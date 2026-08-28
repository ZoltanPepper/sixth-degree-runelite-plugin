package com.sixthdegree;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.DrawManager;

@Singleton
final class SixthDegreeScreenshotService
{
	private final DrawManager drawManager;

	@Inject
	SixthDegreeScreenshotService(DrawManager drawManager)
	{
		this.drawManager = drawManager;
	}

	CompletableFuture<byte[]> capturePng()
	{
		CompletableFuture<byte[]> future = new CompletableFuture<>();
		drawManager.requestNextFrameListener(image -> CompletableFuture.runAsync(() ->
		{
			try
			{
				future.complete(toPng(image));
			}
			catch (Exception e)
			{
				future.completeExceptionally(e);
			}
		}));
		return future;
	}

	private static byte[] toPng(Image image) throws Exception
	{
		if (image == null)
		{
			throw new IllegalStateException("RuneLite returned no screenshot frame");
		}

		BufferedImage buffered;
		if (image instanceof BufferedImage)
		{
			buffered = (BufferedImage) image;
		}
		else
		{
			int width = Math.max(1, image.getWidth(null));
			int height = Math.max(1, image.getHeight(null));
			buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = buffered.createGraphics();
			try
			{
				graphics.drawImage(image, 0, 0, null);
			}
			finally
			{
				graphics.dispose();
			}
		}

		ByteArrayOutputStream output = new ByteArrayOutputStream(1024 * 512);
		if (!ImageIO.write(buffered, "png", output))
		{
			throw new IllegalStateException("No PNG writer is available");
		}
		return output.toByteArray();
	}
}
