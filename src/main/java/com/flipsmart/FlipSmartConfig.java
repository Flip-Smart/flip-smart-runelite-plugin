package com.flipsmart;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("flipsmart")
public interface FlipSmartConfig extends Config
{
	@ConfigItem(
		keyName = "showOverlay",
		name = "Show Overlay",
		description = "Toggles the display of the flipping overlay"
	)
	default boolean showOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minProfit",
		name = "Minimum Profit",
		description = "Minimum profit margin to highlight (in GP)"
	)
	default int minimumProfit()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "trackHistory",
		name = "Track History",
		description = "Track flipping history across sessions"
	)
	default boolean trackHistory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "apiUrl",
		name = "API URL",
		description = "URL of the Flip Smart API"
	)
	default String apiUrl()
	{
		return "http://localhost:8000";
	}

	@ConfigItem(
		keyName = "email",
		name = "Email",
		description = "Your account email for authentication"
	)
	default String email()
	{
		return "";
	}

	@ConfigItem(
		keyName = "password",
		name = "Password",
		description = "Your account password",
		secret = true
	)
	default String password()
	{
		return "";
	}

	@ConfigItem(
		keyName = "highlightGoodFlips",
		name = "Highlight Good Flips",
		description = "Highlight items in inventory that are good flips"
	)
	default boolean highlightGoodFlips()
	{
		return true;
	}

	@ConfigItem(
		keyName = "minEfficiencyScore",
		name = "Minimum Efficiency Score",
		description = "Minimum efficiency score to highlight an item (0-100)"
	)
	default int minEfficiencyScore()
	{
		return 50;
	}
}

