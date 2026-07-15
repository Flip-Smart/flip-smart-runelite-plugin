package com.flipsmart;

import java.awt.Color;
import java.util.Optional;

public enum SlotBorderTint
{
    GREEN(new Color(76, 187, 23)),
    RED(new Color(215, 75, 75)),
    BLUE(new Color(0, 102, 204)),
    ORANGE(new Color(255, 140, 0));

    private final Color color;

    SlotBorderTint(Color color)
    {
        this.color = color;
    }

    public Color getColor()
    {
        return color;
    }

    public static Optional<SlotBorderTint> forOffer(FlipSmartPlugin.OfferCompetitiveness competitiveness, boolean colorblind)
    {
        switch (competitiveness)
        {
            case COMPETITIVE:
                return Optional.of(colorblind ? BLUE : GREEN);
            case UNCOMPETITIVE:
                return Optional.of(colorblind ? ORANGE : RED);
            default:
                return Optional.empty();
        }
    }
}
