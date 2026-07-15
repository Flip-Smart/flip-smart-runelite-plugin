package com.flipsmart;

import org.junit.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import static org.junit.Assert.assertEquals;

public class SpriteOutlineTest
{
    private static final Color GREEN = new Color(0, 255, 0);
    private static final int OPAQUE = GREEN.getRGB();

    @Test
    public void topEdgePieceDrawsLineAtSlotBorderNotSpriteEdge()
    {
        // Mirrors the vanilla top edge piece: 83x32 overhanging 13px above the slot (relY=-13).
        BufferedImage out = SpriteOutline.build(83, 32, SpriteOutline.TOP, GREEN, 2, 16, -13, 115, 110);

        // The slot's top border sits at piece-local y = -relY = 13, not at the sprite's own top (y=0).
        assertEquals(OPAQUE, out.getRGB(40, 13));
        assertEquals("sprite top (above the slot) stays transparent", 0, out.getRGB(40, 0));
        assertEquals("below the border line stays transparent", 0, out.getRGB(40, 20));
    }

    @Test
    public void rightEdgePieceDrawsLineAtSlotRightBorder()
    {
        // Vanilla right edge piece: 32x78 at relX=95; slot right border = slotWidth - relX - t = 18.
        BufferedImage out = SpriteOutline.build(32, 78, SpriteOutline.RIGHT, GREEN, 2, 95, 16, 115, 110);

        assertEquals(OPAQUE, out.getRGB(18, 40));
        assertEquals("sprite right edge (past the slot) stays transparent", 0, out.getRGB(31, 40));
        assertEquals("sprite left stays transparent", 0, out.getRGB(0, 40));
    }

    @Test
    public void alignedCornerDrawsTwoEdgesAtItsOwnCorner()
    {
        // Top-left corner is aligned to the slot (relX=relY=0), so the formula resolves to its own edges.
        BufferedImage out = SpriteOutline.build(32, 32, SpriteOutline.TOP | SpriteOutline.LEFT, GREEN, 2, 0, 0, 115, 110);

        assertEquals("top line", OPAQUE, out.getRGB(15, 0));
        assertEquals("left line", OPAQUE, out.getRGB(0, 15));
        assertEquals("interior transparent", 0, out.getRGB(16, 16));
    }
}
