package com.spellchecker;

/**
 * Grammar-check aggression. Surfaced in config so the user dials it.
 *   OFF        - no grammar underlines at all.
 *   SAFE       - only unambiguous errors ("should of", comparative + "then") plus user phrases.
 *   AGGRESSIVE - the above plus context-aware homophones (your/you're, etc.).
 */
public enum GrammarMode
{
	OFF("Off"),
	SAFE("Safe"),
	AGGRESSIVE("Aggressive");

	private final String label;

	GrammarMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
