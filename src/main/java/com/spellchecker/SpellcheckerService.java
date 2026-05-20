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
 * isCorrect() is the only consumer. Suggestion generation lives separately and is
 * intentionally not part of v0 — get flagging right first, then add suggestions.
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
	private static final java.util.regex.Pattern LAUGH = java.util.regex.Pattern.compile(
		"^(ha)+h?$|^(he)+h?$|^(lo)+l?$|^(ah){2,}$|^(eh){2,}$|^a+h+$|^o+h+$|^u+g+h+$"
	);

	private final Set<String> baseDict = new HashSet<>();
	private final Set<String> customDict = new HashSet<>();

	void load()
	{
		baseDict.clear();
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
				}
			}
		}
		catch (IOException e)
		{
			log.warn("Failed to load common-words.txt", e);
		}
		log.debug("Loaded {} base words", baseDict.size());
	}

	void setCustomDict(String csv)
	{
		customDict.clear();
		if (csv == null || csv.isEmpty())
		{
			return;
		}
		for (String w : csv.split(","))
		{
			String t = w.trim().toLowerCase();
			if (!t.isEmpty())
			{
				customDict.add(t);
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
		if (baseDict.contains(t) || customDict.contains(t))
		{
			return true;
		}
		// strip a trailing 's or s and try the root, so "tbow's" and "scythes" hit the dict
		String root = t;
		if (root.endsWith("'s") && root.length() > 2)
		{
			root = root.substring(0, root.length() - 2);
		}
		else if (root.endsWith("s") && root.length() > 1)
		{
			root = root.substring(0, root.length() - 1);
		}
		return baseDict.contains(root) || customDict.contains(root);
	}

	private boolean isChatspeak(String t)
	{
		if (CHATSPEAK.contains(t))
		{
			return true;
		}
		// numbers with optional k/m/b suffix: 1m, 500k, 2b, 100
		if (t.matches("\\d+[kmb]?"))
		{
			return true;
		}
		// account-style alphanumeric mix: w301, lvl99 — leave alone
		if (t.matches("[a-z]+\\d+") || t.matches("\\d+[a-z]+"))
		{
			return true;
		}
		// haha / hehe / lolol / ahhh / ohhh / ughhh
		if (LAUGH.matcher(t).matches())
		{
			return true;
		}
		return false;
	}

	/**
	 * Suggest up to {@code max} corrections for the given token by Damerau-Levenshtein
	 * distance. Short words (≤4 chars) only allow 1 edit to avoid noisy suggestions;
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
