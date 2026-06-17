package com.flipsmart;

import com.flipsmart.trading.OfferStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Verifies the offer-screen lock state machine on {@link AutoRecommendService}.
 * Covers the public state contract only; the {@code invokeFocusCallback} gate
 * is exercised via manual QA because it requires a fully-wired queue.
 */
public class AutoRecommendServiceLockTest
{
	private AutoRecommendService service;

	@Before
	public void setUp()
	{
		FlipSmartConfig config = mock(FlipSmartConfig.class);
		FlipSmartPlugin plugin = mock(FlipSmartPlugin.class);
		service = new AutoRecommendService(config, plugin, new OfferStore());
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
