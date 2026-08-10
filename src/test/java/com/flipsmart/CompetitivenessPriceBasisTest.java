package com.flipsmart;

import com.flipsmart.api.dto.Dtos.WikiPrice;
import java.lang.reflect.Field;
import net.runelite.api.Client;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which price a verdict is measured against matters more than how fresh it is. The wiki pair
 * answers buys against insta-sell and sells against insta-buy; the guide price is one blended
 * number that answers neither, so falling back to it can reverse the verdict on an offer that
 * has not changed. That reversal, arriving once a cache lifetime, is what reached players as a
 * flashing border.
 */
public class CompetitivenessPriceBasisTest
{
    private static final int WHIP = 4151;
    private static final int INSTA_BUY = 2_000_000;
    private static final int INSTA_SELL = 1_800_000;
    // Sits between the two: competitive to buy against the pair, uncompetitive against the blend.
    private static final int OFFER_PRICE = 1_850_000;
    private static final int GUIDE_PRICE = 1_900_000;

    private FlipSmartPlugin plugin;
    private FlipSmartApiClient apiClient;
    private ItemManager itemManager;

    @Before
    public void setUp() throws Exception
    {
        plugin = new FlipSmartPlugin();
        apiClient = mock(FlipSmartApiClient.class);
        itemManager = mock(ItemManager.class);
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        when(itemManager.getItemPrice(WHIP)).thenReturn(GUIDE_PRICE);

        inject("apiClient", apiClient);
        inject("itemManager", itemManager);
        inject("client", client);
    }

    private void inject(String field, Object value) throws Exception
    {
        Field f = FlipSmartPlugin.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(plugin, value);
    }

    @Test
    public void anAgedOutPair_isPreferredOverTheGuidePrice()
    {
        when(apiClient.getLastKnownWikiPrice(WHIP)).thenReturn(new WikiPrice(INSTA_BUY, INSTA_SELL));

        assertEquals(FlipSmartPlugin.OfferCompetitiveness.COMPETITIVE,
            plugin.calculateCompetitiveness(WHIP, OFFER_PRICE, true));
        verify(itemManager, never()).getItemPrice(anyInt());
    }

    @Test
    public void theGuidePriceIsStillUsedWhenNoPairWasEverHeld()
    {
        when(apiClient.getLastKnownWikiPrice(WHIP)).thenReturn(null);

        assertEquals(FlipSmartPlugin.OfferCompetitiveness.UNCOMPETITIVE,
            plugin.calculateCompetitiveness(WHIP, OFFER_PRICE, true));
    }
}
