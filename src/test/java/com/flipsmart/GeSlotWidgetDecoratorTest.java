package com.flipsmart;

import com.flipsmart.trading.OfferStore;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GeSlotWidgetDecoratorTest
{
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
