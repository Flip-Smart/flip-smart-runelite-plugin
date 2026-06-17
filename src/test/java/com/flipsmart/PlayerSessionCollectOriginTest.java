package com.flipsmart;

import com.flipsmart.recommend.CollectOrigin;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlayerSessionCollectOriginTest {

    @Test
    public void tracksOriginAndTimestamp() {
        PlayerSession s = new PlayerSession();
        s.addCollectedItem(100, 5, CollectOrigin.PARTIAL_CANCEL, 1234L);
        assertTrue(s.getCollectedItemIds().contains(100));
        assertEquals(5, s.getCollectedQuantity(100));
        assertEquals(CollectOrigin.PARTIAL_CANCEL, s.getCollectOrigin(100));
        assertEquals(1234L, s.getCollectedAtMillis(100));
    }

    @Test
    public void untrackedItemHasNullOriginAndZeroTimestamp() {
        PlayerSession s = new PlayerSession();
        assertNull(s.getCollectOrigin(999));
        assertEquals(0L, s.getCollectedAtMillis(999));
    }

    @Test
    public void removeClearsOriginAndTimestamp() {
        PlayerSession s = new PlayerSession();
        s.addCollectedItem(100, 5, CollectOrigin.COMPLETED_BUY, 1234L);
        s.removeCollectedItem(100);
        assertNull(s.getCollectOrigin(100));
        assertEquals(0L, s.getCollectedAtMillis(100));
    }
}
