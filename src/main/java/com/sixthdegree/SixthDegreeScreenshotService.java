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
		drawManager.requestNextFrameListener(image ->
		{
			final BufferedImage snapshot;
			try
			{
				// DrawManager's frame belongs to RuneLite's render pipeline. Copy it while
				// we're still inside the callback so a later render cannot mutate the image
				// while PNG encoding runs on a background thread.
				snapshot = copyFrame(image);
			}
			catch (Exception e)
			{
				future.completeExceptionally(e);
				return;
			}

			CompletableFuture.runAsync(() ->
			{
				try
				{
					future.complete(toPng(snapshot));
				}
				catch (Exception e)
				{
					future.completeExceptionally(e);
				}
			});
		});
		return future;
	}

	private static BufferedImage copyFrame(Image image)
	{
		if (image == null)
		{
			throw new IllegalStateException("RuneLite returned no screenshot frame");
		}
		int width = Math.max(1, image.getWidth(null));
		int height = Math.max(1, image.getHeight(null));
		BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = copy.createGraphics();
		try
		{
			graphics.drawImage(image, 0, 0, null);
		}
		finally
		{
			graphics.dispose();
		}
		return copy;
	}

	private static byte[] toPng(BufferedImage buffered) throws Exception
	{
		ByteArrayOutputStream output = new ByteArrayOutputStream(1024 * 512);
		if (!ImageIO.write(buffered, "png", output))
		{
			throw new IllegalStateException("No PNG writer is available");
		}
		return output.toByteArray();
	}
}
