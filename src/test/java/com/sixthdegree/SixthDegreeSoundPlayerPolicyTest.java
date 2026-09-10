package com.sixthdegree;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SixthDegreeSoundPlayerPolicyTest
{
	@Test
	public void soundServiceUsesRuneLiteAudioPlayerInsteadOfDirectJavaxSound() throws Exception
	{
		String resource = "/com/sixthdegree/SixthDegreeSoundService.class";
		assertNotNull(SixthDegreeSoundService.class.getResourceAsStream(resource));

		String source = new String(
			SixthDegreeSoundPlayerPolicyTest.class.getResourceAsStream(
				"/com/sixthdegree/SixthDegreeSoundService.class").readAllBytes(),
			StandardCharsets.ISO_8859_1);

		assertTrue(source.contains("net/runelite/client/audio/AudioPlayer"));
		assertFalse(source.contains("javax/sound/sampled"));
	}
}
