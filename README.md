# Spellchecker

Flags misspelled words as you type in the chatbox, fixes obvious typos
automatically, and gently underlines common grammar slips, with a personal
dictionary you grow over time. Nothing is ever sent anywhere. It all runs
locally against a bundled word list.

## What the underlines mean

| Color | Meaning | Action |
|-------|---------|--------|
| Red squiggle | Misspelled word | Right-click, pick a suggestion, or **Add to dictionary** |
| Blue squiggle | Grammar / word-use slip (their/there/they're, your/you're, should of) | Right-click, **Replace with** |

Corrections apply instantly when you click the menu entry. No spacebar needed.

## Fixing a flagged word

Right-click the underlined word inside the chat input. You'll get:

- **Replace with ...** swaps in the suggestion immediately.
- **Add to dictionary** (red/spelling only) marks the word correct forever.

You can also press the **Add to dictionary** hotkey (default `Insert`) to add the
last flagged word in your current line without right-clicking.

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

## Auto-correct (replace-as-you-type)

Config, then **Auto-correct**. When **Auto-correct typos** is on, finishing a word
(typing a space after it) replaces obvious misspellings in place. The list is
fully editable, one `wrong=>right` pair per comma:

```
alot=>a lot, teh=>the, recieve=>receive, thier=>their
```

- **Add** a correction: append `mistake=>fix`.
- **Remove** a default: delete its entry.

Only completed words are touched. The word your cursor is still inside is left
alone, so it never fights you mid-type.

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
| Auto-correct typos | on | Replace-as-you-type |
| Corrections | (seeded) | Editable `wrong=>right` list |
| Min word length | 4 | Ignore shorter tokens (ty, gz, kk) |
| Personal dictionary | (empty) | Your always-correct words |
| Ignore punctuation | on | Accept `dont`, `youre`, `isnt` without apostrophes |
| Add to dictionary | `Insert` | Hotkey to whitelist the last flagged word |
| Underline color | red | Color of the spelling squiggle |
| Log flagged words | off | Print flagged tokens to the RuneLite log |
