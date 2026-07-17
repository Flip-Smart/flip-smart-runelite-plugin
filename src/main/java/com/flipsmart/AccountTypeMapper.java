package com.flipsmart;

import java.util.Locale;
import net.runelite.api.vars.AccountType;

final class AccountTypeMapper
{
	private AccountTypeMapper()
	{
	}

	static String toApiValue(AccountType accountType)
	{
		return accountType == null ? null : accountType.name().toLowerCase(Locale.ROOT);
	}
}
