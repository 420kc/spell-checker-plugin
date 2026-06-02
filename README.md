# Spellchecker

Flags misspelled words as you type in the chatbox and gently underlines common
grammar slips, with a personal dictionary you grow over time. Nothing is ever
sent anywhere. It all runs locally against a bundled word list.

## What the underlines mean

| Color | Meaning | Action |
|-------|---------|--------|
| Red squiggle | Misspelled word | Right-click to see suggestions, or **Add to dictionary** |
| Blue squiggle | Grammar / word-use slip (their/there/they're, your/you're, should of) | Right-click to see the suggestion |

## Fixing a flagged word

Right-click the underlined word inside the chat input. You'll get:

- **Suggestion** entries show likely corrections without changing your text.
- **Add to dictionary** marks a red-underlined word correct forever.

You can also press the **Add to dictionary** hotkey (default `Insert`) to add the
last flagged word in your current line without right-clicking.

Suggestions are hints only. Spellchecker never rewrites your chat input or
changes an outgoing message.

## Adding / removing dictionary words

Open the plugin config, then **Dictionary > Personal dictionary**. It's a plain
comma-separated list:

```
zezima, claws, voidwaker, mybestfriendsrsn
```

- **Add** a word by appending it (or use the right-click / hotkey above, which
  edits this list for you).
- **Remove** a word by deleting it from the list.

Words are matched case-insensitively, and common suffixes are handled
automatically. Adding `splash` also accepts `splashing`, `splashed`, `splashes`.

## Grammar

Config, then **Grammar > Grammar checking**:

- **Off**: no blue underlines.
- **Safe**: only unambiguous slips (`should of` becomes `should have`, `better then`
  becomes `better than`) plus your own phrases.
- **Aggressive**: also context-aware homophones: your/you're, their/there/they're,
  its/it's, to/too. These fire only when the surrounding words make the mistake
  certain, so correct text stays clean.

Add your own blue-underlined phrases under **Grammar > Extra phrases**, same
`wrong=>right` format:

```
should of=>should have, could care less=>couldn't care less
```

## Config reference

| Setting | Default | Notes |
|---------|---------|-------|
| Enable | on | Master switch |
| Grammar checking | Aggressive | Off / Safe / Aggressive |
| Extra phrases | (empty) | Your own `wrong=>right` grammar phrases |
| Min word length | 4 | Ignore shorter tokens (ty, gz, kk) |
| Personal dictionary | (empty) | Your always-correct words |
| Ignore punctuation | on | Accept `dont`, `youre`, `isnt` without apostrophes |
| Add to dictionary | `Insert` | Hotkey to whitelist the last flagged word |
| Underline color | red | Color of the spelling squiggle |
| Log flagged words | off | Print flagged tokens to the RuneLite log |
