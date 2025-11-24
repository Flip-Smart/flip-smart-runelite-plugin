package com.flipsmart;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("flipsmart")
public interface FlipSmartConfig extends Config
{
	// ============================================
	// Authentication Section
	// ============================================
	@ConfigSection(
		name = "Authentication",
		description = "Account settings for Flip Smart API",
		position = 0
	)
	String authenticationSection = "authentication";

	@ConfigItem(
		keyName = "email",
		name = "Email",
		description = "Your account email for authentication",
		section = authenticationSection,
		position = 0
	)
	default String email()
	{
		return "";
	}

	@ConfigItem(
		keyName = "password",
		name = "Password",
		description = "Your account password",
		secret = true,
		section = authenticationSection,
		position = 1
	)
	default String password()
	{
		return "";
	}

	@ConfigItem(
		keyName = "apiUrl",
		name = "API URL",
		description = "URL of the Flip Smart API",
		section = authenticationSection,
		position = 2
	)
	default String apiUrl()
	{
		return "http://localhost:8000";
	}

	// ============================================
	// Flip Finder Section
	// ============================================
	@ConfigSection(
		name = "Flip Finder",
		description = "Settings for flip recommendations",
		position = 1,
		closedByDefault = false
	)
	String flipFinderSection = "flipFinder";

	@ConfigItem(
		keyName = "showFlipFinder",
		name = "Enable Flip Finder",
		description = "Show the Flip Finder panel in the sidebar",
		section = flipFinderSection,
		position = 0
	)
	default boolean showFlipFinder()
	{
		return true;
	}

	@ConfigItem(
		keyName = "flipStyle",
		name = "Flip Style",
		description = "Your preferred flipping style",
		section = flipFinderSection,
		position = 1
	)
	default FlipStyle flipStyle()
	{
		return FlipStyle.BALANCED;
	}

	@ConfigItem(
		keyName = "flipFinderLimit",
		name = "Number of Recommendations",
		description = "Number of flip recommendations to show (1-50)",
		section = flipFinderSection,
		position = 2
	)
	default int flipFinderLimit()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "flipFinderRefreshMinutes",
		name = "Refresh Interval (minutes)",
		description = "How often to refresh flip recommendations (1-60 minutes)",
		section = flipFinderSection,
		position = 3
	)
	default int flipFinderRefreshMinutes()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "minProfit",
		name = "Minimum Profit",
		description = "Minimum profit margin to highlight (in GP)",
		section = flipFinderSection,
		position = 4
	)
	default int minimumProfit()
	{
		return 100;
	}

	// ============================================
	// Display Section
	// ============================================
	@ConfigSection(
		name = "Display",
		description = "Display and overlay settings",
		position = 2,
		closedByDefault = false
	)
	String displaySection = "display";

	@ConfigItem(
		keyName = "showGEOverlay",
		name = "Show GE Tracker",
		description = "Display in-game Grand Exchange offer tracker",
		section = displaySection,
		position = 0
	)
	default boolean showGEOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGEItemNames",
		name = "Show Item Names",
		description = "Display item names in the GE tracker",
		section = displaySection,
		position = 1
	)
	default boolean showGEItemNames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGEItemIcons",
		name = "Show Item Icons",
		description = "Display item icons in the GE tracker",
		section = displaySection,
		position = 2
	)
	default boolean showGEItemIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGEDetailedInfo",
		name = "Show Detailed Info",
		description = "Show quantity, price per item, and total value",
		section = displaySection,
		position = 3
	)
	default boolean showGEDetailedInfo()
	{
		return true;
	}

	// ============================================
	// General Section
	// ============================================
	@ConfigSection(
		name = "General",
		description = "General plugin settings",
		position = 3,
		closedByDefault = true
	)
	String generalSection = "general";

	@ConfigItem(
		keyName = "trackHistory",
		name = "Track History",
		description = "Track flipping history across sessions",
		section = generalSection,
		position = 0
	)
	default boolean trackHistory()
	{
		return true;
	}

	// ============================================
	// Flip Style Enum
	// ============================================
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

