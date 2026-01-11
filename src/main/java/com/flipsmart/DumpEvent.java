package com.flipsmart;

import com.google.gson.annotations.SerializedName;
import lombok.Data;

/**
 * Represents a market dump event from the Flip Smart API.
 * A dump is a sudden price drop (≥5%) with high trading volume.
 */
@Data
public class DumpEvent
{
	/**
	 * Unique identifier for this dump event
	 */
	private int id;

	/**
	 * OSRS item ID
	 */
	@SerializedName("item_id")
	private int itemId;

	/**
	 * Item name
	 */
	@SerializedName("item_name")
	private String itemName;

	/**
	 * Price before the dump
	 */
	@SerializedName("previous_price")
	private int previousPrice;

	/**
	 * Price after the dump (current instant-sell price)
	 */
	@SerializedName("current_price")
	private int currentPrice;

	/**
	 * Percentage drop (e.g., 5.5 for a 5.5% drop)
	 */
	@SerializedName("price_drop_percent")
	private double priceDropPercent;

	/**
	 * Current instant-sell price (buy price for players)
	 */
	@SerializedName("buy_price")
	private int buyPrice;

	/**
	 * Current instant-buy price (sell price for players)
	 */
	@SerializedName("sell_price")
	private int sellPrice;

	/**
	 * Net margin after GE tax
	 */
	private int margin;

	/**
	 * 1-hour average price (may be null)
	 */
	@SerializedName("price_1h_avg")
	private Integer price1hAvg;

	/**
	 * 24-hour average price (may be null)
	 */
	@SerializedName("price_24h_avg")
	private Integer price24hAvg;

	/**
	 * Estimated 24-hour trading volume
	 */
	@SerializedName("volume_24h")
	private int volume24h;

	/**
	 * GE buy limit for this item (may be null)
	 */
	@SerializedName("buy_limit")
	private Integer buyLimit;

	/**
	 * Estimated profit (margin × buy_limit, may be null)
	 */
	@SerializedName("estimated_profit")
	private Integer estimatedProfit;

	/**
	 * When the dump was detected (ISO 8601 timestamp string)
	 */
	@SerializedName("dump_detected_at")
	private String dumpDetectedAt;

	/**
	 * Format the dump as a chat message
	 */
	public String toChatMessage()
	{
		String profitStr = estimatedProfit != null
			? String.format("~%s", formatGP(estimatedProfit))
			: "Unknown";

		return String.format(
			"DUMP: %s - Buy: %s | Sell: %s | Limit: %s | Profit: %s",
			itemName,
			formatGP(buyPrice),
			formatGP(sellPrice),
			buyLimit != null ? String.format("%,d", buyLimit) : "Unknown",
			profitStr
		);
	}

	/**
	 * Format GP with K/M/B suffixes
	 */
	private static String formatGP(int value)
	{
		if (value >= 1_000_000_000)
		{
			return String.format("%.1fB", value / 1_000_000_000.0);
		}
		else if (value >= 1_000_000)
		{
			return String.format("%.1fM", value / 1_000_000.0);
		}
		else if (value >= 1_000)
		{
			return String.format("%.1fK", value / 1_000.0);
		}
		else
		{
			return String.format("%dgp", value);
		}
	}
}
