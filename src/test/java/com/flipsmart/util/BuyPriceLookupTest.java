package com.flipsmart.util;

import com.flipsmart.domain.flip.ActiveFlip;
import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * #1089 D4: the offer-screen breakeven/margin and slot-hover P&L resolve the
 * recorded buy price from the backend-sourced active-flips list. When that list
 * is empty (refresh race, free-tier trim, or plain absence) the price came back
 * null and every derived stat rendered "?", even though the buy is sitting in
 * the local OfferStore. The fallback resolves the price from OfferStore so the
 * stats stay populated.
 */
public class BuyPriceLookupTest
{
    private static ActiveFlip flip(int itemId, int avgBuy)
    {
        ActiveFlip f = new ActiveFlip();
        f.setItemId(itemId);
        f.setAverageBuyPrice(avgBuy);
        return f;
    }

    private static OfferRecord buy(int itemId, int filled, long spent)
    {
        int price = filled > 0 ? (int) (spent / filled) : 0;
        return OfferRecord.newOffer(1L, 0, itemId, "i" + itemId, true, filled, price, 0L)
            .withFill(filled, spent, OfferState.PARTIAL_FILL, 0L);
    }

    @Test
    public void activeFlipPresent_usesFlipPrice_ignoresFallback()
    {
        Integer p = BuyPriceLookup.findAverageBuyPriceWithFallback(
            Collections.singletonList(flip(536, 3284)),
            null,
            Collections.singletonList(buy(536, 100, 100L * 9999)),
            536);
        assertEquals(Integer.valueOf(3284), p);
    }

    /**
     * #1091: the offer store never evicts, so its records outlive the flip they belong to.
     * Averaging across them made breakeven describe stock the player had already sold. The
     * ledger's basis is scoped to the open cycle, so it cannot.
     */
    @Test
    public void cycleBasisOutranksRecordsFromAlreadyClosedFlips()
    {
        List<OfferRecord> staleRecords = asList(buy(536, 1000, 1000L * 9999), buy(536, 1000, 1000L * 8888));

        Integer p = BuyPriceLookup.findAverageBuyPriceWithFallback(
            Collections.emptyList(), 3284, staleRecords, 536);

        assertEquals(Integer.valueOf(3284), p);
    }

    @Test
    public void activeFlipStillOutranksTheCycleBasis()
    {
        Integer p = BuyPriceLookup.findAverageBuyPriceWithFallback(
            Collections.singletonList(flip(536, 3284)), 9999, Collections.emptyList(), 536);

        assertEquals("the backend snapshot stays authoritative", Integer.valueOf(3284), p);
    }

    @Test
    public void recordsStillCoverAnItemTheLedgerHasNoBasisFor()
    {
        // A client whose ledger has not seen a fill for this item yet — e.g. the buy happened
        // before this build shipped. The records remain the last resort.
        Integer p = BuyPriceLookup.findAverageBuyPriceWithFallback(
            Collections.emptyList(), null, Collections.singletonList(buy(536, 1000, 1000L * 3284)), 536);

        assertEquals(Integer.valueOf(3284), p);
    }

    @Test
    public void activeFlipsEmpty_fallsBackToOfferStoreWeightedAverage()
    {
        // (3000*3284 + 1000*3300) / 4000 = 3288
        List<OfferRecord> offers = asList(buy(536, 3000, 3000L * 3284), buy(536, 1000, 1000L * 3300));
        Integer p = BuyPriceLookup.findAverageBuyPriceWithFallback(Collections.emptyList(), null, offers, 536);
        assertEquals(Integer.valueOf(3288), p);
    }

    @Test
    public void fallbackIgnoresSellsAndOtherItems()
    {
        OfferRecord otherItem = buy(999, 500, 500L * 100);
        List<OfferRecord> offers = asList(buy(536, 1000, 1000L * 3284), otherItem);
        Integer p = BuyPriceLookup.findAverageBuyPriceWithFallback(Collections.emptyList(), null, offers, 536);
        assertEquals(Integer.valueOf(3284), p);
    }

    @Test
    public void neitherSourceHasItem_returnsNull()
    {
        assertNull(BuyPriceLookup.findAverageBuyPriceWithFallback(
            Collections.emptyList(), null, Collections.emptyList(), 536));
    }
}
