package com.flipsmart;

import org.junit.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import static org.junit.Assert.assertEquals;

public class SpriteRecolorTest
{
    @Test
    public void fullStrengthMidGrayTintedGreenKeepsLuminanceOnGreenChannelOnly()
    {
        BufferedImage src = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, new Color(128, 128, 128, 255).getRGB());

        BufferedImage out = SpriteRecolor.tint(src, new Color(0, 255, 0), 1.0);

        assertEquals(new Color(0, 128, 0, 255).getRGB(), out.getRGB(0, 0));
    }

    @Test
    public void halfStrengthBlendsBackTowardOriginal()
    {
        BufferedImage src = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, new Color(128, 128, 128, 255).getRGB());

        // Full tint = (0,128,0); half strength halves the delta from the original 128 → (64,128,64).
        BufferedImage out = SpriteRecolor.tint(src, new Color(0, 255, 0), 0.5);

        assertEquals(new Color(64, 128, 64, 255).getRGB(), out.getRGB(0, 0));
    }

    @Test
    public void transparentPixelStaysTransparent()
    {
        BufferedImage src = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, 0x00000000);

        BufferedImage out = SpriteRecolor.tint(src, new Color(255, 0, 0), 1.0);

        assertEquals(0, (out.getRGB(0, 0) >>> 24));
    }

    @Test
    public void preservesAlpha()
    {
        BufferedImage src = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, new Color(200, 200, 200, 120).getRGB());

        BufferedImage out = SpriteRecolor.tint(src, new Color(255, 255, 255), 1.0);

        assertEquals(120, (out.getRGB(0, 0) >>> 24));
    }
}
