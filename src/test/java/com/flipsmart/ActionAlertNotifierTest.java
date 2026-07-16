package com.flipsmart;

import com.flipsmart.recommend.ActionDecision;
import com.flipsmart.recommend.ActionKind;
import com.flipsmart.recommend.ActionStep;
import net.runelite.client.Notifier;
import net.runelite.client.config.Notification;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the action-alert trigger: which decisions alert, how repeats and flaps are
 * suppressed, and the message text. The empty-slot buy carries a queue-sourced itemId
 * that rotates on every Flip Finder refresh, so the alert key is deliberately coarser
 * than ActionDecision equality — {@link #rotatingBuySuggestionAlertsOnce()} pins that.
 */
public class ActionAlertNotifierTest
{
	private static final int ITEM = 4151;
	private static final int OTHER_ITEM = 561;
	private static final String ITEM_NAME = "Abyssal whip";
	private static final String OTHER_NAME = "Nature rune";

	private Notifier notifier;
	private FlipSmartConfig config;
	private long now;
	private ActionAlertNotifier alerts;

	@Before
	public void setUp()
	{
		notifier = mock(Notifier.class);
		config = mock(FlipSmartConfig.class);
		when(config.actionAlert()).thenReturn(Notification.ON);
		now = 1_000_000L;
		alerts = new ActionAlertNotifier(notifier, config, this::name, () -> now);
	}

	private String name(int itemId)
	{
		return itemId == ITEM ? ITEM_NAME : OTHER_NAME;
	}

	private static ActionDecision decision(ActionKind kind, ActionStep step, int itemId, int slot)
	{
		return new ActionDecision(kind, step, itemId, slot, 0L);
	}

	private static ActionDecision cancel(int itemId, int slot)
	{
		return decision(ActionKind.S6, ActionStep.CANCEL, itemId, slot);
	}

	private static ActionDecision collect(int itemId, int slot)
	{
		return decision(ActionKind.S5, ActionStep.COLLECT, itemId, slot);
	}

	private static ActionDecision placeBuy(int itemId)
	{
		return decision(ActionKind.S2, ActionStep.PLACE_BUY, itemId, -1);
	}

	/** A fresh cancel recommendation alerts, naming the item. */
	@Test
	public void newCancelDecisionAlertsOnce()
	{
		alerts.onDecision(cancel(ITEM, 3));

		verify(notifier, times(1)).notify(eq(Notification.ON), anyString());
	}

	/** The configured Notification is handed to RuneLite, which owns enable-gating. */
	@Test
	public void notifyReceivesTheConfiguredNotification()
	{
		Notification configured = Notification.OFF;
		when(config.actionAlert()).thenReturn(configured);

		alerts.onDecision(cancel(ITEM, 3));

		verify(notifier).notify(eq(configured), anyString());
	}

	/** resolveAndApply re-applies an unchanged decision on every offer event. */
	@Test
	public void repeatedDecisionIsSilent()
	{
		alerts.onDecision(cancel(ITEM, 3));
		alerts.onDecision(cancel(ITEM, 3));
		alerts.onDecision(cancel(ITEM, 3));

		verify(notifier, times(1)).notify(any(Notification.class), anyString());
	}

	/** detectedAtMillis is excluded from decision identity; it must not re-alert. */
	@Test
	public void detectedAtChangeIsSilent()
	{
		alerts.onDecision(new ActionDecision(ActionKind.S6, ActionStep.CANCEL, ITEM, 3, 1L));
		alerts.onDecision(new ActionDecision(ActionKind.S6, ActionStep.CANCEL, ITEM, 3, 99_999L));

		verify(notifier, times(1)).notify(any(Notification.class), anyString());
	}

	/** Nothing to do — stay silent. */
	@Test
	public void idleIsSilent()
	{
		alerts.onDecision(ActionDecision.IDLE);

		verify(notifier, never()).notify(any(Notification.class), anyString());
	}

	/** A null decision must not throw. */
	@Test
	public void nullDecisionIsSilent()
	{
		alerts.onDecision(null);

		verify(notifier, never()).notify(any(Notification.class), anyString());
	}

	/**
	 * THE regression this class exists for: the Flip Finder queue rotates its suggested
	 * buy every refresh, so the decision's itemId changes while the actionable fact —
	 * a slot is free — does not. Alert once, not once per rotation.
	 */
	@Test
	public void rotatingBuySuggestionAlertsOnce()
	{
		alerts.onDecision(placeBuy(ITEM));
		now += 120_000L;
		alerts.onDecision(placeBuy(OTHER_ITEM));
		now += 120_000L;
		alerts.onDecision(placeBuy(ITEM));

		verify(notifier, times(1)).notify(any(Notification.class), anyString());
	}

	/** Distinct actions on the same item are distinct alerts. */
	@Test
	public void cancelThenCollectAlertsTwice()
	{
		alerts.onDecision(cancel(ITEM, 3));
		alerts.onDecision(collect(ITEM, 3));

		verify(notifier, times(2)).notify(any(Notification.class), anyString());
	}

	/** The same action on a different slot is a different alert. */
	@Test
	public void sameActionDifferentSlotAlertsTwice()
	{
		alerts.onDecision(cancel(ITEM, 3));
		alerts.onDecision(cancel(ITEM, 5));

		verify(notifier, times(2)).notify(any(Notification.class), anyString());
	}

	/** Going idle then re-detecting the same action re-alerts once the cooldown lapses. */
	@Test
	public void actionReturningAfterIdleReAlertsOnceCooldownLapses()
	{
		alerts.onDecision(cancel(ITEM, 3));
		alerts.onDecision(ActionDecision.IDLE);
		now += ActionAlertNotifier.COOLDOWN_MS;
		alerts.onDecision(cancel(ITEM, 3));

		verify(notifier, times(2)).notify(any(Notification.class), anyString());
	}

	/** Message text per step. */
	@Test
	public void messageNamesTheItemPerStep()
	{
		assertEquals("Cancel your Abyssal whip offer", ActionAlertNotifier.message(cancel(ITEM, 3), this::name));
		assertEquals("Collect your Abyssal whip", ActionAlertNotifier.message(collect(ITEM, 3), this::name));
		assertEquals("A Grand Exchange slot is free", ActionAlertNotifier.message(placeBuy(ITEM), this::name));
		assertEquals("List Abyssal whip to sell",
			ActionAlertNotifier.message(decision(ActionKind.SELL_WAITING, ActionStep.LIST, ITEM, -1), this::name));
		assertEquals("Adjust the price of your Abyssal whip offer",
			ActionAlertNotifier.message(decision(ActionKind.S4, ActionStep.REPRICE, ITEM, 3), this::name));
	}

	/** The alert-key table: PLACE_BUY is item-agnostic, LIST is per-item, the rest are per-offer. */
	@Test
	public void alertKeyTable()
	{
		assertNull(ActionAlertNotifier.alertKey(ActionDecision.IDLE));
		assertNull(ActionAlertNotifier.alertKey(null));
		assertEquals("PLACE_BUY", ActionAlertNotifier.alertKey(placeBuy(ITEM)));
		assertEquals("PLACE_BUY", ActionAlertNotifier.alertKey(placeBuy(OTHER_ITEM)));
		assertEquals("LIST:4151",
			ActionAlertNotifier.alertKey(decision(ActionKind.SELL_WAITING, ActionStep.LIST, ITEM, -1)));
		assertEquals("CANCEL:4151:3", ActionAlertNotifier.alertKey(cancel(ITEM, 3)));
		assertEquals("COLLECT:4151:3", ActionAlertNotifier.alertKey(collect(ITEM, 3)));
	}
}
