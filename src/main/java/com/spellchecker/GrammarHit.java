package com.spellchecker;

import lombok.Value;

/**
 * A grammar/usage mistake found in the typed buffer: the span [start, end) that
 * should be replaced by {@code suggestion}. {@code matched} is the original text
 * (used as a race-guard before rewriting the buffer).
 */
@Value
public class GrammarHit
{
	String matched;
	String suggestion;
	int start;
	int end;
}
