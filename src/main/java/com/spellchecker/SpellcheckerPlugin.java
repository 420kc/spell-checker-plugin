package com.spellchecker;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Spellchecker",
	description = "Flag misspelled words as you type in chat, with a personal dictionary you grow over time",
	tags = {"chat", "spell", "spelling", "typo", "dictionary"}
)
public class SpellcheckerPlugin extends Plugin implements KeyListener
{
	private static final Pattern TOKEN_PATTERN = Pattern.compile("[A-Za-z']+");

	// Buffer-level patterns underlined green when the sibling plugin is loaded.
	// Ordered longest-first so overlap dedupe keeps the outer match.
	private static final Pattern[] SIBLING_PATTERNS = {
		Pattern.compile("blaze it!"),
		Pattern.compile("\\b420 kc\\b"),
		Pattern.compile("\\b420kc\\b"),
		Pattern.compile("\\b420\\b"),
	};

	@Inject private Client client;
	@Inject private SpellcheckerConfig config;
	@Inject private ConfigManager configManager;
	@Inject private SpellcheckerService service;
	@Inject private OverlayManager overlayManager;
	@Inject private KeyManager keyManager;
	@Inject private SpellcheckerOverlay overlay;
	@Inject private PluginManager pluginManager;
	@Inject private GrammarChecker grammarChecker;
	@Inject private AutoCorrector autoCorrector;
	@Inject private ClientThread clientThread;

	@Getter
	private final List<FlaggedToken> flagged = new ArrayList<>();

	@Getter
	private final List<int[]> greenRanges = new ArrayList<>();

	@Getter
	private final List<GrammarHit> grammarHits = new ArrayList<>();

	@Getter
	private volatile boolean siblingPluginLoaded;

	@Override
	protected void startUp()
	{
		service.load();
		service.setCustomDict(config.customDict());
		service.setIgnorePunctuation(config.ignorePunctuation());
		grammarChecker.setUserPhrases(config.grammarPhrases());
		autoCorrector.setEnabled(config.autoCorrect());
		autoCorrector.setList(config.autoCorrectList());
		overlayManager.add(overlay);
		keyManager.registerKeyListener(this);
		refreshSiblingPlugin();
		log.debug("Spellchecker started");
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		keyManager.unregisterKeyListener(this);
		flagged.clear();
		log.debug("Spellchecker stopped");
	}

	@Provides
	SpellcheckerConfig provideConfig(ConfigManager mgr)
	{
		return mgr.getConfig(SpellcheckerConfig.class);
	}

	@Subscribe
	public void onVarClientStrChanged(VarClientStrChanged e)
	{
		if (e.getIndex() != VarClientStr.CHATBOX_TYPED_TEXT)
		{
			return;
		}
		if (config.enabled() && tryAutoCorrect())
		{
			// The rewrite fires another VarClientStrChanged, which re-runs this
			// handler and re-checks the corrected text - so we stop here.
			return;
		}
		recheck();
	}

