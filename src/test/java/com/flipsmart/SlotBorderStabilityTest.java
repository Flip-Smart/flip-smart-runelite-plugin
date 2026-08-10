package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.SpriteManager;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The border is recomputed every game tick. Any tick that cannot decide a colour used to
 * revert the slot to vanilla, so a single undecidable tick in a run of decidable ones read
 * as a flash. These pin the stability rule and the limit on it.
 */
public class SlotBorderStabilityTest
{
    private final FlipSmartPlugin plugin = mock(FlipSmartPlugin.class);
    private final FlipSmartConfig config = mock(FlipSmartConfig.class);

    private GeSlotWidgetDecorator decorator()
    {
        GeSlotWidgetDecorator real = new GeSlotWidgetDecorator(
            mock(Client.class), config, plugin, mock(SpriteManager.class));
        GeSlotWidgetDecorator decorator = spy(real);
        doNothing().when(decorator).applyBorder(anyInt(), any(), any());
        doNothing().when(decorator).revertBorder(anyInt(), any());
        return decorator;
    }

    private static GrandExchangeOffer buyOffer(int itemId, int price)
    {
        GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
        when(offer.getState()).thenReturn(GrandExchangeOfferState.BUYING);
        when(offer.getItemId()).thenReturn(itemId);
        when(offer.getPrice()).thenReturn(price);
        return offer;
    }

    private void verdict(FlipSmartPlugin.OfferCompetitiveness first,
        FlipSmartPlugin.OfferCompetitiveness... rest)
    {
        when(plugin.calculateCompetitiveness(anyInt(), anyInt(), anyBoolean()))
            .thenReturn(first, rest);
    }

    @Test
    public void undecidableTick_holdsTheLastDecidedTint_insteadOfRevertingToVanilla()
    {
        verdict(FlipSmartPlugin.OfferCompetitiveness.COMPETITIVE,
            FlipSmartPlugin.OfferCompetitiveness.UNKNOWN);

        GeSlotWidgetDecorator decorator = decorator();
        Widget slotWidget = mock(Widget.class);
        GrandExchangeOffer offer = buyOffer(4444, 100);

        decorator.reconcileBorder(slotWidget, 0, offer, true);
        decorator.reconcileBorder(slotWidget, 0, offer, true);

        verify(decorator, times(2)).applyBorder(0, slotWidget, SlotBorderTint.GREEN);
        verify(decorator, never()).revertBorder(anyInt(), any());
    }

    @Test
    public void aRepricedOfferInTheSameSlot_doesNotInheritTheHeldTint()
    {
        verdict(FlipSmartPlugin.OfferCompetitiveness.COMPETITIVE,
            FlipSmartPlugin.OfferCompetitiveness.UNKNOWN);

        GeSlotWidgetDecorator decorator = decorator();
        Widget slotWidget = mock(Widget.class);

        decorator.reconcileBorder(slotWidget, 0, buyOffer(4444, 100), true);
        // Same slot, same item, cancelled and relisted at a price we cannot yet judge.
        decorator.reconcileBorder(slotWidget, 0, buyOffer(4444, 250), true);

        verify(decorator, times(1)).applyBorder(anyInt(), any(), any());
        verify(decorator).revertBorder(0, slotWidget);
    }

    @Test
    public void aHeldTintInOneSlot_doesNotLeakIntoAnother()
    {
        verdict(FlipSmartPlugin.OfferCompetitiveness.COMPETITIVE,
            FlipSmartPlugin.OfferCompetitiveness.UNKNOWN);

        GeSlotWidgetDecorator decorator = decorator();
        Widget slotWidget = mock(Widget.class);
        GrandExchangeOffer offer = buyOffer(4444, 100);

        decorator.reconcileBorder(slotWidget, 0, offer, true);
        decorator.reconcileBorder(slotWidget, 3, offer, true);

        verify(decorator).applyBorder(0, slotWidget, SlotBorderTint.GREEN);
        verify(decorator).revertBorder(3, slotWidget);
    }
}
