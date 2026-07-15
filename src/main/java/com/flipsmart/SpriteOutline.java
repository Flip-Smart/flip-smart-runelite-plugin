package com.flipsmart;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Builds a thin edge-outline sprite for one GE slot border piece.
 *
 * <p>The frame pieces overhang the visible slot by a few px (their outer edge sticks out past the
 * slot bounds), so the slot's actual border does not sit at the sprite's own edge. Each line is
 * therefore positioned at the slot border expressed in the piece's local coordinates —
 * {@code -relX}/{@code -relY} for the top/left, {@code slot - rel - thickness} for the bottom/right —
 * and the {@link Graphics2D} clip drops any part that falls outside the piece. Corners (which are
 * aligned to the slot) resolve to their own edges under the same formula.
 */
public final class SpriteOutline
{
    public static final int TOP = 1;
    public static final int BOTTOM = 2;
    public static final int LEFT = 4;
    public static final int RIGHT = 8;

    private SpriteOutline()
    {
    }

    public static BufferedImage build(int width, int height, int edges, Color color, int thickness,
                                      int relX, int relY, int slotWidth, int slotHeight)
    {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int t = Math.max(1, thickness);
        Graphics2D g = out.createGraphics();
        g.setColor(color);
        if ((edges & TOP) != 0)
        {
            g.fillRect(0, -relY, width, t);
        }
        if ((edges & BOTTOM) != 0)
        {
            g.fillRect(0, slotHeight - relY - t, width, t);
        }
        if ((edges & LEFT) != 0)
        {
            g.fillRect(-relX, 0, t, height);
        }
        if ((edges & RIGHT) != 0)
        {
            g.fillRect(slotWidth - relX - t, 0, t, height);
        }
        g.dispose();
        return out;
    }
}
