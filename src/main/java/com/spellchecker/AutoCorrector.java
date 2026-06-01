package com.spellchecker;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Singleton;

/**
 * Replace-as-you-type corrections for unambiguous misspellings (alot -> a lot,
 * teh -> the). Unlike the spellchecker (which underlines) and the grammar
 * checker (which suggests on right-click), these fire automatically the moment
 * a word is completed.
 *
 * A word counts as "completed" only when followed by a non-letter - a space or
 * punctuation. The word currently under the caret is never touched, so typing
 * "alo" mid-word is left alone and only "alot " (with the trailing space)
 * triggers the swap. That keeps corrections from firing on partial input.
 */
@Singleton
class AutoCorrector
{
	private final List<Rule> rules = new ArrayList<>();
	private volatile boolean enabled;

	void setEnabled(boolean v)
	{
		this.enabled = v;
	}

	/**
	 * Parse a "wrong=>right, wrong=>right" list. Keys are matched case-insensitively
	 * on word boundaries; a following non-letter is required so we never rewrite the
	 * word the caret is still inside.
	 */
	void setList(String csv)
	{
		rules.clear();
		if (csv == null || csv.isEmpty())
		{
			return;
		}
		for (String entry : csv.split(","))
		{
			int arrow = entry.indexOf("=>");
			if (arrow < 0)
			{
				continue;
			}
			String wrong = entry.substring(0, arrow).trim();
			String right = entry.substring(arrow + 2).trim();
			if (wrong.isEmpty() || right.isEmpty() || wrong.equalsIgnoreCase(right))
			{
				continue;
			}
			// \b…\b anchors the key to whole words; the lookahead demands a trailing
			// non-letter so the still-being-typed final word is never corrected.
			Pattern p = Pattern.compile(
				"\\b" + Pattern.quote(wrong) + "\\b(?=[^A-Za-z']|$)",
				Pattern.CASE_INSENSITIVE);
			rules.add(new Rule(p, right));
		}
	}

	/**
	 * Apply the leftmost applicable correction to {@code buffer}. Returns the
	 * rewritten buffer, or null if nothing changed. Only one correction is applied
	 * per call - the resulting buffer change re-triggers the typed-text event, so a
	 * second pass picks up any remaining ones.
	 */
	String apply(String buffer)
	{
		if (!enabled || buffer == null || buffer.isEmpty())
		{
			return null;
		}
		int bestStart = Integer.MAX_VALUE;
		int bestEnd = -1;
		String bestReplacement = null;
		for (Rule r : rules)
		{
			Matcher m = r.pattern.matcher(buffer);
			if (m.find() && m.start() < bestStart)
			{
				bestStart = m.start();
				bestEnd = m.end();
				bestReplacement = preserveCase(buffer.substring(m.start(), m.end()), r.replacement);
			}
		}
		if (bestReplacement == null)
		{
			return null;
		}
		return buffer.substring(0, bestStart) + bestReplacement + buffer.substring(bestEnd);
	}

	/** Carry a leading capital from the typed word onto the replacement. */
	private static String preserveCase(String matched, String replacement)
	{
		if (!matched.isEmpty() && Character.isUpperCase(matched.charAt(0)) && !replacement.isEmpty())
		{
			return Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1);
		}
		return replacement;
	}

	private static final class Rule
	{
		private final Pattern pattern;
		private final String replacement;

		Rule(Pattern pattern, String replacement)
		{
			this.pattern = pattern;
			this.replacement = replacement;
		}
	}
}
