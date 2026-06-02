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
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
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
		if (e.getIndex() != VarClientID.CHATINPUT)
		{
			return;
		}
		recheck();
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

		String buf = client.getVarcStrValue(VarClientID.CHATINPUT);
		if (buf == null || buf.isEmpty() || isCommandLine(buf))
		{
			return;
		}

		// Three layers, highest-precedence first. Each later layer skips ranges
		// already painted by an earlier one, so we never stack underlines.
		//   1. green (sibling-plugin brand mark)
		//   2. blue  (grammar)
		//   3. red   (spelling)
		if (siblingPluginLoaded)
		{
			greenRanges.addAll(scanSiblingRanges(buf));
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
			// Don't flag the word still under the caret. A token reaching the end of
			// the buffer has no boundary after it, so it's mid-type; like every
			// standard spellchecker, we only judge it once a space/punctuation
			// finishes it.
			if (m.end() == buf.length())
			{
				continue;
			}
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
			log.debug("flagged: {}", flagged);
		}
	}

	// Command lines (::dz, ;;ge, /p, ~clan) pass straight through, never flagged.
	private static boolean isCommandLine(String buf)
	{
		return buf.startsWith("::") || buf.startsWith(";;")
			|| buf.startsWith("/") || buf.startsWith("~");
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
		if (!config.enabled() || flagged.isEmpty())
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

		FlaggedToken target = findTokenAtMouse();
		if (target == null)
		{
			return;
		}
		final String word = target.getText();

		client.getMenu().createMenuEntry(-1)
			.setOption("Add to dictionary")
			.setTarget("<col=ffff00>" + word + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(me -> addToDict(word));
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
		String typed = client.getVarcStrValue(VarClientID.CHATINPUT);
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
