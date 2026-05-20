package com.spellchecker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
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
		"ea", "smh", "fyi", "btw", "idk", "idc", "asap", "rip", "yikes", "ez", "ezpz"
	)));

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
		return false;
	}
}
