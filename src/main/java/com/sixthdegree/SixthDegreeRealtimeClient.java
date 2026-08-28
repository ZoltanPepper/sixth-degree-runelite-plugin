package com.sixthdegree;

import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@Singleton
final class SixthDegreeRealtimeClient
{
	private static final String WS_URL = SixthDegreeApiClient.API_BASE.replaceFirst("^https://", "wss://") + "/ws";

	private final OkHttpClient httpClient;
	private final Gson gson;

	@Inject
	SixthDegreeRealtimeClient(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	CompletableFuture<WebSocket> connect(String sessionToken, Consumer<RealtimeEvent> onEvent, Runnable onClosed)
	{
		CompletableFuture<WebSocket> connected = new CompletableFuture<>();
		Request request = new Request.Builder()
			.url(WS_URL)
			.header("Authorization", "Bearer " + sessionToken)
			.header("User-Agent", "Sixth-Degree-RuneLite/0.5")
			.header("ngrok-skip-browser-warning", "sixth-degree-runelite")
			.build();
		httpClient.newWebSocket(request, new Listener(gson, onEvent, onClosed, connected));
		return connected;
	}

	static final class RealtimeEvent
	{
		String type;
		String rsn;
		long server_time;
		SixthDegreeApiClient.LfgEntry entry;
	}

	private static final class Listener extends WebSocketListener
	{
		private final Gson gson;
		private final Consumer<RealtimeEvent> onEvent;
		private final Runnable onClosed;
		private final CompletableFuture<WebSocket> connected;
		private final AtomicBoolean closed = new AtomicBoolean(false);

		private Listener(
			Gson gson,
			Consumer<RealtimeEvent> onEvent,
			Runnable onClosed,
			CompletableFuture<WebSocket> connected)
		{
			this.gson = gson;
			this.onEvent = onEvent;
			this.onClosed = onClosed;
			this.connected = connected;
		}

		@Override
		public void onOpen(WebSocket webSocket, Response response)
		{
			connected.complete(webSocket);
		}

		@Override
		public void onMessage(WebSocket webSocket, String text)
		{
			try
			{
				RealtimeEvent event = gson.fromJson(text, RealtimeEvent.class);
				if (event != null && event.type != null)
				{
					onEvent.accept(event);
				}
			}
			catch (Exception ignored)
			{
				// Ignore malformed realtime messages; the normal API remains authoritative.
			}
		}

		@Override
		public void onClosed(WebSocket webSocket, int code, String reason)
		{
			notifyClosed();
		}

		@Override
		public void onFailure(WebSocket webSocket, Throwable throwable, Response response)
		{
			connected.completeExceptionally(throwable);
			notifyClosed();
			if (response != null)
			{
				response.close();
			}
		}

		private void notifyClosed()
		{
			if (closed.compareAndSet(false, true))
			{
				onClosed.run();
			}
		}
	}
}
