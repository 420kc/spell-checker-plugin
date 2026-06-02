package com.spellchecker;

import lombok.Value;

/**
 * A grammar/usage mistake found in the typed buffer: the span [start, end) that
 * is underlined, plus the cleaner wording we keep for future display surfaces.
 */
@Value
public class GrammarHit
{
	String matched;
	String suggestion;
	int start;
	int end;
}
