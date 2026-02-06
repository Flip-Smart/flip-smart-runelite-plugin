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
	 * Type of price change: "dump" (decrease) or "pump" (increase)
	 */
	@SerializedName("price_change_type")
	private String priceChangeType;

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
			? String.format("~%s", GpUtils.formatGPExact(estimatedProfit))
			: "Unknown";

		String eventType = "pump".equalsIgnoreCase(priceChangeType) ? "PUMP" : "DUMP";
		String changeSymbol = "pump".equalsIgnoreCase(priceChangeType) ? "+" : "-";

		return String.format(
			"%s: %s (%s%.1f%%) - Buy: %s | Sell: %s | Limit: %s | Profit: %s",
			eventType,
			itemName,
			changeSymbol,
			priceDropPercent,
			GpUtils.formatGPExact(buyPrice),
			GpUtils.formatGPExact(sellPrice),
			buyLimit != null ? String.format("%,d", buyLimit) : "Unknown",
			profitStr
		);
	}

}
