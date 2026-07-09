package com.flipsmart.recommend;

public enum ActionKind {
    // Priority is by ordinal (lowest wins). SELL_WAITING and S3 both sit above S2 so
    // the collect->sell pipeline for an item we already own is finished before capital
    // is deployed into a new buy: SELL_WAITING lists an already-collected item, and S3
    // collects a completed buy (which then becomes a SELL_WAITING) — both ahead of the
    // S2 new buy.
    S1, SELL_WAITING, S3, S2, S4, S5, S6, IDLE
}
