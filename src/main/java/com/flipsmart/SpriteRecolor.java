package com.flipsmart;

import java.awt.Color;
import java.awt.image.BufferedImage;

public final class SpriteRecolor
{
    private SpriteRecolor()
    {
    }

    public static BufferedImage tint(BufferedImage src, Color color)
    {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++)
        {
            for (int x = 0; x < w; x++)
            {
                int argb = src.getRGB(x, y);
                int a = argb >>> 24;
                if (a == 0)
                {
                    out.setRGB(x, y, 0);
                    continue;
                }
                int r = (argb >> 16) & 0xff;
                int g = (argb >> 8) & 0xff;
                int b = argb & 0xff;
                int nr = r * color.getRed() / 255;
                int ng = g * color.getGreen() / 255;
                int nb = b * color.getBlue() / 255;
                out.setRGB(x, y, (a << 24) | (nr << 16) | (ng << 8) | nb);
            }
        }
        return out;
    }
}
