package com.flipsmart.recommend;

public enum ActionKind {
    // Priority is by ordinal (lowest wins). SELL_WAITING sits above S2 so a held
    // item the player is waiting to sell takes a freed slot before a new buy.
    S1, SELL_WAITING, S2, S3, S4, S5, S6, IDLE
}
