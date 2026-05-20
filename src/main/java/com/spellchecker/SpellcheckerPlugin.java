package com.spellchecker;

import com.google.inject.Provides;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
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
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.VarClientStrChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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

	@Inject private Client client;
	@Inject private SpellcheckerConfig config;
	@Inject private ConfigManager configManager;
	@Inject private SpellcheckerService service;
	@Inject private OverlayManager overlayManager;
	@Inject private KeyManager keyManager;
	@Inject private SpellcheckerOverlay overlay;

	@Getter
	private final List<FlaggedToken> flagged = new ArrayList<>();

	@Override
	protected void startUp()
	{
		service.load();
		service.setCustomDict(config.customDict());
		overlayManager.add(overlay);
		keyManager.registerKeyListener(this);
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
		recheck();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!SpellcheckerConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if ("customDict".equals(e.getKey()))
		{
			service.setCustomDict(config.customDict());
			recheck();
		}
	}

	private void recheck()
	{
		flagged.clear();
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
			flagged.add(new FlaggedToken(tok, m.start(), m.end()));
		}

		if (config.logFlagged() && !flagged.isEmpty())
		{
			log.info("flagged: {}", flagged);
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (!config.enabled() || flagged.isEmpty())
		{
			return;
		}
		// Only act if the menu was opened on the chatbox input widget.
		boolean onInput = false;
		for (MenuEntry me : event.getMenuEntries())
		{
			Widget w = me.getWidget();
			if (w != null && w.getId() == InterfaceID.Chatbox.INPUT)
			{
				onInput = true;
				break;
			}
		}
		if (!onInput)
		{
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
		if (widgetText == null)
		{
			return null;
		}
		int typedStart = widgetText.indexOf(typed);
		if (typedStart < 0)
		{
			return null;
		}
		FontTypeFace font = input.getFont();
		if (font == null)
		{
			return null;
		}
		Point loc = input.getCanvasLocation();
		Point mouse = client.getMouseCanvasPosition();
		if (loc == null || mouse == null)
		{
			return null;
		}
		int prefixWidth = font.getTextWidth(widgetText.substring(0, typedStart));
		int relX = mouse.getX() - loc.getX() - prefixWidth;
		if (relX < 0)
		{
			return null;
		}
		for (FlaggedToken t : flagged)
		{
			int xStart = font.getTextWidth(typed.substring(0, t.getStart()));
			int xEnd = font.getTextWidth(typed.substring(0, t.getEnd()));
			// 2px slack so the squiggle edges count.
			if (relX >= xStart - 2 && relX <= xEnd + 2)
			{
				return t;
			}
		}
		return null;
	}

	private void replaceToken(FlaggedToken token, String replacement)
	{
		String buf = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (buf == null || token.getEnd() > buf.length())
		{
			return;
		}
		// Race-condition guard: token might have shifted if the user kept typing.
		String found = buf.substring(token.getStart(), token.getEnd());
		if (!found.equalsIgnoreCase(token.getText()))
		{
			return;
		}
		String updated = buf.substring(0, token.getStart()) + replacement + buf.substring(token.getEnd());
		client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, updated);
		log.debug("replaced '{}' with '{}'", token.getText(), replacement);
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
		if (flagged.isEmpty())
		{
			return;
		}
		addToDict(flagged.get(flagged.size() - 1).getText());
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
