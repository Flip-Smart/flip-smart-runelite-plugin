package com.flipsmart;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Issue #702 — verifies the offer-screen lock state machine on
 * {@link AutoRecommendService}. The lock guards against the auto-advance race
 * where focus could silently switch to a different item while the user is
 * mid-input on a GE offer screen.
 *
 * <p>These tests cover the lock's public state contract only. The
 * {@code invokeFocusCallback} gate is exercised indirectly through manual QA
 * (the offer-screen reproduction) because it requires a fully-wired queue.
 */
public class AutoRecommendServiceLockTest
{
	private AutoRecommendService service;

	@Before
	public void setUp()
	{
		FlipSmartConfig config = mock(FlipSmartConfig.class);
		FlipSmartPlugin plugin = mock(FlipSmartPlugin.class);
		service = new AutoRecommendService(config, plugin);
	}

	@Test
	public void lockStartsCleared()
	{
		assertNull(service.getLockedItemId());
	}

	@Test
	public void acquireOfferLockStoresItemId()
	{
		service.acquireOfferLock(1234);
		assertEquals(Integer.valueOf(1234), service.getLockedItemId());
	}

	@Test
	public void acquireOfferLockIgnoresNonPositiveItemId()
	{
		service.acquireOfferLock(0);
		assertNull(service.getLockedItemId());

		service.acquireOfferLock(-1);
		assertNull(service.getLockedItemId());
	}

	@Test
	public void acquireOfferLockReplacesExistingLock()
	{
		service.acquireOfferLock(1111);
		service.acquireOfferLock(2222);
		assertEquals(Integer.valueOf(2222), service.getLockedItemId());
	}

	@Test
	public void acquireOfferLockIsIdempotentForSameItem()
	{
		service.acquireOfferLock(4444);
		service.acquireOfferLock(4444);
		assertEquals(Integer.valueOf(4444), service.getLockedItemId());
	}

	@Test
	public void releaseOfferLockClearsState()
	{
		service.acquireOfferLock(5555);
		service.releaseOfferLock();
		assertNull(service.getLockedItemId());
	}

	@Test
	public void releaseOfferLockIsSafeWhenAlreadyCleared()
	{
		service.releaseOfferLock();
		assertNull(service.getLockedItemId());
	}
}
