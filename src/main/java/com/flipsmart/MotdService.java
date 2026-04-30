package com.flipsmart;

import lombok.extern.slf4j.Slf4j;
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
import javax.inject.Singleton;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls /motd at 60s and posts the plugin channel's message to the chatbox
 * once per version per client. Subscribes to game-state LOGGED_IN to surface
 * any unseen MOTD on login between polls.
 */
@Slf4j
@Singleton
public class MotdService
{
	private static final String CONFIG_GROUP = "flipsmart";
	private static final String LAST_SHOWN_KEY = "motd.lastShownVersion";
	private static final long POLL_INTERVAL_SECONDS = 60L;
	private static final long INITIAL_DELAY_SECONDS = 5L;

	private final Client client;
	private final FlipSmartConfig config;
	private final FlipSmartApiClient apiClient;
	private final ChatMessageManager chatMessageManager;
	private final ClientThread clientThread;
	private final ConfigManager configManager;

	private ScheduledExecutorService executor;
	private ScheduledFuture<?> pollingTask;

	private FlipSmartApiClient.MotdResponse latest;

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

	public void start()
	{
		stop();
		executor = Executors.newSingleThreadScheduledExecutor();
		pollingTask = executor.scheduleAtFixedRate(this::poll,
			INITIAL_DELAY_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
		log.debug("MotdService started ({}s interval)", POLL_INTERVAL_SECONDS);
	}

	public void stop()
	{
		if (pollingTask != null)
		{
			pollingTask.cancel(false);
			pollingTask = null;
		}
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	private void poll()
	{
		try
		{
			apiClient.getMotdAsync().thenAccept(this::handleResponse);
		}
		catch (Exception e)
		{
			log.debug("MOTD poll error: {}", e.getMessage());
		}
	}

	/** Visible for testing. */
	void handleResponse(FlipSmartApiClient.MotdResponse response)
	{
		if (response == null) return;
		latest = response;
		maybeShow();
	}

	/** Subscribed in FlipSmartPlugin on game-state LOGGED_IN. */
	public void onLogin()
	{
		maybeShow();
	}

	private void maybeShow()
	{
		if (!config.motdEnabled()) return;
		if (latest == null) return;
		FlipSmartApiClient.MotdChannelData plugin = latest.getPlugin();
		if (plugin == null) return;
		if (!plugin.isEnabled()) return;
		String message = plugin.getMessage();
		if (message == null || message.isEmpty()) return;
		String version = plugin.getVersion();
		if (version == null) return;
		if (client.getGameState() != GameState.LOGGED_IN) return;

		String lastShown = configManager.getConfiguration(CONFIG_GROUP, LAST_SHOWN_KEY);
		if (version.equals(lastShown)) return;

		clientThread.invokeLater(() -> postChat(message));
		configManager.setConfiguration(CONFIG_GROUP, LAST_SHOWN_KEY, version);
	}

	private void postChat(String message)
	{
		String formatted = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[FlipSmart MOTD] ")
			.append(ChatColorType.NORMAL)
			.append(message)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(formatted)
			.build());
	}
}
