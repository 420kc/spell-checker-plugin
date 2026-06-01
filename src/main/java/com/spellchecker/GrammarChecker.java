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
 * Confusion-set grammar checker. Catches the handful of mistakes people actually
 * make in chat (their/there/they're, your/you're, its/it's, to/too, then/than,
 * "should of") and underlines them blue, leaving a one-word right-click fix.
 *
 * DESIGN - precision over recall. The previous engine tried to part-of-speech tag
 * the whole sentence and defaulted every unknown word to NOUN; a single unknown
 * function word ("their going TO die") poisoned the decision and the error was
 * never flagged. This rewrite never asks "is this a noun?". It asks "is the next
 * word in a closed set that PROVES the contraction?" - articles, pronouns,
 * be/aux verbs, modals, degree adverbs and -ing participles are all small, fully
 * enumerable classes. We fire only on that positive evidence and stay silent when
 * unsure, so correct text is essentially never false-flagged.
 *
 * Two tiers, gated by {@link GrammarMode}:
 *   SAFE       - "should of" -> "should have", comparative + "then" -> "than",
 *                plus the user's own phrase list.
 *   AGGRESSIVE - the above plus context homophones.
 */
@Singleton
class GrammarChecker
{
	private static final Pattern WORD = Pattern.compile("[A-Za-z']+");

	// Articles + demonstratives. A possessive (your/their/its) can never sit
	// directly before one of these, so "your a noob" must be "you're a noob".
	private static final Set<String> ARTICLES = set("a", "an", "the");

	// be / auxiliary verbs and the informal contractions that read as "going to".
	// These open a predicate, so "<possessive> <aux>" means the contraction.
	private static final Set<String> AUX = set(
		"be", "been", "being",
		"gonna", "gunna", "wanna", "gotta", "finna");

	// Existential be-verbs that make "there ___" correct ("there is a spider").
	private static final Set<String> EXISTENTIAL = set(
		"is", "are", "was", "were", "has", "have", "had", "will", "would",
		"ain't", "isn't", "aren't", "wasn't", "weren't", "s");

	// Degree / sentence adverbs - a closed class. "<possessive> really/not/so ..."
	// reads as a predicate ("their really good" -> "they're really good").
	private static final Set<String> ADVERBS = set(
		"not", "very", "really", "so", "too", "just", "quite", "almost", "always",
		"never", "often", "sometimes", "usually", "soon", "now", "still", "rather",
		"pretty", "totally", "completely", "absolutely", "definitely", "probably",
		"honestly", "literally", "seriously", "actually", "basically", "clearly",
		"obviously", "barely", "hardly", "nearly", "truly", "simply", "mostly",
		"entirely", "fully", "slightly", "extremely", "incredibly", "super",
		"kinda", "sorta", "highly", "deeply", "fairly", "somewhat", "lowkey",
		"highkey", "def", "prolly");

