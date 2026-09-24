package com.flipsmart;

import org.mockito.stubbing.Answer;

/**
 * Stubs a RuneLite money getter ({@code GrandExchangeOffer.getPrice/getSpent},
 * {@code ItemManager.getItemPrice}) so the same test compiles and runs against both
 * RuneLite 1.12 (int) and 1.13+ (long). {@code thenReturn(100)} fails to compile on
 * 1.13 and {@code thenReturn(100L)} fails on 1.12, so box to whichever type the
 * stubbed method actually returns.
 */
public final class GpAnswer
{
	private GpAnswer()
	{
	}

	public static Answer<Object> gp(long value)
	{
		return invocation -> invocation.getMethod().getReturnType() == long.class
			? (Object) value
			: (Object) Math.toIntExact(value);
	}
}
