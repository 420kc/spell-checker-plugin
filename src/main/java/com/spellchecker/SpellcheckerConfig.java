package com.spellchecker;

import java.awt.Color;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Keybind;

@ConfigGroup(SpellcheckerConfig.GROUP)
public interface SpellcheckerConfig extends Config
{
	String GROUP = "spellchecker";

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

	@ConfigItem(
		keyName = "minLength",
		name = "Min word length",
		description = "Skip tokens shorter than this (keeps ty / gz / kk from lighting up)",
		position = 1
	)
	default int minLength()
	{
		return 4;
	}

	@ConfigItem(
		keyName = "customDict",
		name = "Personal dictionary",
		description = "Comma-separated words to treat as correct (RSNs, OSRS terms, friend names)",
		position = 2
	)
	default String customDict()
	{
		return "";
	}

	@ConfigItem(
		keyName = "underlineColor",
		name = "Underline color",
		description = "Color of the squiggle beneath misspelled words",
		position = 3
	)
	default Color underlineColor()
	{
		return new Color(220, 60, 60);
	}

	@ConfigItem(
		keyName = "addWordHotkey",
		name = "Add to dictionary",
		description = "Press to add the last flagged word in your buffer to your personal dictionary",
		position = 4
	)
	default Keybind addWordHotkey()
	{
		return new Keybind(KeyEvent.VK_INSERT, 0);
	}

	@ConfigItem(
		keyName = "ignorePunctuation",
		name = "Ignore punctuation",
		description = "Accept contractions typed without apostrophes (dont, youre, isnt) by matching against the apostrophe-stripped dictionary",
		position = 5
	)
	default boolean ignorePunctuation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "logFlagged",
		name = "Log flagged words",
		description = "Print flagged tokens to the RuneLite log (useful while tuning your dictionary)",
		position = 6
	)
	default boolean logFlagged()
	{
		return false;
	}
}
