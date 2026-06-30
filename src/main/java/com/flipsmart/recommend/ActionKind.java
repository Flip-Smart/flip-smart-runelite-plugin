package com.flipsmart.recommend;

public enum ActionKind {
    // Priority is by ordinal (lowest wins). SELL_WAITING sits above S2 so an item
    // collected preemptively from an open trade is sold before placing a new buy.
    S1, SELL_WAITING, S2, S3, S4, S5, S6, IDLE
}
