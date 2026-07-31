package com.flipsmart;

import com.flipsmart.trading.OfferStore;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class GeSlotWidgetDecoratorTest
{
    /**
     * A competitive-price check needs only item, price and direction, all of which the client
     * reports. Reading them from the tracked record made a transient store gap blank the border.
     */
    @Test
    public void borderTint_comesFromTheLiveOffer_notTheTrackedRecord()
    {
        OfferStore store = new OfferStore();   // deliberately empty: the store has lost this slot
        FlipSmartPlugin plugin = mock(FlipSmartPlugin.class);
        when(plugin.getOfferStore()).thenReturn(store);
        when(plugin.calculateCompetitiveness(anyInt(), anyInt(), anyBoolean()))
            .thenReturn(FlipSmartPlugin.OfferCompetitiveness.UNKNOWN);

        FlipSmartConfig config = mock(FlipSmartConfig.class);
        GeSlotWidgetDecorator decorator = new GeSlotWidgetDecorator(
            mock(Client.class), config, plugin, mock(SpriteManager.class), mock(ItemManager.class));

        GrandExchangeOffer live = mock(GrandExchangeOffer.class);
        when(live.getState()).thenReturn(GrandExchangeOfferState.BUYING);
        when(live.getItemId()).thenReturn(4444);
        when(live.getPrice()).thenReturn(100);

        decorator.reconcileBorder(mock(Widget.class), 0, live, true);

        verify(plugin).calculateCompetitiveness(4444, 100, true);
    }

    @Test
    public void seedStoreFromLiveOffers_seedsUnmappedLiveSlot()
    {
        OfferStore store = new OfferStore();
        FlipSmartPlugin plugin = mock(FlipSmartPlugin.class);
        when(plugin.getOfferStore()).thenReturn(store);

        ItemManager itemManager = mock(ItemManager.class);
        ItemComposition comp = mock(ItemComposition.class);
        when(comp.getName()).thenReturn("Raw shark");
        when(itemManager.getItemComposition(anyInt())).thenReturn(comp);

        GeSlotWidgetDecorator decorator = new GeSlotWidgetDecorator(
            mock(Client.class), mock(FlipSmartConfig.class), plugin, mock(SpriteManager.class), itemManager);

        GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
        when(offer.getState()).thenReturn(GrandExchangeOfferState.BUYING);
        when(offer.getItemId()).thenReturn(383);
        when(offer.getTotalQuantity()).thenReturn(10);
        when(offer.getPrice()).thenReturn(1000);
        when(offer.getQuantitySold()).thenReturn(0);
        when(offer.getSpent()).thenReturn(0);

        decorator.seedStoreFromLiveOffers(new GrandExchangeOffer[]{ offer });

        assertNotNull("slot 0 must be seeded from the live offer", store.bySlot(0));
        assertEquals(383, store.bySlot(0).getItemId());
    }
}
