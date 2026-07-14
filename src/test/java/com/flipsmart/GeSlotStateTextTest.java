package com.flipsmart;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GeSlotStateTextTest
{
    @Test
    public void buyWithShortTimer()
    {
        assertEquals("Buy  <col=ffffff>5:32</col>", GeSlotStateText.build("Buy", "5:32", "ffffff"));
    }

    @Test
    public void sellWithHourLengthTimerDoesNotTruncateOrReorder()
    {
        // AC9: even at hour lengths the label + timer are one clean string
        assertEquals("Sell  <col=4cbb17>1:23:45</col>", GeSlotStateText.build("Sell", "1:23:45", "4cbb17"));
    }

    @Test
    public void labelStaysUntaggedSoItKeepsNativeColor()
    {
        String out = GeSlotStateText.build("Buy", "0:05", "ffffff");
        assertEquals("Buy", out.substring(0, out.indexOf("<col=")).trim());
    }
}
