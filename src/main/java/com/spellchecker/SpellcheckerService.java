package com.spellchecker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Dictionary lookup for the spellchecker.
 *
 * Words live in three buckets:
 *   - baseDict: bundled common-English seed from resources/common-words.txt
 *   - customDict: user-supplied words from config (RSNs, OSRS terms, friend names)
 *   - chatspeak: a hardcoded allowlist for the obvious abbreviations
 *
 * isCorrect() answers "is this a real word?". suggest() returns nearby words for
 * display-only hints.
 */
@Singleton
@Slf4j
class SpellcheckerService
{
	private static final Set<String> CHATSPEAK = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"ty", "tyvm", "tysm", "gz", "gratz", "kk", "lol", "lmao", "lmfao", "wp", "gg",
		"gl", "hf", "ge", "pls", "plz", "plox", "ofc", "nvm", "irl", "rs", "afk",
		"brb", "rn", "tbh", "imo", "imho", "wtf", "wyd", "wbu", "ikr", "fr", "ong",
		"np", "nm", "yw", "ily", "iyk", "sus", "yh", "ya", "yo", "sup", "aye",
		"rsn", "osrs", "rs3", "cc", "fc", "mc", "gp", "pvm", "pvp", "kc", "xp",
		"ea", "smh", "fyi", "btw", "idk", "idc", "asap", "rip", "yikes", "ez", "ezpz",
		"omg", "omfg", "ngl", "rly", "mhm", "mmhmm", "pog", "poggers", "kek", "mb",
		"ig", "ttyl", "bruh", "bruv", "ayy", "ayyy", "yass", "yasss", "ggwp", "gege"
	)));

	// Repeated-syllable laughs: haha, hahaha, hehe, hehehe, lolol, lololol, etc.
	private static final Pattern LAUGH = Pattern.compile(
		"^(ha)+h?$|^(he)+h?$|^(lo)+l?$|^(ah){2,}$|^(eh){2,}$|^a+h+$|^o+h+$|^u+g+h+$"
	);

	// Numbers with optional k/m/b (1m, 500k, 100), account-style alphanumerics
	// (w301, lvl99), and drawn-out interjections (yo/yoo/yooo, ayy, woo).
	private static final Pattern NUMBER = Pattern.compile("\\d+[kmb]?");
	private static final Pattern ACCOUNT = Pattern.compile("[a-z]+\\d+|\\d+[a-z]+");
	private static final Pattern INTERJECTION = Pattern.compile("yo+|ay+|wo+");

	private final Set<String> baseDict = new HashSet<>();
	private final Set<String> customDict = new HashSet<>();
	// Apostrophe-stripped forms of entries that contain an apostrophe, so that
	// "dont" / "youre" / "isnt" can match "don't" / "you're" / "isn't" when the
	// ignore-punctuation toggle is on. Kept in sync with the main sets.
	private final Set<String> baseDictStripped = new HashSet<>();
	private final Set<String> customDictStripped = new HashSet<>();
	private volatile boolean ignorePunctuation;

	void load()
	{
		baseDict.clear();
		baseDictStripped.clear();
		try (InputStream in = getClass().getResourceAsStream("/com/spellchecker/common-words.txt"))
		{
			if (in == null)
			{
				log.warn("common-words.txt not on classpath; baseDict empty");
				return;
			}
			try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = r.readLine()) != null)
				{
					String w = line.trim().toLowerCase();
					if (w.isEmpty() || w.startsWith("#"))
					{
						continue;
					}
					baseDict.add(w);
					if (w.indexOf('\'') >= 0)
					{
						baseDictStripped.add(w.replace("'", ""));
					}
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to load common-words.txt", e);
		}
		log.debug("Loaded {} base words ({} apostrophe-stripped variants)",
			baseDict.size(), baseDictStripped.size());
	}

	void setIgnorePunctuation(boolean v)
	{
		this.ignorePunctuation = v;
	}

	void setCustomDict(String csv)
	{
		customDict.clear();
		customDictStripped.clear();
		if (csv == null || csv.isEmpty())
		{
			return;
		}
		for (String w : csv.split(","))
		{
			String t = w.trim().toLowerCase();
			if (t.isEmpty())
			{
				continue;
			}
			customDict.add(t);
			if (t.indexOf('\'') >= 0)
			{
				customDictStripped.add(t.replace("'", ""));
			}
		}
	}

	boolean isCorrect(String token)
	{
		if (token == null || token.isEmpty())
		{
			return true;
		}
		String t = token.toLowerCase();
		if (isChatspeak(t))
		{
			return true;
		}
		if (inDict(t))
		{
			return true;
		}
		// Strip a trailing 's (possessive) and try the root.
		if (t.endsWith("'s") && t.length() > 2 && inDict(t.substring(0, t.length() - 2)))
		{
			return true;
		}
		// Try common English suffixes with morphological reversals (e-drop,
		// consonant doubling, y/i). If any candidate root is in the dict, accept.
		for (String suffix : SUFFIXES)
		{
			if (!t.endsWith(suffix) || t.length() <= suffix.length() + 1)
			{
				continue;
			}
			String root = t.substring(0, t.length() - suffix.length());
			if (inDict(root))
			{
				return true;
			}
			// e-drop reversal: hoped -> hope, taker -> take, making -> make
			if (inDict(root + "e"))
			{
				return true;
			}
			// consonant-doubling reversal: running -> run, stopped -> stop, planner -> plan
			if (root.length() >= 2 && root.charAt(root.length() - 1) == root.charAt(root.length() - 2)
				&& inDict(root.substring(0, root.length() - 1)))
			{
				return true;
			}
			// y/i reversal: happily -> happy, easier -> easy, prettiest -> pretty
			if (root.endsWith("i") && inDict(root.substring(0, root.length() - 1) + "y"))
			{
				return true;
			}
		}
		return false;
	}

	private boolean inDict(String w)
	{
		if (baseDict.contains(w) || customDict.contains(w))
		{
			return true;
		}
		// When ignorePunctuation is on, also accept tokens whose form matches an
		// apostrophe-stripped dict entry (dont -> don't, youre -> you're).
		return ignorePunctuation
			&& (baseDictStripped.contains(w) || customDictStripped.contains(w));
	}

	// Order is not load-bearing - every suffix that matches is tried. Listed
	// roughly longest-first for readability.
	private static final String[] SUFFIXES = {
		"ation", "ition", "ities", "iness",
		"ness", "ment", "able", "ible", "ious", "less", "ful", "ing",
		"tion", "sion", "ize", "ise", "ist", "ity", "ous",
		"ies", "iest", "ier", "ily",
		"ers", "est", "ed", "es", "er", "ly", "s", "y"
	};

	private boolean isChatspeak(String t)
	{
		if (CHATSPEAK.contains(t))
		{
			return true;
		}
		return NUMBER.matcher(t).matches()
			|| ACCOUNT.matcher(t).matches()
			|| LAUGH.matcher(t).matches()
			|| INTERJECTION.matcher(t).matches();
	}

	/**
	 * Suggest up to {@code max} corrections for the given token by Damerau-Levenshtein
	 * distance. Short words (<= 4 chars) only allow 1 edit to avoid noisy suggestions;
	 * longer words allow up to 2.
	 */
	List<String> suggest(String token, int max)
	{
		if (token == null || token.isEmpty())
		{
			return Collections.emptyList();
		}
		String t = token.toLowerCase();
		int threshold = t.length() <= 4 ? 1 : 2;

		List<Scored> scored = new ArrayList<>();
		score(t, baseDict, threshold, scored);
		score(t, customDict, threshold, scored);

		scored.sort(Comparator.<Scored>comparingInt(s -> s.distance).thenComparing(s -> s.word));
		List<String> out = new ArrayList<>(max);
		for (Scored s : scored)
		{
			if (out.size() >= max)
			{
				break;
			}
			if (!out.contains(s.word))
			{
				out.add(s.word);
			}
		}
		return out;
	}

	private void score(String t, Set<String> dict, int threshold, List<Scored> out)
	{
		for (String w : dict)
		{
			if (Math.abs(w.length() - t.length()) > threshold)
			{
				continue;
			}
			int d = damerau(t, w, threshold);
			if (d <= threshold)
			{
				out.add(new Scored(w, d));
			}
		}
	}

	/**
	 * Damerau-Levenshtein distance with early-out at {@code max}. Returns max+1 if
	 * the true distance exceeds the bound so we can prune.
	 */
	private static int damerau(String a, String b, int max)
	{
		int n = a.length();
		int m = b.length();
		if (Math.abs(n - m) > max)
		{
			return max + 1;
		}
		int[][] dp = new int[n + 1][m + 1];
		for (int i = 0; i <= n; i++)
		{
			dp[i][0] = i;
		}
		for (int j = 0; j <= m; j++)
		{
			dp[0][j] = j;
		}
		for (int i = 1; i <= n; i++)
		{
			int rowMin = Integer.MAX_VALUE;
			for (int j = 1; j <= m; j++)
			{
				int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
				int v = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
				if (i > 1 && j > 1
					&& a.charAt(i - 1) == b.charAt(j - 2)
					&& a.charAt(i - 2) == b.charAt(j - 1))
				{
					v = Math.min(v, dp[i - 2][j - 2] + 1);
				}
				dp[i][j] = v;
				if (v < rowMin)
				{
					rowMin = v;
				}
			}
			if (rowMin > max)
			{
				return max + 1;
			}
		}
		return dp[n][m];
	}

	private static final class Scored
	{
		final String word;
		final int distance;

		Scored(String word, int distance)
		{
			this.word = word;
			this.distance = distance;
		}
	}
}
