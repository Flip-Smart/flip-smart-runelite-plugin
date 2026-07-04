package com.flipsmart;
import com.flipsmart.api.dto.MotdChannelData;
import com.flipsmart.api.dto.MotdResponse;

import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;

import javax.inject.Inject;
import java.awt.Color;
import javax.inject.Singleton;

/**
 * Fetches /motd once per login (game-state LOGGED_IN) and posts the plugin
 * channel's message to the chatbox at most once per version per client. No
 * background polling: a message set while a player is already logged in
 * surfaces on their next login.
 */
@Singleton
public class MotdService
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String LAST_SHOWN_KEY = "motd.lastShownVersion";

	private final Client client;
	private final FlipSmartConfig config;
	private final FlipSmartApiClient apiClient;
	private final ChatMessageManager chatMessageManager;
	private final ClientThread clientThread;
	private final ConfigManager configManager;

	private MotdResponse latest;

	@Inject
	public MotdService(
		Client client,
		FlipSmartConfig config,
		FlipSmartApiClient apiClient,
		ChatMessageManager chatMessageManager,
		ClientThread clientThread,
		ConfigManager configManager)
	{
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.chatMessageManager = chatMessageManager;
		this.clientThread = clientThread;
		this.configManager = configManager;
	}

	/** Visible for testing. */
	void handleResponse(MotdResponse response)
	{
		if (response == null) return;
		latest = response;
		maybeShow();
	}

	/** Subscribed in FlipSmartPlugin on game-state LOGGED_IN. */
	public void onLogin()
	{
		apiClient.getMotdAsync().thenAccept(response ->
		{
			if (response != null)
			{
				latest = response;
			}
			maybeShow();
		});
	}

	private void maybeShow()
	{
		if (!config.motdEnabled()) return;
		if (latest == null) return;
		MotdChannelData plugin = latest.getPlugin();
		if (plugin == null) return;
		if (!plugin.isEnabled()) return;
		String message = plugin.getMessage();
		if (message == null || message.isEmpty()) return;
		String version = plugin.getVersion();
		if (version == null) return;
		if (client.getGameState() != GameState.LOGGED_IN) return;

		// Show at most once per (client, version) — both login and poll paths
		// dedup against the persisted lastShownVersion to avoid spamming users
		// who log in/out repeatedly during one announcement.
		String lastShown = configManager.getConfiguration(CONFIG_GROUP, LAST_SHOWN_KEY);
		if (version.equals(lastShown)) return;

		String severity = plugin.getSeverity();
		clientThread.invokeLater(() -> postChat(message, severity));
		configManager.setConfiguration(CONFIG_GROUP, LAST_SHOWN_KEY, version);
	}

	private void postChat(String message, String severity)
	{
		ChatMessageBuilder builder = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[FlipSmart] ");

		if ("alert".equalsIgnoreCase(severity))
		{
			builder.append(Color.RED, message);
		}
		else if ("warning".equalsIgnoreCase(severity))
		{
			builder.append(Color.ORANGE, message);
		}
		else
		{
			// "normal" or unknown — fall back to the theme's default text color so the
			// message remains readable in both opaque and transparent chatbox modes.
			builder.append(ChatColorType.NORMAL).append(message);
		}

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(builder.build())
			.build());
	}
}
