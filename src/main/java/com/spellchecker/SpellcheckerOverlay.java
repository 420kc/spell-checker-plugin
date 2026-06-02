package com.spellchecker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.FontTypeFace;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws the underlines beneath the typed chat buffer: red squiggles for spelling,
 * blue squiggles for grammar, and a solid green mark for sibling-plugin phrases.
 *
 * KNOWN FRAGILE SPOT: finding the chatbox input widget. The gameval id for that child
 * has shifted across RuneLite versions, so we scan CHATBOX children for the one whose
 * text contains the current typed buffer. If we can't find it (e.g. login screen, bank
 * pin pad open, gameval changes again), we silently no-op.
 */
class SpellcheckerOverlay extends Overlay
{
	private static final Color SIBLING_GREEN = new Color(30, 200, 30);
	private static final Color GRAMMAR_BLUE = new Color(30, 60, 170);

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
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.enabled())
		{
			return null;
		}

		List<SpellcheckerPlugin.FlaggedToken> red = plugin.getFlagged();
		List<int[]> green = plugin.getGreenRanges();
		List<GrammarHit> blue = plugin.getGrammarHits();
		if (red.isEmpty() && green.isEmpty() && blue.isEmpty())
		{
			return null;
		}

		String typed = client.getVarcStrValue(VarClientID.CHATINPUT);
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
		Point loc = input.getCanvasLocation();
		if (font == null || loc == null)
		{
			return null;
		}

		// Widget format is "RSN: <typed>*". Anchor on the colon-space separator
		// rather than searching for `typed` inside widgetText - an RSN that
		// contains the typed string (e.g. "420 kc" while typing "420") used to
		// shadow the input.
		String widgetText = input.getText();
		int prefixEnd = widgetText == null ? -1 : widgetText.lastIndexOf(": ");
		if (prefixEnd < 0)
		{
			return null;
		}
		prefixEnd += 2;
		int prefixWidth = font.getTextWidth(widgetText.substring(0, prefixEnd));

		int baseX = loc.getX() + prefixWidth;
		int baseY = loc.getY() + input.getHeight() - 2;

		// Red squiggles for misspelled tokens.
		g.setColor(config.underlineColor());
		g.setStroke(new BasicStroke(1f));
		for (SpellcheckerPlugin.FlaggedToken t : red)
		{
			int x1 = baseX + font.getTextWidth(typed.substring(0, t.getStart()));
			int x2 = baseX + font.getTextWidth(typed.substring(0, t.getEnd()));
			drawSquiggle(g, x1, x2, baseY);
		}

		// Blue grammar squiggle, same wave as the red one but dark blue, so it
		// reads like Word's grammar underline rather than a row of dots.
		if (!blue.isEmpty())
		{
			g.setColor(GRAMMAR_BLUE);
			g.setStroke(new BasicStroke(1f));
			for (GrammarHit h : blue)
			{
				int x1 = baseX + font.getTextWidth(typed.substring(0, h.getStart()));
				int x2 = baseX + font.getTextWidth(typed.substring(0, h.getEnd()));
				drawSquiggle(g, x1, x2, baseY);
			}
		}

		// Sibling-plugin green underline - solid so it reads as a "feature" rather
		// than an "error".
		if (!green.isEmpty())
		{
			g.setColor(SIBLING_GREEN);
			g.setStroke(new BasicStroke(2f));
			for (int[] r : green)
			{
				int x1 = baseX + font.getTextWidth(typed.substring(0, r[0]));
				int x2 = baseX + font.getTextWidth(typed.substring(0, r[1]));
				g.drawLine(x1, baseY, x2, baseY);
			}
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
