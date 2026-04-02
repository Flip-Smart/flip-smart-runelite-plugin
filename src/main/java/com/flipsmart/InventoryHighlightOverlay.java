package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ItemComposition;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Overlay that draws a pulsing orange glow around the outline of inventory
 * items that have pending sell adjustment recommendations.
 */
@Slf4j
public class InventoryHighlightOverlay extends WidgetItemOverlay
{
	private static final Color COLOR_GLOW = new Color(255, 185, 50);

	private final Set<Integer> highlightedItemIds = ConcurrentHashMap.newKeySet();

	// Cache outline images to avoid regenerating every frame
	private final Map<Integer, BufferedImage> outlineCache = new ConcurrentHashMap<>();

	@Inject
	private ItemManager itemManager;

	public InventoryHighlightOverlay()
	{
		showOnInventory();
	}

	public void addHighlight(int itemId)
	{
		highlightedItemIds.add(itemId);
	}

	public void removeHighlight(int itemId)
	{
		highlightedItemIds.remove(itemId);
		outlineCache.remove(itemId);
	}

	public void clearAll()
	{
		highlightedItemIds.clear();
		outlineCache.clear();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!highlightedItemIds.contains(itemId) && !highlightedItemIds.contains(getUnnotedId(itemId)))
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null || bounds.width <= 0)
		{
			return;
		}

		BufferedImage outline = outlineCache.computeIfAbsent(itemId, this::generateOutline);
		if (outline == null)
		{
			return;
		}

		long elapsed = System.currentTimeMillis() % 1500;
		float pulseAlpha = (float) (0.5 + 0.5 * Math.sin(elapsed / 1500.0 * 2 * Math.PI));

		// Clip out the top-left corner where quantity text is rendered
		// so the glow doesn't obscure it
		Shape originalClip = graphics.getClip();
		java.awt.geom.Area clipArea = new java.awt.geom.Area(new Rectangle(
			bounds.x - 10, bounds.y - 10,
			bounds.width + 20, bounds.height + 20));
		clipArea.subtract(new java.awt.geom.Area(new Rectangle(
			bounds.x, bounds.y, bounds.width / 2, bounds.height / 3)));
		graphics.setClip(clipArea);

		// Draw expanded glow layers for outer glow effect
		Composite originalComposite = graphics.getComposite();
		for (int i = 2; i >= 1; i--)
		{
			float alpha = (0.3f + 0.2f * pulseAlpha) / i;
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(alpha, 1.0f)));
			graphics.drawImage(outline,
				bounds.x - i,
				bounds.y - i,
				bounds.width + i * 2,
				bounds.height + i * 2,
				null);
		}

		// Draw the crisp inner outline
		float innerAlpha = 0.7f + 0.3f * pulseAlpha;
		graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, innerAlpha));
		graphics.drawImage(outline, bounds.x, bounds.y, bounds.width, bounds.height, null);

		graphics.setComposite(originalComposite);
		graphics.setClip(originalClip);
	}

	/**
	 * Generate an outline image from the item sprite. The outline consists of
	 * pixels that are transparent but adjacent to a non-transparent pixel.
	 */
	private BufferedImage generateOutline(int itemId)
	{
		BufferedImage itemImage = itemManager.getImage(itemId);
		if (itemImage == null)
		{
			return null;
		}

		int w = itemImage.getWidth();
		int h = itemImage.getHeight();
		BufferedImage outline = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

		int glowRgb = COLOR_GLOW.getRGB() & 0x00FFFFFF; // strip alpha

		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < w; x++)
			{
				// Only draw outline on transparent pixels adjacent to opaque ones
				if ((itemImage.getRGB(x, y) >>> 24) != 0)
				{
					continue;
				}

				if (hasOpaqueNeighbor(itemImage, x, y, w, h))
				{
					outline.setRGB(x, y, 0xFF000000 | glowRgb);
				}
			}
		}

		return outline;
	}

	private boolean hasOpaqueNeighbor(BufferedImage image, int x, int y, int w, int h)
	{
		for (int dy = -1; dy <= 1; dy++)
		{
			for (int dx = -1; dx <= 1; dx++)
			{
				if (dx == 0 && dy == 0)
				{
					continue;
				}
				int nx = x + dx;
				int ny = y + dy;
				if (nx >= 0 && nx < w && ny >= 0 && ny < h
					&& (image.getRGB(nx, ny) >>> 24) != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Get the unnoted (base) item ID for a potentially noted item.
	 * If the item is already unnoted, returns the item ID unchanged.
	 * This is called on the render thread where ItemComposition access is safe.
	 */
	private int getUnnotedId(int itemId)
	{
		try
		{
			ItemComposition comp = itemManager.getItemComposition(itemId);
			// getNote() == 799 means this IS a noted item; getLinkedNoteId() returns the unnoted version
			if (comp.getNote() == 799)
			{
				return comp.getLinkedNoteId();
			}
		}
		catch (Exception e)
		{
			// Fall through
		}
		return itemId;
	}
}