	// Common predicate adjectives - used after a be-verb. Not exhaustive (open
	// class), but every entry is a true positive signal, never a suppressor.
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
		"dead", "alive", "gone", "lost", "stuck", "safe",
		"rough", "smooth", "wild", "chill", "salty", "toxic", "trash", "nuts");

	// -ing words that are really nouns, so we don't read them as participles.
	private static final Set<String> NOUN_ING = set(
		"thing", "something", "anything", "nothing", "everything", "morning",
		"evening", "ceiling", "building", "feeling", "meeting", "wedding",
		"ring", "king", "string", "wing", "sting", "spring", "swing", "bling",
		"earring", "offspring", "sibling", "ending", "beginning", "saying",
		"painting", "drawing", "warning", "clothing", "lightning", "viking");

	private static final Set<String> COMPARATIVES = set(
		"more", "less", "rather", "other", "better", "worse", "fewer",
		"further", "farther", "greater", "sooner", "later", "else", "bigger",
		"smaller", "taller", "shorter", "easier", "harder", "richer", "poorer",
		"faster", "slower", "older", "younger", "cheaper", "weaker", "stronger",
		"higher", "lower", "longer", "wider", "deeper", "closer", "nicer",
		"cooler", "hotter", "colder", "happier", "sadder", "tougher", "safer",
		"smarter", "dumber", "louder", "softer", "cleaner", "rarer", "wiser");

	// "to" before one of these reads as "too" (excessive / also).
	private static final Set<String> TOO_TRIGGERS = set(
		"much", "many", "late", "early", "big", "small", "hard", "easy",
		"far", "fast", "slow", "soon", "long", "short", "good", "bad",
		"high", "low", "old", "young", "heavy", "tired", "expensive", "cheap",
		"strong", "weak", "loud", "quiet", "hot", "cold", "busy");

	private static final Set<String> MODALS = set(
		"should", "could", "would", "might", "must");

	// Built-in phrases that no contextual rule covers cleanly. Right side is the fix.
	// ("should of" etc. are handled by the modal + "of" rule, not duplicated here.)
	private static final String[][] BUILTIN_PHRASES = {
		{"for all intensive purposes", "for all intents and purposes"},
		{"could care less", "couldn't care less"},
		{"on accident", "by accident"},
		{"your welcome", "you're welcome"},
	};

	private final List<Phrase> builtinPhrases = new ArrayList<>();
	private final List<Phrase> userPhrases = new ArrayList<>();

	GrammarChecker()
	{
		for (String[] p : BUILTIN_PHRASES)
		{
			builtinPhrases.add(new Phrase(p[0], p[1]));
		}
	}

	/** Parse the user's "wrong=>right, ..." extra-phrase list from config. */
	void setUserPhrases(String csv)
	{
		userPhrases.clear();
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
			if (!wrong.isEmpty() && !right.isEmpty() && !wrong.equalsIgnoreCase(right))
			{
				userPhrases.add(new Phrase(wrong, right));
			}
		}
	}

	List<GrammarHit> check(String buffer, GrammarMode mode)
	{
		List<GrammarHit> hits = new ArrayList<>();
		if (mode == GrammarMode.OFF || buffer == null || buffer.isEmpty())
		{
			return hits;
		}
		List<Tok> toks = tokenize(buffer);
		addPhraseHits(buffer, hits);
		addAlwaysWrong(toks, hits);
		if (mode == GrammarMode.AGGRESSIVE)
		{
			addConfusions(toks, hits);
		}
		return dedupeOverlaps(hits);
	}

	/**
	 * Keep grammar hits non-overlapping so the same span never gets two blue
	 * underlines (e.g. the "your welcome" phrase vs. a "your" confusion rule).
	 * Longest span at a given start wins.
	 */
	private static List<GrammarHit> dedupeOverlaps(List<GrammarHit> hits)
	{
		if (hits.size() < 2)
		{
			return hits;
		}
		hits.sort(java.util.Comparator
			.comparingInt(GrammarHit::getStart)
			.thenComparing(java.util.Comparator.comparingInt(GrammarHit::getEnd).reversed()));
		List<GrammarHit> out = new ArrayList<>(hits.size());
		int lastEnd = -1;
		for (GrammarHit h : hits)
		{
			if (h.getStart() >= lastEnd)
			{
				out.add(h);
				lastEnd = h.getEnd();
			}
		}
		return out;
	}

	private void addPhraseHits(String buffer, List<GrammarHit> hits)
	{
		for (Phrase p : builtinPhrases)
		{
			p.findInto(buffer, hits);
		}
		for (Phrase p : userPhrases)
		{
			p.findInto(buffer, hits);
		}
	}

	private void addAlwaysWrong(List<Tok> toks, List<GrammarHit> hits)
	{
		for (int i = 0; i < toks.size(); i++)
		{
			Tok t = toks.get(i);
			switch (t.lower)
			{
				case "of":
					// "should of" -> "should have" (only the "of" is rewritten).
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
					if (predicateFollows(toks, i))
					{
						hits.add(hit(t, "you're"));
					}
					break;
				case "their":
					if (predicateFollows(toks, i))
					{
						hits.add(hit(t, "they're"));
					}
					break;
				case "there":
					// "there is/are ..." is existential and correct; only a
					// non-existential predicate ("there going") means "they're".
					if (!existentialFollows(toks, i) && predicateFollows(toks, i))
					{
						hits.add(hit(t, "they're"));
					}
					break;
				case "its":
					if (predicateFollows(toks, i))
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
	 * True when the word after index {@code i} begins a predicate - i.e. the
	 * possessive there is really "<subject> is/are". Fires only on positive,
	 * closed-class evidence: an article, a be/aux verb, a degree adverb, a known
	 * adjective, or an -ing participle. Anything else (a plain noun, an unknown
	 * word) leaves the possessive alone. This is the whole precision story.
	 */
	private boolean predicateFollows(List<Tok> toks, int i)
	{
		if (i + 1 >= toks.size())
		{
			return false;
		}
		String next = toks.get(i + 1).lower;
		return ARTICLES.contains(next)
			|| AUX.contains(next)
			|| ADVERBS.contains(next)
			|| COMMON_ADJ.contains(next)
			|| isParticiple(next);
	}

	/**
	 * True when "there" is existential. Looks at the next token, and through a
	 * single leading adverb ("there really IS a problem") so the adverb rule
	 * doesn't mistake it for "they're".
	 */
	private boolean existentialFollows(List<Tok> toks, int i)
	{
		if (i + 1 >= toks.size())
		{
			return false;
		}
		if (EXISTENTIAL.contains(toks.get(i + 1).lower))
		{
			return true;
		}
		return ADVERBS.contains(toks.get(i + 1).lower)
			&& i + 2 < toks.size()
			&& EXISTENTIAL.contains(toks.get(i + 2).lower);
	}

	private static boolean isParticiple(String w)
	{
		return w.length() > 4 && w.endsWith("ing") && !NOUN_ING.contains(w);
	}

	private static GrammarHit hit(Tok t, String suggestion)
	{
		return new GrammarHit(t.text, matchCapital(t.text, suggestion), t.start, t.end);
	}

	/** Carry a leading capital from the original onto the suggestion. */
	private static String matchCapital(String original, String suggestion)
	{
		if (!original.isEmpty() && Character.isUpperCase(original.charAt(0)) && !suggestion.isEmpty())
		{
			return Character.toUpperCase(suggestion.charAt(0)) + suggestion.substring(1);
		}
		return suggestion;
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

	/** A fixed multi-word correction, matched case-insensitively on word boundaries. */
	private static final class Phrase
	{
		private final Pattern pattern;
		private final String replacement;

		Phrase(String wrong, String replacement)
		{
			// Collapse internal whitespace to \s+ so spacing variations still match.
			String body = Pattern.quote(wrong).replaceAll("\\s+", "\\\\E\\\\s+\\\\Q");
			this.pattern = Pattern.compile("\\b" + body + "\\b", Pattern.CASE_INSENSITIVE);
			this.replacement = replacement;
		}

		void findInto(String buffer, List<GrammarHit> hits)
		{
			Matcher m = pattern.matcher(buffer);
			while (m.find())
			{
				String matched = buffer.substring(m.start(), m.end());
				hits.add(new GrammarHit(matched, matchCapital(matched, replacement), m.start(), m.end()));
			}
		}
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
