package com.flipsmart;

import com.flipsmart.trading.OfferStore;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.client.game.SpriteManager;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

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
            mock(Client.class), config, plugin, mock(SpriteManager.class));

        GrandExchangeOffer live = mock(GrandExchangeOffer.class);
        when(live.getState()).thenReturn(GrandExchangeOfferState.BUYING);
        when(live.getItemId()).thenReturn(4444);
        when(live.getPrice()).thenReturn(100);

        decorator.reconcileBorder(mock(Widget.class), 0, live, true);

        verify(plugin).calculateCompetitiveness(4444, 100, true);
    }

}
