package com.spellchecker;

import java.awt.Color;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

@ConfigGroup(SpellcheckerConfig.GROUP)
public interface SpellcheckerConfig extends Config
{
	String GROUP = "spellchecker";

	@ConfigSection(
		name = "Grammar",
		description = "Underline common grammar / word-use mistakes in blue",
		position = 10
	)
	String grammarSection = "grammarSection";

	@ConfigSection(
		name = "Auto-correct",
		description = "Fix obvious typos automatically as you type",
		position = 20
	)
	String autocorrectSection = "autocorrectSection";

	@ConfigSection(
		name = "Dictionary",
		description = "Words treated as correct, and how matching behaves",
		position = 30
	)
	String dictionarySection = "dictionarySection";

	@ConfigSection(
		name = "Advanced",
		description = "Appearance and debugging",
		position = 40,
		closedByDefault = true
	)
	String advancedSection = "advancedSection";

	@ConfigItem(
		keyName = "enabled",
		name = "Enable",
		description = "Underline misspelled words as you type in chat",
		position = 0
	)
	default boolean enabled()
	{
		return true;
	}

	// --- Grammar ---------------------------------------------------------

	@ConfigItem(
		keyName = "grammarMode",
		name = "Grammar checking",
		description = "How aggressively to flag grammar/usage mistakes. "
			+ "Safe = only unambiguous errors (should of, better then) plus your phrases. "
			+ "Aggressive = also context-aware homophones (your/you're, their/they're/there, its/it's, to/too).",
		section = grammarSection,
		position = 11
	)
	default GrammarMode grammarMode()
	{
		return GrammarMode.AGGRESSIVE;
	}

	@ConfigItem(
		keyName = "grammarPhrases",
		name = "Extra phrases",
		description = "Your own grammar phrases to underline blue. Format: wrong=>right, "
			+ "comma-separated. Example: should of=>should have, could care less=>couldn't care less",
		section = grammarSection,
		position = 12
	)
	default String grammarPhrases()
	{
		return "";
	}

	// --- Auto-correct ----------------------------------------------------

	@ConfigItem(
		keyName = "autoCorrect",
		name = "Auto-correct typos",
		description = "Replace common misspellings the moment you finish the word (after a space)",
		section = autocorrectSection,
		position = 21
	)
	default boolean autoCorrect()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoCorrectList",
		name = "Corrections",
		description = "Words to auto-replace. Format: wrong=>right, comma-separated. "
			+ "Edit freely - remove a default by deleting its entry.",
		section = autocorrectSection,
		position = 22
	)
	default String autoCorrectList()
	{
		return "alot=>a lot, teh=>the, thier=>their, recieve=>receive, seperate=>separate, "
			+ "definately=>definitely, occured=>occurred, untill=>until, wich=>which, "
			+ "becuase=>because, freind=>friend, beleive=>believe, wierd=>weird, tommorow=>tomorrow";
	}

	// --- Dictionary ------------------------------------------------------

	@ConfigItem(
		keyName = "minLength",
		name = "Min word length",
		description = "Skip tokens shorter than this (keeps ty / gz / kk from lighting up)",
		section = dictionarySection,
		position = 31
	)
	default int minLength()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "customDict",
		name = "Personal dictionary",
		description = "Comma-separated words to treat as correct (RSNs, OSRS terms, friend names). "
			+ "Right-click a flagged word -> Add to dictionary to grow this automatically.",
		section = dictionarySection,
		position = 32
	)
	default String customDict()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ignorePunctuation",
		name = "Ignore punctuation",
		description = "Accept contractions typed without apostrophes (dont, youre, isnt) by matching against the apostrophe-stripped dictionary",
		section = dictionarySection,
		position = 33
	)
	default boolean ignorePunctuation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "addWordHotkey",
		name = "Add to dictionary",
		description = "Press to add the last flagged word in your buffer to your personal dictionary",
		section = dictionarySection,
		position = 34
	)
	default Keybind addWordHotkey()
	{
		return new Keybind(KeyEvent.VK_INSERT, 0);
	}

	// --- Advanced --------------------------------------------------------

	@ConfigItem(
		keyName = "underlineColor",
		name = "Underline color",
		description = "Color of the squiggle beneath misspelled words",
		section = advancedSection,
		position = 41
	)
	default Color underlineColor()
	{
		return new Color(220, 60, 60);
	}

	@ConfigItem(
		keyName = "logFlagged",
		name = "Log flagged words",
		description = "Print flagged tokens to the RuneLite log (useful while tuning your dictionary)",
		section = advancedSection,
		position = 42
	)
	default boolean logFlagged()
	{
		return false;
	}
}
