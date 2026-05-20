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
import net.runelite.api.VarClientStr;
import net.runelite.api.events.VarClientStrChanged;
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
		FlaggedToken last = flagged.get(flagged.size() - 1);
		String addition = last.getText().toLowerCase();

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
