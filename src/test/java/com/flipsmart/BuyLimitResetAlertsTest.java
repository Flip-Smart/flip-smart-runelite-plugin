package com.flipsmart;

import net.runelite.client.Notifier;
import net.runelite.client.config.Notification;
import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class BuyLimitResetAlertsTest
{
	private static final int WHIP = 4151;
	private static final int CANNONBALL = 2;

	private final AtomicLong now = new AtomicLong(1_000_000L);
	private Notifier notifier;
	private BuyLimitResetAlerts alerts;

	@Before
	public void setUp()
	{
		notifier = mock(Notifier.class);
		alerts = new BuyLimitResetAlerts(notifier, () -> Notification.ON,
			id -> id == WHIP ? "Abyssal whip" : "Cannonball", now::get);
	}

	private Instant in(long millis)
	{
		return Instant.ofEpochMilli(now.get() + millis);
	}

	@Test
	public void notifiesOnceWhenTheWindowEnds()
	{
		alerts.track(WHIP, in(5_000));

		alerts.poll();
		verify(notifier, never()).notify(eq(Notification.ON), anyString());

		now.addAndGet(5_000);
		alerts.poll();
		alerts.poll();
		verify(notifier, times(1)).notify(Notification.ON, "Buy limit reset: Abyssal whip");
	}

	@Test
	public void windowsEndingTogetherShareOneNotification()
	{
		alerts.track(WHIP, in(1_000));
		alerts.track(CANNONBALL, in(2_000));

		now.addAndGet(3_000);
		alerts.poll();

		verify(notifier, times(1)).notify(eq(Notification.ON), anyString());
	}

	@Test
	public void aWindowThatAlreadyEndedIsNeverAnnounced()
	{
		alerts.track(WHIP, in(-60_000));

		alerts.poll();

		verify(notifier, never()).notify(eq(Notification.ON), anyString());
	}

	@Test
	public void aNewWindowAfterAResetNotifiesAgain()
	{
		alerts.track(WHIP, in(1_000));
		now.addAndGet(1_000);
		alerts.poll();

		alerts.track(WHIP, in(1_000));
		now.addAndGet(1_000);
		alerts.poll();

		verify(notifier, times(2)).notify(Notification.ON, "Buy limit reset: Abyssal whip");
	}

	@Test
	public void clearDropsPendingWindows()
	{
		alerts.track(WHIP, in(1_000));
		alerts.clear();

		now.addAndGet(1_000);
		alerts.poll();

		verify(notifier, never()).notify(eq(Notification.ON), anyString());
	}
}
