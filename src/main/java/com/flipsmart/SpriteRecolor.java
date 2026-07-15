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
        return tint(src, color, 1.0);
    }

    /**
     * Multiply-tints each pixel toward {@code color}, then blends that result back toward the
     * original by {@code strength} (1.0 = full tint, 0.0 = untouched). Lower strengths keep the
     * native texture visible for a lighter accent. Alpha is preserved; transparent pixels stay
     * transparent.
     */
    public static BufferedImage tint(BufferedImage src, Color color, double strength)
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
                int nr = (int) Math.round(r + (r * color.getRed() / 255 - r) * strength);
                int ng = (int) Math.round(g + (g * color.getGreen() / 255 - g) * strength);
                int nb = (int) Math.round(b + (b * color.getBlue() / 255 - b) * strength);
                out.setRGB(x, y, (a << 24) | (nr << 16) | (ng << 8) | nb);
            }
        }
        return out;
    }
}
