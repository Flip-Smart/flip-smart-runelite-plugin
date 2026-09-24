package com.flipsmart.v9;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Holds and persists {@link V9FlipState} per active sell, keyed by item id, scoped per RSN.
 * Follows the existing offer/ledger persistence idiom (JSON blob under the flipsmart config
 * group). Absolute timers are never stored — rungs are recomputed from the listing timestamp
 * on restore, so offline elapsed time counts. Entries older than {@link #MAX_AGE_MS} are
 * dropped on load so a long-abandoned flip can't resurrect a stale prompt.
 */
@Slf4j
public class V9FlipStateStore
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String KEY_PREFIX = "v9FlipState_";

	// Covers the longest ladder (4h: 2h40m + 2h40m) with generous margin.
	static final long MAX_AGE_MS = 6L * 60L * 60L * 1000L;

	private final Gson gson;
	private final Map<Integer, V9FlipState> byItem = new ConcurrentHashMap<>();

	public V9FlipStateStore(Gson gson)
	{
		this.gson = gson;
	}

	public V9FlipState get(int itemId)
	{
		return byItem.get(itemId);
	}

	public void put(V9FlipState state)
	{
		if (state != null)
		{
			byItem.put(state.getItemId(), state);
		}
	}

	public void remove(int itemId)
	{
		byItem.remove(itemId);
	}

	public Map<Integer, V9FlipState> snapshot()
	{
		return new HashMap<>(byItem);
	}

	public void clear()
	{
		byItem.clear();
	}

	/** Serialize the current map to JSON (pure; the persistence entry points wrap this). */
	String serialize(long now)
	{
		Map<Integer, V9FlipState> out = new HashMap<>();
		for (V9FlipState s : byItem.values())
		{
			s.setSavedAtMillis(now);
			out.put(s.getItemId(), s);
		}
		return gson.toJson(out);
	}

	/** Replace the in-memory map from JSON, dropping entries older than {@link #MAX_AGE_MS}. */
	void load(String json, long now)
	{
		byItem.clear();
		if (json == null || json.isEmpty())
		{
			return;
		}
		try
		{
			Type type = new TypeToken<Map<Integer, V9FlipState>>() {}.getType();
			Map<Integer, V9FlipState> parsed = gson.fromJson(json, type);
			if (parsed == null)
			{
				return;
			}
			for (V9FlipState s : parsed.values())
			{
				if (s == null)
				{
					continue;
				}
				if (now - s.getSavedAtMillis() <= MAX_AGE_MS)
				{
					byItem.put(s.getItemId(), s);
				}
			}
		}
		catch (Exception e)
		{
			logLoadFailure(e);
		}
	}

	private void logLoadFailure(Exception e)
	{
		if (log.isDebugEnabled())
		{
			log.debug("Ignoring unreadable persisted v9 flip state ({})", e.getMessage());
		}
	}

	public void persist(ConfigManager configManager, String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}
		try
		{
			if (byItem.isEmpty())
			{
				configManager.unsetConfiguration(CONFIG_GROUP, KEY_PREFIX + rsn);
				return;
			}
			configManager.setConfiguration(CONFIG_GROUP, KEY_PREFIX + rsn, serialize(System.currentTimeMillis()));
		}
		catch (Exception e)
		{
			if (log.isDebugEnabled())
			{
				log.debug("Failed to persist v9 flip state for {}: {}", rsn, e.getMessage());
			}
		}
	}

	public void restore(ConfigManager configManager, String rsn)
	{
		if (rsn == null || rsn.isEmpty())
		{
			return;
		}
		String json = configManager.getConfiguration(CONFIG_GROUP, KEY_PREFIX + rsn);
		load(json, System.currentTimeMillis());
	}
}
