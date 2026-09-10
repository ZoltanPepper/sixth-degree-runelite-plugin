package com.sixthdegree;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

@Slf4j
@Singleton
final class SixthDegreeSoundService
{
	enum Cue
	{
		BOTW("botw.wav"),
		COLLECTION_LOG("collection-log.wav"),
		DEATH("death.wav"),
		LFG("lfg.wav"),
		LOGIN("login.wav"),
		PERSONAL_BEST("personal-best.wav"),
		QUEST("quest.wav"),
		SOTW("sotw.wav"),
		WINNER("winner.wav");

		private final String fileName;

		Cue(String fileName)
		{
			this.fileName = fileName;
		}
	}

	private final AudioPlayer audioPlayer;
	private ExecutorService executor;

	@Inject
	SixthDegreeSoundService(AudioPlayer audioPlayer)
	{
		this.audioPlayer = audioPlayer;
	}

	synchronized void start()
	{
		if (executor != null && !executor.isShutdown())
		{
			return;
		}
		executor = Executors.newSingleThreadExecutor(r ->
		{
			Thread thread = new Thread(r, "sixth-degree-sounds");
			thread.setDaemon(true);
			return thread;
		});
	}

	synchronized void stop()
	{
		if (executor != null)
		{
			executor.shutdownNow();
			executor = null;
		}
	}

	void play(Cue cue)
	{
		ExecutorService current;
		synchronized (this)
		{
			current = executor;
		}
		if (cue == null || current == null || current.isShutdown())
		{
			return;
		}
		try
		{
			current.execute(() -> playNow(cue));
		}
		catch (RejectedExecutionException ignored)
		{
			// RuneLite is shutting the plugin down.
		}
	}

	static String resourcePath(Cue cue)
	{
		return "/com/sixthdegree/sounds/" + cue.fileName;
	}

	private void playNow(Cue cue)
	{
		try
		{
			audioPlayer.play(SixthDegreeSoundService.class, resourcePath(cue), 0.0f);
		}
		catch (Exception error)
		{
			log.debug("Unable to play Sixth Degree sound {}", cue, error);
		}
	}
}
