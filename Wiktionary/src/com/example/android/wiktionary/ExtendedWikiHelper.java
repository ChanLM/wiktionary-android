/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.wiktionary;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.example.android.wiktionary.SimpleWikiHelper.ApiException;
import com.example.android.wiktionary.SimpleWikiHelper.ParseException;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extended version of {@link SimpleWikiHelper}. This version adds methods to
 * pick a random word, and to format generic wiki-style text into HTML.
 */
public class ExtendedWikiHelper extends SimpleWikiHelper {
	private static final Random RANDOM = new Random(System.currentTimeMillis());

	/**
	 * HTML style sheet to include with any {@link #formatWikiText(String)} HTML
	 * results. It formats nicely for a mobile screen, and hides some content
	 * boxes to keep things tidy.
	 */
	private static final String STYLE_SHEET = "<style>h2 {font-size:1.2em;font-weight:normal;} "
			+ "a {color:#6688cc;} ol {padding-left:1.5em;} blockquote {margin-left:0em;} "
			+ ".interProject, .noprint {display:none;} "
			+ "li, blockquote {margin-top:0.5em;margin-bottom:0.5em;}</style>";

	/**
	 * Pattern of section titles we're interested in showing. This trims out
	 * extra sections that can clutter things up on a mobile screen.
	 */
	private static final Pattern sValidSections = Pattern.compile(
			"(verb|noun|adjective|pronoun|interjection|adverb)",
			Pattern.CASE_INSENSITIVE);

	/**
	 * Pattern that can be used to split a returned wiki page into its various
	 * sections. Doesn't treat children sections differently.
	 */
	private static final Pattern sSectionSplit = Pattern.compile(
			"^=+(.+?)=+.+?(?=^=)", Pattern.MULTILINE | Pattern.DOTALL);

	/**
	 * When picking random words in {@link #getRandomWord()}, we sometimes
	 * encounter special articles or templates. This pattern ignores any words
	 * like those, usually because they have ":" or other punctuation.
	 */
	private static final Pattern sInvalidWord = Pattern
			.compile("[^A-Za-z0-9 ]");

	/**
	 * {@link Uri} authority to use when creating internal links.
	 */
	public static final String WIKI_AUTHORITY = "wiktionary";

	/**
	 * {@link Uri} host to use when creating internal links.
	 */
	public static final String WIKI_LOOKUP_HOST = "lookup";

	/**
	 * Mime-type to use when showing parsed results in a {@link WebView}.
	 */
	public static final String MIME_TYPE = "text/html";

	/**
	 * Fake section to insert at the bottom of a wiki response before parsing.
	 * This ensures that {@link #sSectionSplit} will always catch the last
	 * section, as it uses section headers in its searching.
	 */
	private static final String STUB_SECTION = "\n=Stub section=";

	/**
	 * Number of times to try finding a random word in {@link #getRandomWord()}.
	 * These failures are usually when the found word fails the
	 * {@link #sInvalidWord} test, or when a network error happens.
	 */
	private static final int RANDOM_TRIES = 5;

	/**
	 * Internal class to hold a wiki formatting rule. It's mostly a wrapper to
	 * simplify {@link Matcher#replaceAll(String)}.
	 */
	private static class FormatRule {
		private Pattern mPattern;
		private String mReplaceWith;

		/**
		 * Create a wiki formatting rule.
		 * 
		 * @param pattern
		 *            Search string to be compiled into a {@link Pattern}.
		 * @param replaceWith
		 *            String to replace any found occurances with. This string
		 *            can also include back-references into the given pattern.
		 * @param flags
		 *            Any flags to compile the {@link Pattern} with.
		 */
		public FormatRule(String pattern, String replaceWith, int flags) {
			mPattern = Pattern.compile(pattern, flags);
			mReplaceWith = replaceWith;
		}

		/**
		 * Create a wiki formatting rule.
		 * 
		 * @param pattern
		 *            Search string to be compiled into a {@link Pattern}.
		 * @param replaceWith
		 *            String to replace any found occurances with. This string
		 *            can also include back-references into the given pattern.
		 */
		public FormatRule(String pattern, String replaceWith) {
			this(pattern, replaceWith, 0);
		}

		/**
		 * Apply this formatting rule to the given input string, and return the
		 * resulting new string.
		 */
		public String apply(String input) {
			Matcher m = mPattern.matcher(input);
			return m.replaceAll(mReplaceWith);
		}

	}

	/**
	 * List of internal formatting rules to apply when parsing wiki text. These
	 * include indenting various bullets, apply italic and bold styles, and
	 * adding internal linking.
	 */
	private static final List<FormatRule> sFormatRules = new ArrayList<FormatRule>();

