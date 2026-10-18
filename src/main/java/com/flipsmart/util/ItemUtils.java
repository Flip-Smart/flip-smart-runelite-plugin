package com.flipsmart.util;

import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

/**
 * Utility class for item-related operations shared across the plugin.
 */
public final class ItemUtils
{
	private ItemUtils()
	{
		// Utility class - prevent instantiation
	}

	/**
	 * Safely gets the name of an item by ID, returning "Unknown Item" if the composition is null.
	 *
	 * @param itemManager The RuneLite item manager
	 * @param itemId The item ID to look up
	 * @return The item name, or "Unknown Item" if composition is null
	 */
	public static String getItemName(ItemManager itemManager, int itemId)
	{
		ItemComposition composition = itemManager.getItemComposition(itemId);
		return composition != null ? composition.getName() : "Unknown Item";
	}

	/**
	 * Checks if an item is tradeable on the Grand Exchange.
	 *
	 * @param itemManager The RuneLite item manager
	 * @param itemId The item ID to check
	 * @return true if the item exists and is tradeable, false otherwise
	 */
	public static boolean isTradeable(ItemManager itemManager, int itemId)
	{
		ItemComposition composition = itemManager.getItemComposition(itemId);
		return composition != null && composition.isTradeable();
	}

	/**
	 * Read a bundled resource as space-separated rows. Rows start with item ids;
	 * anything after them (an item name) is for readers of the file.
	 */
	public static java.util.stream.Stream<String[]> resourceRows(String path)
	{
		try (java.io.InputStream in = ItemUtils.class.getResourceAsStream(path))
		{
			return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().map(l -> l.split(" "));
		}
		catch (java.io.IOException e)
		{
			throw new java.io.UncheckedIOException(e);
		}
	}
}
