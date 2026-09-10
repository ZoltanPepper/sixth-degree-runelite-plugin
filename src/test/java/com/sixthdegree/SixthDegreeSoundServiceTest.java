package com.sixthdegree;

import java.io.BufferedInputStream;
import java.io.InputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.Test;
import static org.junit.Assert.*;

public class SixthDegreeSoundServiceTest
{
	@Test
	public void everyCueHasAValidPcmWavResource() throws Exception
	{
		for (SixthDegreeSoundService.Cue cue : SixthDegreeSoundService.Cue.values())
		{
			String path = SixthDegreeSoundService.resourcePath(cue);
			try (InputStream resource = SixthDegreeSoundService.class.getResourceAsStream(path))
			{
				assertNotNull(path, resource);
				try (AudioInputStream audio = AudioSystem.getAudioInputStream(new BufferedInputStream(resource)))
				{
					AudioFormat format = audio.getFormat();
					assertEquals(path, AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
					assertEquals(path, 44_100.0f, format.getSampleRate(), 0.1f);
					assertEquals(path, 16, format.getSampleSizeInBits());
					assertTrue(path, audio.getFrameLength() > 0L);
				}
			}
		}
	}

	@Test
	public void deathScreenshotPreferencePreservesTheEventIdentity()
	{
		SixthDegreeNotificationEvent original = SixthDegreeNotificationEvent.of(
			"death", "Death", "detail", "source", 0L, true);
		SixthDegreeNotificationEvent withoutScreenshot = original.withScreenshot(false);

		assertFalse(withoutScreenshot.screenshot);
		assertEquals(original.eventId, withoutScreenshot.eventId);
		assertEquals(original.occurredAt, withoutScreenshot.occurredAt);
		assertSame(withoutScreenshot, withoutScreenshot.withScreenshot(false));
	}
}