	/**
	 * Apply an auto-correction to the typed buffer if one is due. Returns true if
	 * the buffer was rewritten (and a redraw scheduled), false otherwise.
	 */
	private boolean tryAutoCorrect()
	{
		String buf = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (buf == null || buf.isEmpty()
			|| buf.startsWith("::") || buf.startsWith(";;") || buf.startsWith("/") || buf.startsWith("~"))
		{
			return false;
		}
		String corrected = autoCorrector.apply(buf);
		if (corrected == null || corrected.equals(buf))
		{
			return false;
		}
		setTypedText(corrected);
		return true;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!SpellcheckerConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		switch (e.getKey())
		{
			case "customDict":
				service.setCustomDict(config.customDict());
				recheck();
				break;
			case "ignorePunctuation":
				service.setIgnorePunctuation(config.ignorePunctuation());
				recheck();
				break;
			case "grammarPhrases":
				grammarChecker.setUserPhrases(config.grammarPhrases());
				recheck();
				break;
			case "autoCorrect":
				autoCorrector.setEnabled(config.autoCorrect());
				break;
			case "autoCorrectList":
				autoCorrector.setList(config.autoCorrectList());
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged e)
	{
		// Only react when the sibling plugin itself toggles - every other plugin
		// in the system doesn't affect us.
		String name = e.getPlugin().getClass().getSimpleName();
		if (!"Rank1Plugin".equals(name) && !"FourTwentyKcPlugin".equals(name))
		{
			return;
		}
		boolean was = siblingPluginLoaded;
		refreshSiblingPlugin();
		if (was != siblingPluginLoaded)
		{
			recheck();
		}
	}

	private void refreshSiblingPlugin()
	{
		siblingPluginLoaded = pluginManager.getPlugins().stream().anyMatch(p ->
		{
			String n = p.getClass().getSimpleName();
			return "Rank1Plugin".equals(n) || "FourTwentyKcPlugin".equals(n);
		});
	}

	private void recheck()
	{
		flagged.clear();
		greenRanges.clear();
		grammarHits.clear();
		if (!config.enabled())
		{
			return;
		}

		String buf = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (buf == null || buf.isEmpty())
		{
			return;
		}
		// Skip command lines so ::dz, ;;ge, /p, ~clan don't get flagged.
		if (buf.startsWith("::") || buf.startsWith(";;") || buf.startsWith("/") || buf.startsWith("~"))
		{
			return;
		}

		// Three layers, highest-precedence first. Each later layer skips ranges
		// already painted by an earlier one, so we never stack underlines.
		//   1. green (sibling-plugin brand mark)
		//   2. blue  (grammar)
		//   3. red   (spelling)
		String lower = buf.toLowerCase();

		if (siblingPluginLoaded)
		{
			greenRanges.addAll(scanSiblingRanges(lower));
		}

		// Grammar hits are single, non-overlapping tokens - only filter against green.
		for (GrammarHit h : grammarChecker.check(buf, config.grammarMode()))
		{
			if (!overlapsAny(h.getStart(), h.getEnd(), greenRanges))
			{
				grammarHits.add(h);
			}
		}

		Matcher m = TOKEN_PATTERN.matcher(buf);
		while (m.find())
		{
			String tok = m.group();
			if (tok.length() < config.minLength())
			{
				continue;
			}
			if (service.isCorrect(tok))
			{
				continue;
			}
			if (overlapsAny(m.start(), m.end(), greenRanges)
				|| overlapsGrammar(m.start(), m.end()))
			{
				continue;
			}
			flagged.add(new FlaggedToken(tok, m.start(), m.end()));
		}

		if (config.logFlagged() && !flagged.isEmpty())
		{
			log.info("flagged: {}", flagged);
		}
	}

	private static boolean overlapsAny(int start, int end, List<int[]> ranges)
	{
		for (int[] g : ranges)
		{
			if (start < g[1] && end > g[0])
			{
				return true;
			}
		}
		return false;
	}

	private boolean overlapsGrammar(int start, int end)
	{
		for (GrammarHit h : grammarHits)
		{
			if (start < h.getEnd() && end > h.getStart())
			{
				return true;
			}
		}
		return false;
	}

	private static List<int[]> scanSiblingRanges(String buf)
	{
		String t = buf.toLowerCase();
		List<int[]> all = new ArrayList<>();
		for (Pattern p : SIBLING_PATTERNS)
		{
			Matcher m = p.matcher(t);
			while (m.find())
			{
				all.add(new int[]{m.start(), m.end()});
			}
		}
		if (all.isEmpty())
		{
			return Collections.emptyList();
		}
		// Drop matches fully contained inside an earlier longer match
		// (e.g. "420" inside "420 kc" - only the outer span keeps the underline).
		all.sort(Comparator.<int[]>comparingInt(a -> a[0]).thenComparing(a -> -a[1]));
		List<int[]> out = new ArrayList<>();
		int lastEnd = -1;
		for (int[] r : all)
		{
			if (r[1] <= lastEnd)
			{
				continue;
			}
			out.add(r);
			lastEnd = r[1];
		}
		return out;
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!config.enabled() || (flagged.isEmpty() && grammarHits.isEmpty()))
		{
			return;
		}
		// Only act if the right-click landed inside the chatbox input widget bounds.
		// The "Cancel"-only menu has no entry pointing at INPUT, so a widget-id check
		// on event.getMenuEntries() misses it.
		Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
		Point mouse = client.getMouseCanvasPosition();
		if (input == null || input.isHidden() || mouse == null)
		{
			return;
		}
		Point loc = input.getCanvasLocation();
		if (loc == null)
		{
			return;
		}
		int mx = mouse.getX();
		int my = mouse.getY();
		if (mx < loc.getX() || mx > loc.getX() + input.getWidth()
			|| my < loc.getY() || my > loc.getY() + input.getHeight())
		{
			return;
		}

		// Grammar hit takes precedence over a spelling flag if they coexist
		// (they shouldn't - recheck enforces non-overlap - but be safe).
		GrammarHit hit = findGrammarHitAtMouse();
		if (hit != null)
		{
			final GrammarHit h = hit;
			client.createMenuEntry(-1)
				.setOption("Replace with")
				.setTarget("<col=4682e6>" + h.getSuggestion() + "</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(me -> replaceRange(h.getStart(), h.getEnd(), h.getMatched(), h.getSuggestion()));
			return;
		}

		FlaggedToken target = findTokenAtMouse();
		if (target == null)
		{
			return;
		}
		final String word = target.getText();
		final FlaggedToken tk = target;

		// "Add to dictionary" entry sits below the suggestions.
		client.createMenuEntry(-1)
			.setOption("Add to dictionary")
			.setTarget("<col=ffff00>" + word + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(me -> addToDict(word));

		// Suggestions: insert in reverse so the top-ranked one ends up at the top.
		List<String> suggestions = service.suggest(word, 3);
		for (int i = suggestions.size() - 1; i >= 0; i--)
		{
			final String s = suggestions.get(i);
			client.createMenuEntry(-1)
				.setOption("Replace with")
				.setTarget("<col=00ff00>" + s + "</col>")
				.setType(MenuAction.RUNELITE)
				.onClick(me -> replaceToken(tk, s));
		}
	}

	private FlaggedToken findTokenAtMouse()
	{
		HitGeometry geom = hitGeometry();
		if (geom == null)
		{
			return null;
		}
		for (FlaggedToken t : flagged)
		{
			if (geom.contains(t.getStart(), t.getEnd()))
			{
				return t;
			}
		}
		return null;
	}

	private GrammarHit findGrammarHitAtMouse()
	{
		HitGeometry geom = hitGeometry();
		if (geom == null)
		{
			return null;
		}
		for (GrammarHit h : grammarHits)
		{
			if (geom.contains(h.getStart(), h.getEnd()))
			{
				return h;
			}
		}
		return null;
	}

	/**
	 * Captures the geometry needed to map the mouse canvas position to a
	 * character range in the typed buffer. Returns null if any link in the
	 * chain (widget, font, mouse) is unavailable or the mouse is outside the
	 * typed portion.
	 */
	private HitGeometry hitGeometry()
	{
		Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
		if (input == null)
		{
			return null;
		}
		String typed = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (typed == null || typed.isEmpty())
		{
			return null;
		}
		String widgetText = input.getText();
		int prefixEnd = widgetText == null ? -1 : widgetText.lastIndexOf(": ");
		if (prefixEnd < 0)
		{
			return null;
		}
		prefixEnd += 2;
		FontTypeFace font = input.getFont();
		Point loc = input.getCanvasLocation();
		Point mouse = client.getMouseCanvasPosition();
		if (font == null || loc == null || mouse == null)
		{
			return null;
		}
		int prefixWidth = font.getTextWidth(widgetText.substring(0, prefixEnd));
		int relX = mouse.getX() - loc.getX() - prefixWidth;
		if (relX < 0)
		{
			return null;
		}
		return new HitGeometry(typed, font, relX);
	}

	private static final class HitGeometry
	{
		private final String typed;
		private final FontTypeFace font;
		private final int relX;

		HitGeometry(String typed, FontTypeFace font, int relX)
		{
			this.typed = typed;
			this.font = font;
			this.relX = relX;
		}

		boolean contains(int start, int end)
		{
			int xStart = font.getTextWidth(typed.substring(0, start));
			int xEnd = font.getTextWidth(typed.substring(0, end));
			// 2px slack so edge clicks count.
			return relX >= xStart - 2 && relX <= xEnd + 2;
		}
	}

	private void replaceToken(FlaggedToken token, String replacement)
	{
		replaceRange(token.getStart(), token.getEnd(), token.getText(), replacement);
	}

	private void replaceRange(int start, int end, String expected, String replacement)
	{
		String buf = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (buf == null || end > buf.length())
		{
			return;
		}
		// Race-condition guard: range might have shifted if the user kept typing.
		String found = buf.substring(start, end);
		if (!found.equalsIgnoreCase(expected))
		{
			return;
		}
		String updated = buf.substring(0, start) + replacement + buf.substring(end);
		setTypedText(updated);
		log.debug("replaced '{}' with '{}'", expected, replacement);
	}

	/**
	 * Write {@code text} into the chatbox input and force it to repaint now.
	 * Setting the varc alone updates the value but not the on-screen line - the
	 * old behaviour needed a stray keystroke (spacebar) to refresh. Running
	 * CHAT_TEXT_INPUT_REBUILD rebuilds the input widget from the varc immediately,
	 * so the change is visible the instant it happens.
	 */
	private void setTypedText(String text)
	{
		clientThread.invokeLater(() ->
		{
			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, text);
			client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
		});
	}

	private void addToDict(String word)
	{
		String addition = word.toLowerCase();
		String csv = config.customDict();
		if (csv == null)
		{
			csv = "";
		}
		// Skip if already present (case-insensitive).
		for (String w : csv.split(","))
		{
			if (w.trim().equalsIgnoreCase(addition))
			{
				return;
			}
		}
		String updated = csv.isEmpty() ? addition : csv + "," + addition;
		configManager.setConfiguration(SpellcheckerConfig.GROUP, "customDict", updated);
		log.debug("added '{}' to personal dictionary", addition);
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (!config.addWordHotkey().matches(e))
		{
			return;
		}
		// flagged is mutated on the client thread (recheck); read it there too so a
		// concurrent clear can't slip between the empty-check and the access.
		clientThread.invoke(() ->
		{
			if (!flagged.isEmpty())
			{
				addToDict(flagged.get(flagged.size() - 1).getText());
			}
		});
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	@Value
	public static class FlaggedToken
	{
		String text;
		int start;
		int end;
	}
}
