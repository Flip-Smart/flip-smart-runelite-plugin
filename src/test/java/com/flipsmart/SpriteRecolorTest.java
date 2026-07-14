package com.flipsmart;

import org.junit.Test;
import java.awt.Color;
import java.awt.image.BufferedImage;
import static org.junit.Assert.assertEquals;

public class SpriteRecolorTest
{
    @Test
    public void midGrayTintedGreenKeepsLuminanceOnGreenChannelOnly()
    {
        BufferedImage src = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, new Color(128, 128, 128, 255).getRGB());

        BufferedImage out = SpriteRecolor.tint(src, new Color(0, 255, 0));

        assertEquals(new Color(0, 128, 0, 255).getRGB(), out.getRGB(0, 0));
    }

    @Test
    public void transparentPixelStaysTransparent()
    {
        BufferedImage src = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, 0x00000000);

        BufferedImage out = SpriteRecolor.tint(src, new Color(255, 0, 0));

        assertEquals(0, (out.getRGB(0, 0) >>> 24));
    }

    @Test
    public void preservesAlpha()
    {
        BufferedImage src = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        src.setRGB(0, 0, new Color(200, 200, 200, 120).getRGB());

        BufferedImage out = SpriteRecolor.tint(src, new Color(255, 255, 255));

        assertEquals(120, (out.getRGB(0, 0) >>> 24));
    }
}
