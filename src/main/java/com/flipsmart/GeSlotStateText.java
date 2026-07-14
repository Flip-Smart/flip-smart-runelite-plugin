package com.flipsmart;

public final class GeSlotStateText
{
    private static final String SPACER = "  ";

    private GeSlotStateText()
    {
    }

    public static String build(String label, String timer, String timerColorHex)
    {
        return label + SPACER + "<col=" + timerColorHex + ">" + timer + "</col>";
    }
}
