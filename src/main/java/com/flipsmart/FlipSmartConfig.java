package com.flipsmart;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;

import java.awt.event.KeyEvent;

@ConfigGroup("flipsmart")
public interface FlipSmartConfig extends Config
{
	// ============================================
	// Advanced Section (API URL override only)
	// ============================================
	@ConfigSection(
		name = "Advanced",
		description = "Advanced settings",
		position = 0,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "apiUrl",
		name = "API URL Override",
		description = "Leave empty to use production server (https://flipsm.art). Only set this to override with a custom server URL.",
		section = advancedSection,
		position = 0
	)
	default String apiUrl()
	{
		return "";
	}

	// Hidden config items (not shown in UI, but used for persistence)
	// These are accessed via ConfigManager directly

	@ConfigItem(
		keyName = "email",
		name = "",
		description = "",
		hidden = true
	)
	default String email()
	{
		return "";
	}

	@ConfigItem(
		keyName = "password",
		name = "",
		description = "Deprecated: Use refresh token instead",
		hidden = true,
		secret = true
	)
	default String password()
	{
		return "";
	}

	@ConfigItem(
		keyName = "refreshToken",
		name = "",
		description = "Refresh token for persistent login (do not edit manually)",
		hidden = true,
		secret = true
	)
	default String refreshToken()
	{
		return "";
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
		keyName = "flipFinderLimit",
		name = "Number of Recommendations",
		description = "Number of flip recommendations to show (1-50)",
		section = flipFinderSection,
		position = 1
	)
	default int flipFinderLimit()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "flipStyle",
		name = "Flip Style",
		description = "Your preferred flipping style: Conservative (safer, faster fills), Balanced (mix of both), Aggressive (higher profit, slower fills)",
		section = flipFinderSection,
		position = 2
	)
	default FlipStyle flipStyle()
	{
		return FlipStyle.BALANCED;
	}

	@ConfigItem(
		keyName = "flipTimeframe",
		name = "Timeframe",
		description = "Target flip timeframe: Active (standard recommendations), or time-optimized profiles (30m, 2h, 4h, 12h)",
		section = flipFinderSection,
		position = 3
	)
	default FlipTimeframe flipTimeframe()
	{
		return FlipTimeframe.ACTIVE;
	}

	@ConfigItem(
		keyName = "flipFinderRefreshMinutes",
		name = "Refresh Interval (minutes)",
		description = "How often to refresh flip recommendations (1-60 minutes)",
		section = flipFinderSection,
		position = 5
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
		name = "Show Exchange Viewer",
		description = "Display in-game Grand Exchange offer tracker (hidden when at the GE area)",
		section = displaySection,
		position = 0
	)
	default boolean showGEOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "exchangeViewerSize",
		name = "Display Size",
		description = "Size of the Exchange Viewer overlay",
		section = displaySection,
		position = 1
	)
	default ExchangeViewerSize exchangeViewerSize()
	{
		return ExchangeViewerSize.FULL;
	}

	@ConfigItem(
		keyName = "showGEItemNames",
		name = "Show Item Names",
		description = "Display item names in the Exchange Viewer (Full mode only)",
		section = displaySection,
		position = 2
	)
	default boolean showGEItemNames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGEItemIcons",
		name = "Show Item Icons",
		description = "Display item icons in the Exchange Viewer",
		section = displaySection,
		position = 3
	)
	default boolean showGEItemIcons()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showGEDetailedInfo",
		name = "Show Detailed Info",
		description = "Show quantity, price per item, and total value (Full mode only)",
		section = displaySection,
		position = 4
	)
	default boolean showGEDetailedInfo()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOfferTimers",
		name = "Show Offer Timers",
		description = "Display elapsed time for each GE offer",
		section = displaySection,
		position = 5
	)
	default boolean showOfferTimers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showCompetitivenessIndicators",
		name = "Show Competitiveness",
		description = "Display indicators comparing your price to Wiki prices (green = competitive, red = uncompetitive)",
		section = displaySection,
		position = 6
	)
	default boolean showCompetitivenessIndicators()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightSlotBorders",
		name = "Highlight Slot Borders",
		description = "Draw colored borders around GE slots based on competitiveness",
		section = displaySection,
		position = 7
	)
	default boolean highlightSlotBorders()
	{
		return true;
	}

	@ConfigItem(
		keyName = "colorblindMode",
		name = "Colorblind Mode",
		description = "Use blue/orange colors instead of green/red for better accessibility",
		section = displaySection,
		position = 8
	)
	default boolean colorblindMode()
	{
		return false;
	}

	// ============================================
	// Flip Assistant Section (Guided Workflow + Quick Actions)
	// ============================================
	@ConfigSection(
		name = "Flip Assistant",
		description = "Guided step-by-step flip workflow with hotkey support",
		position = 3,
		closedByDefault = false
	)
	String flipAssistantSection = "flipAssistant";

	@ConfigItem(
		keyName = "enableFlipAssistant",
		name = "Enable Flip Assistant",
		description = "Show the guided flip assistant panel when focusing on a flip",
		section = flipAssistantSection,
		position = 0
	)
	default boolean enableFlipAssistant()
	{
		return true;
	}

	@ConfigItem(
		keyName = "easyFlipHotkey",
		name = "Auto-Fill Hotkey",
		description = "Hotkey to auto-fill price/quantity in GE (default: E)",
		section = flipAssistantSection,
		position = 1
	)
	default Keybind flipAssistHotkey()
	{
		return new Keybind(KeyEvent.VK_E, 0);
	}

	@ConfigItem(
		keyName = "highlightGEWidgets",
		name = "Highlight GE Buttons",
		description = "Highlight buy/sell buttons and input fields in the GE",
		section = flipAssistantSection,
		position = 2
	)
	default boolean highlightGEWidgets()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showAssistantAlways",
		name = "Show When GE Closed",
		description = "Show assistant even when Grand Exchange is not open",
		section = flipAssistantSection,
		position = 3
	)
	default boolean showAssistantAlways()
	{
		return false;
	}

	@ConfigItem(
		keyName = "priceOffset",
		name = "Price Offset (GP)",
		description = "Adjust buy/sell prices to fill faster. Positive = buy higher and sell lower by this amount. Set to 0 for no offset.",
		section = flipAssistantSection,
		position = 4
	)
	default int priceOffset()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "enableAutoRecommend",
		name = "Auto-Recommend",
		description = "Automatically cycle through flip recommendations one by one into Flip Assist",
		section = flipAssistantSection,
		position = 5
	)
	default boolean enableAutoRecommend()
	{
		return false;
	}

	// ============================================
	// Market Dumps Section
	// ============================================
	@ConfigSection(
		name = "Market Dumps",
		description = "Real-time alerts for sudden price drops in the Grand Exchange",
		position = 4,
		closedByDefault = false
	)
	String marketDumpsSection = "marketDumps";

	@ConfigItem(
		keyName = "enableDumpAlerts",
		name = "Enable Price Alerts",
		description = "Show chat alerts when significant price changes are detected (≥5% price changes with high volume)",
		section = marketDumpsSection,
		position = 0
	)
	default boolean enableDumpAlerts()
	{
		return false;
	}

	@ConfigItem(
		keyName = "priceAlertType",
		name = "Alert Type",
		description = "Type of price changes to alert: Dumps (decreases), Pumps (increases), or Both",
		section = marketDumpsSection,
		position = 1
	)
	default PriceAlertType priceAlertType()
	{
		return PriceAlertType.DUMPS_ONLY;
	}

	@ConfigItem(
		keyName = "dumpAlertMinProfit",
		name = "Minimum Profit",
		description = "Only alert for price changes with estimated profit above this amount (in GP)",
		section = marketDumpsSection,
		position = 2
	)
	default int dumpAlertMinProfit()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "dumpAlertInterval",
		name = "Check Interval (seconds)",
		description = "How often to check for new price changes (30-300 seconds)",
		section = marketDumpsSection,
		position = 3
	)
	default int dumpAlertInterval()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "dumpAlertMaxCount",
		name = "Max Alerts Per Check",
		description = "Only show the top X most profitable price changes per check (1-50)",
		section = marketDumpsSection,
		position = 4
	)
	default int dumpAlertMaxCount()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "dumpAlertCooldownMinutes",
		name = "Item Cooldown (minutes)",
		description = "Don't re-alert for the same item within this many minutes (0-1440)",
		section = marketDumpsSection,
		position = 5
	)
	default int dumpAlertCooldownMinutes()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "dumpAlertSortByProfit",
		name = "Sort by Profit",
		description = "Sort alerts by estimated profit instead of recency (most recent first)",
		section = marketDumpsSection,
		position = 6
	)
	default boolean dumpAlertSortByProfit()
	{
		return false;
	}

	// ============================================
	// General Section
	// ============================================
	@ConfigSection(
		name = "General",
		description = "General plugin settings",
		position = 5,
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

	// ============================================
	// Flip Timeframe Enum
	// ============================================
	enum FlipTimeframe
	{
		ACTIVE("active", "Active"),
		THIRTY_MINS("30m", "30 mins"),
		TWO_HOURS("2h", "2 hours"),
		FOUR_HOURS("4h", "4 hours"),
		TWELVE_HOURS("12h", "12 hours");

		private final String apiValue;
		private final String displayName;

		FlipTimeframe(String apiValue, String displayName)
		{
			this.apiValue = apiValue;
			this.displayName = displayName;
		}

		public String getApiValue()
		{
			return apiValue;
		}

		public boolean isTimeframeBased()
		{
			return this != ACTIVE;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	// ============================================
	// Exchange Viewer Display Size Enum
	// ============================================
	enum ExchangeViewerSize
	{
		FULL("Full"),
		COMPACT("Compact");

		private final String displayName;

		ExchangeViewerSize(String displayName)
		{
			this.displayName = displayName;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	// ============================================
	// Price Alert Type Enum
	// ============================================
	enum PriceAlertType
	{
		DUMPS_ONLY("Dumps Only"),
		PUMPS_ONLY("Pumps Only"),
		BOTH("Both");

		private final String displayName;

		PriceAlertType(String displayName)
		{
			this.displayName = displayName;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}
}

