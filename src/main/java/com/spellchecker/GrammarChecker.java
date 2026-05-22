package com.spellchecker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Singleton;

/**
 * Sentence-aware grammar checker. Tokenizes the whole typed line and judges
 * commonly-confused words by how they're used in context (the part-of-speech of
 * their neighbours), instead of matching a fixed list of phrases.
 *
 * Two tiers, gated by {@link GrammarMode}:
 *   SAFE       - unambiguous errors only: "alot", "should of", comparative + "then".
 *   AGGRESSIVE - the above plus context homophones: your/you're, their/they're,
 *                there/they're, its/it's, to/too.
 *
 * Each hit is a single misused token (e.g. just "your"), so the underline sits on
 * the wrong word and the suggestion swaps that one word — the rest of the sentence
 * is left alone.
 */
@Singleton
class GrammarChecker
{
	private static final Pattern WORD = Pattern.compile("[A-Za-z']+");

	private static final Set<String> DETERMINERS = set(
		"a", "an", "the", "this", "that", "these", "those",
		"my", "your", "his", "her", "its", "our", "their",
		"no", "some", "any", "every", "each", "another");

	// Auxiliaries / be-verbs that signal "<subject> is/are ...". Includes the
	// informal "gonna/wanna/gotta" since they read as "going to".
	private static final Set<String> AUX = set(
		"am", "is", "are", "was", "were", "be", "been", "being",
		"do", "does", "did", "have", "has", "had",
		"will", "would", "can", "could", "shall", "should", "may", "might", "must",
		"gonna", "wanna", "gotta", "finna");

	// Subset of AUX that reads as existential after "there" ("there has been ...").
	// Used to NOT rewrite "there is/are/was/been" to "they're".
	private static final Set<String> EXISTENTIAL = set(
		"is", "are", "was", "were", "has", "have", "had", "will", "would", "been");

	private static final Set<String> ADVERBS = set(
		"very", "really", "so", "too", "just", "quite", "almost", "always",
		"never", "often", "sometimes", "usually", "soon", "now", "again",
		"also", "even", "still", "rather", "pretty", "totally", "completely",
		"absolutely", "definitely", "probably", "honestly", "literally",
		"seriously", "actually", "basically", "clearly", "obviously", "barely",
		"hardly", "nearly", "truly", "simply", "mostly", "entirely", "fully",
		"slightly", "extremely", "incredibly", "super", "kinda", "sorta",
		"highly", "deeply", "fairly", "somewhat", "way", "lowkey", "highkey");

	private static final Set<String> COMMON_ADJ = set(
		"good", "great", "bad", "nice", "mean", "cool", "awesome", "amazing",
		"incredible", "beautiful", "ugly", "pretty", "handsome", "cute",
		"smart", "dumb", "stupid", "clever", "wise", "crazy", "insane",
		"right", "wrong", "correct", "true", "false", "real", "fake",
		"funny", "serious", "happy", "sad", "mad", "angry", "glad", "upset",
		"scared", "afraid", "brave", "proud", "lucky", "sorry", "fine",
		"big", "small", "huge", "tiny", "tall", "short", "long", "wide",
		"fast", "slow", "quick", "hot", "cold", "warm", "wet", "dry",
		"hard", "soft", "easy", "tough", "strong", "weak", "heavy", "light",
		"rich", "poor", "cheap", "expensive", "free", "busy", "lazy", "tired",
		"sick", "healthy", "fit", "fat", "thin", "clean", "dirty", "new", "old",
		"young", "early", "late", "fresh", "loud", "quiet", "bright", "dark",
		"clear", "sharp", "dull", "deep", "high", "low", "full", "empty",
		"close", "open", "ready", "done", "sure", "certain", "weird", "strange",
		"normal", "special", "rare", "common", "perfect", "terrible", "horrible",
		"wonderful", "fantastic", "excellent", "decent", "solid", "legit",
		"broke", "broken", "cracked", "based", "cringe", "goated", "mid", "washed",
		"next", "last", "first", "best", "worst", "main", "whole", "entire");

