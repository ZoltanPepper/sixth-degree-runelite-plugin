package com.sixthdegree;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SixthDegreeNotificationLoggingTest
{
	@Test
	public void oversizedScreenshotFailureIsNotRetried()
	{
		assertTrue(SixthDegreeNotificationCoordinator.isPermanentNotificationFailure(413));
		assertTrue(SixthDegreeNotificationCoordinator.isPermanentNotificationFailure(409));
		assertFalse(SixthDegreeNotificationCoordinator.isPermanentNotificationFailure(500));
	}

	@Test
	public void normalClientLogContainsClearNotificationStages() throws Exception
	{
		String coordinatorResource = "/com/sixthdegree/SixthDegreeNotificationCoordinator.class";
		assertNotNull(SixthDegreeNotificationCoordinator.class.getResourceAsStream(coordinatorResource));
		String coordinator = new String(
			SixthDegreeNotificationCoordinator.class.getResourceAsStream(coordinatorResource).readAllBytes(),
			StandardCharsets.ISO_8859_1);

		assertTrue(coordinator.contains("Sixth Degree notification detected"));
		assertTrue(coordinator.contains("Sixth Degree notification sent"));
		assertTrue(coordinator.contains("Sixth Degree screenshot upload rejected"));
		assertTrue(coordinator.contains("Sixth Degree notification transport failed"));

		String screenshotResource = "/com/sixthdegree/SixthDegreeScreenshotService.class";
		assertNotNull(SixthDegreeScreenshotService.class.getResourceAsStream(screenshotResource));
		String screenshot = new String(
			SixthDegreeScreenshotService.class.getResourceAsStream(screenshotResource).readAllBytes(),
			StandardCharsets.ISO_8859_1);
		assertTrue(screenshot.contains("Sixth Degree screenshot captured"));
	}
}
