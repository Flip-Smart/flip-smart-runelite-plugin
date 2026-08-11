package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferState;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.game.SpriteManager;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Layout contract for the GE slot's "Buy"/"Sell" label and its elapsed timer (#1117).
 *
 * <p>The label and the timer live in separate widgets: the label is pinned to the slot's left
 * border, the timer is right-aligned so a longer duration grows leftward instead of running into
 * the neighbouring slot.
 */
public class GeSlotStateTextLayoutTest
{
    private static final int SLOT = 0;
    private static final int SLOT_WIDTH = 115;

    // The live client reports originalWidth 0 for the state text — it auto-sizes to its content.
    // Stubbing a real width here once hid a bug where the timer inherited a zero-width box and
    // never rendered, so this must stay 0.
    private static final int VANILLA_LABEL_WIDTH = 0;
    private static final int LABEL_BOX_HEIGHT = 25;
    private static final int VANILLA_LABEL_X = 0;

    private GeSlotWidgetDecorator decorator;
    private Widget slotWidget;
    private FakeText label;
    private FakeText timer;

    @Before
    public void setUp()
    {
        decorator = new GeSlotWidgetDecorator(mock(Client.class), mock(FlipSmartConfig.class),
            mock(FlipSmartPlugin.class), mock(SpriteManager.class));

        // Vanilla state text is centred; the decorator left-aligns it while timers are on.
        label = new FakeText(VANILLA_LABEL_X, VANILLA_LABEL_WIDTH, WidgetTextAlignment.CENTER);
        timer = new FakeText(0, 0, WidgetTextAlignment.LEFT);

        slotWidget = mock(Widget.class);
        when(slotWidget.getWidth()).thenReturn(SLOT_WIDTH);
        when(slotWidget.getChild(GeSlotWidgetDecorator.STATE_TEXT_CHILD)).thenReturn(label.widget);
        when(slotWidget.createChild(WidgetType.TEXT)).thenAnswer(i ->
        {
            // The client only exposes an appended child once it is in the parent's array.
            when(slotWidget.getChildren()).thenReturn(new Widget[]{ label.widget, timer.widget });
            return timer.widget;
        });
        when(slotWidget.getChildren()).thenReturn(new Widget[]{ label.widget });
    }

