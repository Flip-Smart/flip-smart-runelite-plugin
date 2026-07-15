package com.flipsmart;

import org.junit.Test;
import java.awt.Color;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static com.flipsmart.FlipSmartPlugin.OfferCompetitiveness.*;

public class SlotBorderTintTest
{
    @Test
    public void competitiveNormalIsGreen()
    {
        assertEquals(Optional.of(SlotBorderTint.GREEN), SlotBorderTint.forOffer(COMPETITIVE, false));
        assertEquals(new Color(76, 187, 23), SlotBorderTint.GREEN.getColor());
    }

    @Test
    public void uncompetitiveNormalIsRed()
    {
        assertEquals(Optional.of(SlotBorderTint.RED), SlotBorderTint.forOffer(UNCOMPETITIVE, false));
        assertEquals(new Color(215, 75, 75), SlotBorderTint.RED.getColor());
    }

    @Test
    public void colorblindSwapsToBlueAndOrange()
    {
        assertEquals(Optional.of(SlotBorderTint.BLUE), SlotBorderTint.forOffer(COMPETITIVE, true));
        assertEquals(Optional.of(SlotBorderTint.ORANGE), SlotBorderTint.forOffer(UNCOMPETITIVE, true));
        assertEquals(new Color(0, 102, 204), SlotBorderTint.BLUE.getColor());
        assertEquals(new Color(255, 140, 0), SlotBorderTint.ORANGE.getColor());
    }

    @Test
    public void unknownYieldsEmpty()
    {
        assertEquals(Optional.empty(), SlotBorderTint.forOffer(UNKNOWN, false));
        assertEquals(Optional.empty(), SlotBorderTint.forOffer(UNKNOWN, true));
    }
}