	static {
		// Format header blocks and wrap outside content in ordered list
		sFormatRules.add(new FormatRule("^=+(.+?)=+", "</ol><h2>$1</h2><ol>",
				Pattern.MULTILINE));

		// Indent quoted blocks, handle ordered and bullet lists
		sFormatRules.add(new FormatRule("^#+\\*?:(.+?)$",
				"<blockquote>$1</blockquote>", Pattern.MULTILINE));
		sFormatRules.add(new FormatRule("^#+:?\\*(.+?)$",
				"<ul><li>$1</li></ul>", Pattern.MULTILINE));
		sFormatRules.add(new FormatRule("^#+(.+?)$", "<li>$1</li>",
				Pattern.MULTILINE));

		// Add internal links
		sFormatRules.add(new FormatRule("\\[\\[([^:\\|\\]]+)\\]\\]", String
				.format("<a href=\"%s://%s/$1\">$1</a>", WIKI_AUTHORITY,
						WIKI_LOOKUP_HOST)));
		sFormatRules.add(new FormatRule(
				"\\[\\[([^:\\|\\]]+)\\|([^\\]]+)\\]\\]", String.format(
						"<a href=\"%s://%s/$1\">$2</a>", WIKI_AUTHORITY,
						WIKI_LOOKUP_HOST)));

		// Add bold and italic formatting
		sFormatRules.add(new FormatRule("'''(.+?)'''", "<b>$1</b>"));
		sFormatRules.add(new FormatRule("([^'])''([^'].*?[^'])''([^'])",
				"$1<i>$2</i>$3"));

		// Remove odd category links and convert remaining links into flat text
		sFormatRules.add(new FormatRule(
				"(\\{+.+?\\}+|\\[\\[[^:]+:[^\\\\|\\]]+\\]\\]|"
						+ "\\[http.+?\\]|\\[\\[Category:.+?\\]\\])", "",
				Pattern.MULTILINE | Pattern.DOTALL));
		sFormatRules.add(new FormatRule("\\[\\[([^\\|\\]]+\\|)?(.+?)\\]\\]",
				"$2", Pattern.MULTILINE));

	}

	/**
	 * Get the WOTD for a random date. Note there are only 365 possible
	 * responses for any given day. Random future dates wrap back to the
	 * 
	 * @return Random dictionary word, or null if no valid word was found.
	 * @throws ApiException
	 *             If any connection or server error occurs.
	 * @throws ParseException
	 *             If there are problems parsing the response.
	 */
	public static String getRandomWord(Context context) throws ApiException,
			ParseException {
		Resources res = context.getResources();
		String[] monthNames = res.getStringArray(R.array.month_names);

		// Keep trying a few times until we find a valid word
		int tries = 0;
		while (tries++ < RANDOM_TRIES) {
			// pick random day of year, 1-365
			int rDoy = RANDOM.nextInt(364) + 1;
			Calendar c = Calendar.getInstance();
			// set cal's day of year to random day of year
			// the corresponding random month and day of month
			// are used below to build the page title
			c.set(Calendar.DAY_OF_YEAR, rDoy);

			// random month, day, year
			String rMonth = monthNames[c.get(Calendar.MONTH)];
			int rDay = c.get(Calendar.DAY_OF_MONTH);
			int rYear = c.get(Calendar.YEAR) - RANDOM.nextInt(4);

			// Build the page title for the archive month, year
			String pageName = res.getString(
					R.string.template_wotd_archive_title, rYear, rMonth);
			String pageContent = null;

			try {
				// query for the archive page
				// archive page contains all words for that year / month
				SimpleWikiHelper.prepareUserAgent(context);
				pageContent = SimpleWikiHelper.getPageContent(pageName, false);
			} catch (ApiException e) {
				Log.e("WordWidget", "Couldn't contact API", e);
			} catch (ParseException e) {
				Log.e("WordWidget", "Couldn't parse API response", e);
			}

			Matcher matcher = null;
			if (pageContent != null) {
				matcher = Pattern.compile(WordWidget.WOTD_PATTERN).matcher(
						pageContent);
			}
			if (matcher != null) {
				// iterate to randomly selected day
				for (int i = 0; i < rDay && matcher.find(); i++) {
					if (i != rDay - 1) {
						continue;
					}
					String foundWord = matcher.group(1);
					if (foundWord != null
							&& !sInvalidWord.matcher(foundWord).find()) {
						return foundWord;
					}
				}
			}
		}

		// No valid word found in number of tries, so return null
		return null;
	}

	/**
	 * Format the given wiki-style text into formatted HTML content. This will
	 * create headers, lists, internal links, and style formatting for any wiki
	 * markup found.
	 * 
	 * @param wikiText
	 *            The raw text to format, with wiki-markup included.
	 * @return HTML formatted content, ready for display in {@link WebView}.
	 */
	public static String formatWikiText(String wikiText) {
		if (wikiText == null) {
			return null;
		}

		// Insert a fake last section into the document so our section splitter
		// can correctly catch the last section.
		wikiText = wikiText.concat(STUB_SECTION);

		// Read through all sections, keeping only those matching our filter,
		// and only including the first entry for each title.
		HashSet<String> foundSections = new HashSet<String>();
		StringBuilder builder = new StringBuilder();

		Matcher sectionMatcher = sSectionSplit.matcher(wikiText);
		while (sectionMatcher.find()) {
			String title = sectionMatcher.group(1);
			if (!foundSections.contains(title)
					&& sValidSections.matcher(title).matches()) {
				String sectionContent = sectionMatcher.group();
				foundSections.add(title);
				builder.append(sectionContent);
			}
		}

		// Our new wiki text is the selected sections only
		wikiText = builder.toString();

		// Apply all formatting rules, in order, to the wiki text
		for (FormatRule rule : sFormatRules) {
			wikiText = rule.apply(wikiText);
		}

		// Return the resulting HTML with style sheet, if we have content left
		if (!TextUtils.isEmpty(wikiText)) {
			return STYLE_SHEET + wikiText;
		} else {
			return null;
		}
	}

}
