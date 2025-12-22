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
		description = "",
		hidden = true,
		secret = true
	)
	default String password()
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
}