	// -ing / -ed words that are really nouns, so we don't read them as verbs.
	private static final Set<String> NOUN_ING = set(
		"thing", "something", "anything", "nothing", "everything", "morning",
		"evening", "ceiling", "building", "feeling", "meeting", "wedding",
		"ring", "king", "string", "wing", "sting", "spring", "swing", "bling",
		"earring", "offspring", "sibling", "ending", "beginning", "bed", "red",
		"shed", "sled", "wed", "fed", "bred");

	private static final Set<String> NOUN_LY = set(
		"family", "supply", "reply", "apply", "ally", "bully", "jelly", "rally",
		"only", "ugly", "silly", "early", "holy", "lily", "rely", "comply",
		"imply", "multiply", "assembly", "anomaly", "monopoly", "belly",
		"folly", "gully", "alley", "valley", "volley", "trolley", "july");

	private static final Set<String> COMPARATIVES = set(
		"more", "less", "rather", "other", "better", "worse", "fewer",
		"further", "farther", "greater", "sooner", "later", "else", "bigger",
		"smaller", "taller", "shorter", "easier", "harder", "richer", "poorer",
		"faster", "slower", "older", "younger", "cheaper", "weaker", "stronger",
		"higher", "lower", "longer", "wider", "deeper", "closer", "nicer",
		"cooler", "hotter", "colder", "happier", "sadder", "tougher", "safer",
		"smarter", "dumber", "louder", "softer", "cleaner", "rarer", "wiser");

	// "to" before these reads as "too" (excessive / also).
	private static final Set<String> TOO_TRIGGERS = set(
		"much", "many", "late", "early", "big", "small", "hard", "easy",
		"far", "fast", "slow", "soon", "long", "short", "good", "bad",
		"high", "low", "old", "young", "heavy", "tired", "expensive", "cheap",
		"strong", "weak", "loud", "quiet", "hot", "cold", "busy");

	private static final Set<String> MODALS = set(
		"should", "could", "would", "might", "must");

	private enum Pos
	{
		DET, AUX, ADV, ADJ, VERB, NOUN
	}

	List<GrammarHit> check(String buffer, GrammarMode mode)
	{
		List<GrammarHit> hits = new ArrayList<>();
		if (mode == GrammarMode.OFF || buffer == null || buffer.isEmpty())
		{
			return hits;
		}
		List<Tok> toks = tokenize(buffer);
		addAlwaysWrong(toks, hits);
		if (mode == GrammarMode.AGGRESSIVE)
		{
			addConfusions(toks, hits);
		}
		return hits;
	}

	private void addAlwaysWrong(List<Tok> toks, List<GrammarHit> hits)
	{
		for (int i = 0; i < toks.size(); i++)
		{
			Tok t = toks.get(i);
			switch (t.lower)
			{
				case "alot":
					hits.add(hit(t, "a lot"));
					break;
				case "alittle":
					hits.add(hit(t, "a little"));
					break;
				case "abit":
					hits.add(hit(t, "a bit"));
					break;
				case "of":
					if (i > 0 && MODALS.contains(toks.get(i - 1).lower))
					{
						hits.add(hit(t, "have"));
					}
					break;
				case "then":
					if (i > 0 && COMPARATIVES.contains(toks.get(i - 1).lower))
					{
						hits.add(hit(t, "than"));
					}
					break;
				default:
					break;
			}
		}
	}

	private void addConfusions(List<Tok> toks, List<GrammarHit> hits)
	{
		for (int i = 0; i < toks.size(); i++)
		{
			Tok t = toks.get(i);
			switch (t.lower)
			{
				case "your":
					if (impliesToBe(toks, i))
					{
						hits.add(hit(t, "you're"));
					}
					break;
				case "their":
					if (impliesToBe(toks, i))
					{
						hits.add(hit(t, "they're"));
					}
					break;
				case "there":
					if (impliesToBeNotExistential(toks, i))
					{
						hits.add(hit(t, "they're"));
					}
					break;
				case "its":
					if (impliesToBe(toks, i))
					{
						hits.add(hit(t, "it's"));
					}
					break;
				case "to":
					if (i + 1 < toks.size() && TOO_TRIGGERS.contains(toks.get(i + 1).lower))
					{
						hits.add(hit(t, "too"));
					}
					break;
				default:
					break;
			}
		}
	}

