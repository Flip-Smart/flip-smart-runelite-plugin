package com.flipsmart;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import net.runelite.api.vars.AccountType;
import org.junit.Test;

public class AccountTypeMapperTest
{
	@Test
	public void mapsEnumConstantsToLowercaseApiValues()
	{
		assertEquals("normal", AccountTypeMapper.toApiValue(AccountType.NORMAL));
		assertEquals("ironman", AccountTypeMapper.toApiValue(AccountType.IRONMAN));
		assertEquals("hardcore_ironman", AccountTypeMapper.toApiValue(AccountType.HARDCORE_IRONMAN));
		assertEquals("ultimate_ironman", AccountTypeMapper.toApiValue(AccountType.ULTIMATE_IRONMAN));
		assertEquals("group_ironman", AccountTypeMapper.toApiValue(AccountType.GROUP_IRONMAN));
		assertEquals("hardcore_group_ironman", AccountTypeMapper.toApiValue(AccountType.HARDCORE_GROUP_IRONMAN));
	}

	@Test
	public void nullMapsToNull()
	{
		assertNull(AccountTypeMapper.toApiValue(null));
	}
}
