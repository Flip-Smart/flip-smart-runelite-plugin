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

	@ConfigItem(
		keyName = "showFlipFinder",
		name = "Show Flip Finder",
		description = "Show the Flip Finder panel in the sidebar"
	)
	default boolean showFlipFinder()
	{
		return true;
	}

	@ConfigItem(
		keyName = "flipStyle",
		name = "Flip Style",
		description = "Your preferred flipping style"
	)
	default FlipStyle flipStyle()
	{
		return FlipStyle.BALANCED;
	}

	@ConfigItem(
		keyName = "flipFinderLimit",
		name = "Number of Recommendations",
		description = "Number of flip recommendations to show (1-50)"
	)
	default int flipFinderLimit()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "flipFinderRefreshMinutes",
		name = "Refresh Interval (minutes)",
		description = "How often to refresh flip recommendations (1-60 minutes)"
	)
	default int flipFinderRefreshMinutes()
	{
		return 5;
	}

	enum FlipStyle
	{
		CONSERVATIVE("conservative"),
		BALANCED("balanced"),
		AGGRESSIVE("aggressive");

		private final String apiValue;

		FlipStyle(String apiValue)
		{
			this.apiValue = apiValue;
		}

		public String getApiValue()
		{
			return apiValue;
		}

		@Override
		public String toString()
		{
			return name().charAt(0) + name().substring(1).toLowerCase();
		}
	}
}

