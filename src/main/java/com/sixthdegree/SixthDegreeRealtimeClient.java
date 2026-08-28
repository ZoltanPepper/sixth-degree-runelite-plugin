package com.sixthdegree;

import com.google.gson.Gson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class SixthDegreeRealtimeClient
{
	private static final String WS_URL = SixthDegreeApiClient.API_BASE.replaceFirst("^https://", "wss://") + "/ws";

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private final Gson gson = new Gson();

	CompletableFuture<WebSocket> connect(String sessionToken, Consumer<RealtimeEvent> onEvent, Runnable onClosed)
	{
		Listener listener = new Listener(gson, onEvent, onClosed);
		return httpClient.newWebSocketBuilder()
			.header("Authorization", "Bearer " + sessionToken)
			.header("User-Agent", "Sixth-Degree-RuneLite/0.4")
			.header("ngrok-skip-browser-warning", "sixth-degree-runelite")
			.connectTimeout(Duration.ofSeconds(15))
			.buildAsync(URI.create(WS_URL), listener);
	}

	static final class RealtimeEvent
	{
		String type;
		String rsn;
		long server_time;
		SixthDegreeApiClient.LfgEntry entry;
	}

	private static final class Listener implements WebSocket.Listener
	{
		private final Gson gson;
		private final Consumer<RealtimeEvent> onEvent;
		private final Runnable onClosed;
		private final StringBuilder text = new StringBuilder();

		private Listener(Gson gson, Consumer<RealtimeEvent> onEvent, Runnable onClosed)
		{
			this.gson = gson;
			this.onEvent = onEvent;
			this.onClosed = onClosed;
		}

		@Override
		public void onOpen(WebSocket webSocket)
		{
			webSocket.request(1);
		}

		@Override
		public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last)
		{
			text.append(data);
			if (last)
			{
				try
				{
					RealtimeEvent event = gson.fromJson(text.toString(), RealtimeEvent.class);
					if (event != null && event.type != null)
					{
						onEvent.accept(event);
					}
				}
				catch (Exception ignored)
				{
					// Ignore malformed realtime messages; the normal API remains authoritative.
				}
				finally
				{
					text.setLength(0);
				}
			}
			webSocket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason)
		{
			onClosed.run();
			return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error)
		{
			onClosed.run();
		}
	}
}
