package com.spellchecker;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.Point;
import net.runelite.api.VarClientStr;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Draws red squiggles beneath flagged tokens in the typed chat buffer.
 *
 * KNOWN FRAGILE SPOT: finding the chatbox input widget. The gameval id for that child
 * has shifted across RuneLite versions, so we scan CHATBOX children for the one whose
 * text contains the current typed buffer. If we can't find it (e.g. login screen, bank
 * pin pad open, gameval changes again), we silently no-op and the plugin still flags
 * via logs.
 */
class SpellcheckerOverlay extends Overlay
{
	private final Client client;
	private final SpellcheckerPlugin plugin;
	private final SpellcheckerConfig config;

	@Inject
	SpellcheckerOverlay(Client client, SpellcheckerPlugin plugin, SpellcheckerConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.enabled() || plugin.getFlagged().isEmpty())
		{
			return null;
		}

		String typed = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT);
		if (typed == null || typed.isEmpty())
		{
			return null;
		}

		Widget input = findInputWidget(typed);
		if (input == null || input.isHidden())
		{
			return null;
		}

		FontTypeFace font = input.getFont();
		if (font == null)
		{
			return null;
		}

		// Widget shows something like "Username: <typed>*" — find where the typed part starts.
		String widgetText = input.getText();
		int typedStart = widgetText == null ? -1 : widgetText.indexOf(typed);
		if (typedStart < 0)
		{
			return null;
		}
		String prefix = widgetText.substring(0, typedStart);
		int prefixWidth = font.getTextWidth(prefix);

		Point loc = input.getCanvasLocation();
		if (loc == null)
		{
			return null;
		}
		int baseX = loc.getX() + prefixWidth;
		int baseY = loc.getY() + input.getHeight() - 2;

		g.setColor(config.underlineColor());
		g.setStroke(new BasicStroke(1f));

		for (SpellcheckerPlugin.FlaggedToken t : plugin.getFlagged())
		{
			int x1 = baseX + font.getTextWidth(typed.substring(0, t.getStart()));
			int x2 = baseX + font.getTextWidth(typed.substring(0, t.getEnd()));
			drawSquiggle(g, x1, x2, baseY);
		}
		return null;
	}

	private Widget findInputWidget(String typed)
	{
		// Direct packed-id lookup: InterfaceID.Chatbox.INPUT = (162 << 16) | 57.
		Widget input = client.getWidget(InterfaceID.Chatbox.INPUT);
		if (input != null && !input.isHidden())
		{
			return input;
		}
		// Fallback: scan CHATBOX children if the gameval ever moves again.
		Widget chatbox = client.getWidget(InterfaceID.CHATBOX, 0);
		if (chatbox == null)
		{
			return null;
		}
		Widget hit = scanChildren(chatbox.getDynamicChildren(), typed);
		if (hit != null)
		{
			return hit;
		}
		return scanChildren(chatbox.getStaticChildren(), typed);
	}

	private Widget scanChildren(Widget[] kids, String typed)
	{
		if (kids == null)
		{
			return null;
		}
		for (Widget w : kids)
		{
			if (w == null || w.isHidden())
			{
				continue;
			}
			String txt = w.getText();
			if (txt != null && txt.contains(typed))
			{
				return w;
			}
		}
		return null;
	}

	private void drawSquiggle(Graphics2D g, int x1, int x2, int y)
	{
		Path2D.Float p = new Path2D.Float();
		p.moveTo(x1, y);
		boolean up = true;
		for (int x = x1 + 2; x <= x2; x += 2)
		{
			p.lineTo(x, y + (up ? -1 : 1));
			up = !up;
		}
		g.draw(p);
	}
}