	/**
	 * True when a possessive word ("your"/"their"/"its") at index i is actually
	 * being used as "<subject> is/are ...": the next word begins a be-complement
	 * (a/an/the, an aux/be-verb, an adverb, an adjective, or an -ing/-ed
	 * participle) AND the adjective/adverb run does not terminate in a noun
	 * (which would make it a legit possessive noun phrase like "your nice car").
	 */
	private boolean impliesToBe(List<Tok> toks, int i)
	{
		if (i + 1 >= toks.size())
		{
			return false;
		}
		Tok next = toks.get(i + 1);
		// "your a/an/the X" is never a valid possessive → always "you're".
		if (next.lower.equals("a") || next.lower.equals("an") || next.lower.equals("the"))
		{
			return true;
		}
		Pos p = classify(next.lower);
		if (p == Pos.AUX)
		{
			return true; // "its been", "your being"
		}
		if (p != Pos.ADV && p != Pos.ADJ && p != Pos.VERB)
		{
			return false;
		}
		return !runEndsInNoun(toks, i + 1);
	}

	private boolean impliesToBeNotExistential(List<Tok> toks, int i)
	{
		if (i + 1 >= toks.size())
		{
			return false;
		}
		// "there is / are / was / been ..." is existential — leave it.
		if (EXISTENTIAL.contains(toks.get(i + 1).lower))
		{
			return false;
		}
		return impliesToBe(toks, i);
	}

	/**
	 * Walk a run of adverbs/adjectives/participles from {@code from}; if the first
	 * non-modifier word is a noun, the whole thing is a possessive noun phrase.
	 */
	private boolean runEndsInNoun(List<Tok> toks, int from)
	{
		for (int j = from; j < toks.size(); j++)
		{
			Pos p = classify(toks.get(j).lower);
			if (p == Pos.ADV || p == Pos.ADJ || p == Pos.VERB)
			{
				continue;
			}
			return p == Pos.NOUN;
		}
		return false; // only modifiers to the end → "<subject> is <modifiers>"
	}

	private Pos classify(String w)
	{
		if (DETERMINERS.contains(w))
		{
			return Pos.DET;
		}
		if (AUX.contains(w))
		{
			return Pos.AUX;
		}
		if (COMMON_ADJ.contains(w))
		{
			return Pos.ADJ;
		}
		if (ADVERBS.contains(w))
		{
			return Pos.ADV;
		}
		if (w.length() > 3 && w.endsWith("ly") && !NOUN_LY.contains(w))
		{
			return Pos.ADV;
		}
		if (w.length() > 4 && (w.endsWith("ing") || w.endsWith("ed")) && !NOUN_ING.contains(w))
		{
			return Pos.VERB;
		}
		if (w.endsWith("ful") || w.endsWith("ous") || w.endsWith("ive")
			|| w.endsWith("able") || w.endsWith("ible") || w.endsWith("ent")
			|| w.endsWith("ant") || w.endsWith("less"))
		{
			return Pos.ADJ;
		}
		return Pos.NOUN;
	}

	private static GrammarHit hit(Tok t, String suggestion)
	{
		String s = suggestion;
		// Preserve a leading capital ("Your great" → "You're great").
		if (!t.text.isEmpty() && Character.isUpperCase(t.text.charAt(0)) && !s.isEmpty())
		{
			s = Character.toUpperCase(s.charAt(0)) + s.substring(1);
		}
		return new GrammarHit(t.text, s, t.start, t.end);
	}

	private static List<Tok> tokenize(String buffer)
	{
		List<Tok> toks = new ArrayList<>();
		Matcher m = WORD.matcher(buffer);
		while (m.find())
		{
			toks.add(new Tok(m.group(), m.group().toLowerCase(), m.start(), m.end()));
		}
		return toks;
	}

	private static Set<String> set(String... words)
	{
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(words)));
	}

	private static final class Tok
	{
		private final String text;
		private final String lower;
		private final int start;
		private final int end;

		Tok(String text, String lower, int start, int end)
		{
			this.text = text;
			this.lower = lower;
			this.start = start;
			this.end = end;
		}
	}
}
