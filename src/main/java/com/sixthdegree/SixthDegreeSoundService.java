package com.sixthdegree;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import lombok.extern.slf4j.Slf4j;

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

	private ExecutorService executor;

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

	private static void playNow(Cue cue)
	{
		try (InputStream resource = SixthDegreeSoundService.class.getResourceAsStream(resourcePath(cue)))
		{
			if (resource == null)
			{
				log.warn("Missing Sixth Degree sound resource {}", resourcePath(cue));
				return;
			}
			try (BufferedInputStream buffered = new BufferedInputStream(resource);
				 AudioInputStream audio = AudioSystem.getAudioInputStream(buffered))
			{
				Clip clip = AudioSystem.getClip();
				CountDownLatch finished = new CountDownLatch(1);
				try
				{
					clip.addLineListener(event ->
					{
						if (event.getType() == LineEvent.Type.STOP)
						{
							finished.countDown();
						}
					});
					clip.open(audio);
					clip.start();
					long timeoutMillis = Math.max(1_000L, clip.getMicrosecondLength() / 1_000L + 1_000L);
					finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
				}
				finally
				{
					clip.close();
				}
			}
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception error)
		{
			log.debug("Unable to play Sixth Degree sound {}", cue, error);
		}
	}
}