    /** AC1: the label sits tight against the slot's left border, not floating in from it. */
    @Test
    public void labelIsPinnedToTheLeftBorder()
    {
        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BUYING), running());

        assertEquals(WidgetTextAlignment.LEFT, label.widget.getXTextAlignment());
        assertTrue("label should be within a few px of the border, was x=" + label.widget.getOriginalX(),
            label.widget.getOriginalX() - VANILLA_LABEL_X <= 4);
        assertEquals("Buy", label.widget.getText());
    }

    /**
     * AC2 + AC3: the timer is its own right-aligned widget whose box ends short of the slot's right
     * edge, so growing from "0:05" to "15h 07m" extends the text leftward and the right edge holds.
     */
    @Test
    public void timerIsRightAlignedAndItsRightEdgeIsFixed()
    {
        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BUYING), running());

        assertEquals(WidgetTextAlignment.RIGHT, timer.widget.getXTextAlignment());
        int timerBoxRightEdge = timer.widget.getOriginalX() + timer.widget.getOriginalWidth();
        assertEquals("timer's right edge is anchored just inside the slot's right border",
            SLOT_WIDTH - 4, timerBoxRightEdge);
        assertTrue("timer should render a duration, was " + timer.widget.getText(),
            timer.widget.getText().matches("\\d+:\\d{2}"));
    }

    /** The label carries the bare state word — the timer is no longer glued onto it. */
    @Test
    public void labelDoesNotCarryTheTimerText()
    {
        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.SELLING), running());

        assertEquals("Sell", label.widget.getText());
    }

    /** AC5: with timers off the label returns to its vanilla spot and the timer disappears. */
    @Test
    public void revertRestoresVanillaLabelAndHidesTheTimer()
    {
        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BUYING), running());

        decorator.revertStateText(SLOT, slotWidget);

        assertEquals(WidgetTextAlignment.CENTER, label.widget.getXTextAlignment());
        assertEquals(VANILLA_LABEL_X, label.widget.getOriginalX());
        assertTrue("timer should be hidden when the setting is off", timer.widget.isHidden());
    }

    /** AC6: re-enabling puts the label back on the border and shows the timer again. */
    @Test
    public void reapplyingAfterRevertRestoresTheRepositionedLayout()
    {
        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BUYING), running());
        decorator.revertStateText(SLOT, slotWidget);

        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BUYING), running());

        assertEquals(WidgetTextAlignment.LEFT, label.widget.getXTextAlignment());
        assertEquals(VANILLA_LABEL_X + 4, label.widget.getOriginalX());
        assertFalse("timer should be visible again once re-enabled", timer.widget.isHidden());
        // Reused rather than re-appended: one timer child per slot, however often we toggle.
        verify(slotWidget).createChild(WidgetType.TEXT);
    }

    /**
     * AC7: the toggle is presentation only. The decorator reads the record and never writes to it,
     * so a frozen duration is byte-identical across an off/on cycle — no reset, gap or drift.
     */
    @Test
    public void togglingDoesNotDisturbTheUnderlyingTiming()
    {
        OfferRecord tracked = completedAfterFiveMinutes();
        GrandExchangeOffer bought = offer(GrandExchangeOfferState.BOUGHT);

        decorator.applyStateText(SLOT, slotWidget, bought, tracked);
        String before = timer.widget.getText();

        decorator.revertStateText(SLOT, slotWidget);
        decorator.applyStateText(SLOT, slotWidget, bought, tracked);

        assertEquals("5:00", before);
        assertEquals(before, timer.widget.getText());
    }

    /**
     * A finished offer freezes at how long it was open. Completing an offer *is* its last activity,
     * so measuring the frozen span from there read 0:00 on every completed slot.
     */
    @Test
    public void completedOfferFreezesAtHowLongItWasOpen()
    {
        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BOUGHT),
            completedAfterFiveMinutes());

        assertEquals("5:00", timer.widget.getText());
    }

    /** Records persisted before creation time was tracked must not report a bogus epoch-length span. */
    @Test
    public void completedRecordWithoutACreationTimeDoesNotInventADuration()
    {
        OfferRecord legacy = new OfferRecord().withFill(1, 100L, OfferState.FILLED, 300_000L);

        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BOUGHT), legacy);

        assertEquals("0:00", timer.widget.getText());
    }

    /** A store gap costs the timer, never the label. */
    @Test
    public void missingRecordLeavesTheLabelAloneAndSkipsTheTimer()
    {
        decorator.applyStateText(SLOT, slotWidget, offer(GrandExchangeOfferState.BUYING), null);

        assertEquals("Buy", label.widget.getText());
        verify(slotWidget, never()).createChild(anyInt());
    }

    private static GrandExchangeOffer offer(GrandExchangeOfferState state)
    {
        GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
        when(offer.getState()).thenReturn(state);
        return offer;
    }

    private static OfferRecord running()
    {
        long start = System.currentTimeMillis() - 65_000L;
        return OfferRecord.newOffer(1L, SLOT, 4151, "Abyssal whip", true, 1, 100, start);
    }

    /** Exactly how OfferStateMachine completes an offer: one withFill straight to FILLED. */
    private static OfferRecord completedAfterFiveMinutes()
    {
        long start = 1_000_000L;
        return OfferRecord.newOffer(2L, SLOT, 4151, "Abyssal whip", true, 1, 100, start)
            .withFill(1, 100L, OfferState.FILLED, start + 300_000L);
    }

    /** A Widget mock with real x / width / alignment / text / hidden state so layout is assertable. */
    private static final class FakeText
    {
        private final Widget widget = mock(Widget.class);

        FakeText(int x, int width, int alignment)
        {
            int[] geometry = { x, width, alignment, 0 };
            boolean[] hidden = { false };
            String[] text = { "" };

            when(widget.getOriginalX()).thenAnswer(i -> geometry[0]);
            when(widget.setOriginalX(anyInt())).thenAnswer(i -> store(geometry, 0, i.getArgument(0)));
            when(widget.getOriginalWidth()).thenAnswer(i -> geometry[1]);
            when(widget.setOriginalWidth(anyInt())).thenAnswer(i -> store(geometry, 1, i.getArgument(0)));
            when(widget.getXTextAlignment()).thenAnswer(i -> geometry[2]);
            when(widget.setXTextAlignment(anyInt())).thenAnswer(i -> store(geometry, 2, i.getArgument(0)));
            when(widget.getTextColor()).thenAnswer(i -> geometry[3]);
            when(widget.setTextColor(anyInt())).thenAnswer(i -> store(geometry, 3, i.getArgument(0)));
            when(widget.getText()).thenAnswer(i -> text[0]);
            when(widget.setText(anyString())).thenAnswer(i ->
            {
                text[0] = i.getArgument(0);
                return widget;
            });
            when(widget.isHidden()).thenAnswer(i -> hidden[0]);
            when(widget.setHidden(anyBoolean())).thenAnswer(i ->
            {
                hidden[0] = i.getArgument(0);
                return widget;
            });
            when(widget.getOriginalHeight()).thenReturn(LABEL_BOX_HEIGHT);
        }

        private Widget store(int[] geometry, int index, int value)
        {
            geometry[index] = value;
            return widget;
        }
    }
}
