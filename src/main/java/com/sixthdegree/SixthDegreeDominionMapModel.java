package com.sixthdegree;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SixthDegreeDominionMapModel
{
	static final int MODEL_WIDTH = 1000;
	static final int MODEL_HEIGHT = 600;

	private static final List<TerritorySpec> TERRITORIES = Collections.unmodifiableList(Arrays.asList(
		new TerritorySpec("kourend", "Kourend", new int[]{55, 105, 185, 260, 315, 305, 265, 170, 85}, new int[]{105, 60, 42, 58, 98, 165, 210, 220, 190}),
		new TerritorySpec("varlamore", "Varlamore", new int[]{18, 72, 155, 245, 305, 318, 278, 208, 118, 50}, new int[]{260, 215, 220, 242, 282, 350, 425, 445, 425, 360}),
		new TerritorySpec("fremennik", "Fremennik", new int[]{430, 485, 555, 620, 650, 625, 560, 495, 445}, new int[]{76, 48, 45, 70, 125, 178, 208, 198, 158}),
		new TerritorySpec("wilderness", "Wilderness", new int[]{652, 715, 790, 842, 858, 825, 765, 700, 650}, new int[]{72, 42, 46, 86, 145, 190, 205, 185, 128}),
		new TerritorySpec("tirannwn", "Tirannwn", new int[]{328, 377, 425, 456, 450, 420, 372, 336, 311}, new int[]{245, 215, 222, 265, 330, 382, 397, 360, 300}),
		new TerritorySpec("kandarin", "Kandarin", new int[]{468, 520, 580, 626, 636, 612, 560, 505, 458}, new int[]{246, 222, 226, 258, 310, 354, 375, 355, 302}),
		new TerritorySpec("asgarnia", "Asgarnia", new int[]{646, 700, 765, 824, 851, 835, 792, 730, 672, 648}, new int[]{225, 188, 190, 216, 270, 330, 367, 380, 346, 292}),
		new TerritorySpec("morytania", "Morytania", new int[]{842, 900, 948, 980, 982, 950, 900, 850, 825}, new int[]{224, 205, 220, 255, 315, 366, 392, 364, 300}),
		new TerritorySpec("karamja", "Karamja", new int[]{490, 545, 605, 648, 660, 635, 590, 525, 480, 462}, new int[]{396, 370, 382, 420, 478, 525, 550, 532, 490, 438}),
		new TerritorySpec("desert", "Kharidian Desert", new int[]{678, 735, 805, 862, 900, 905, 875, 825, 752, 695, 660}, new int[]{397, 370, 374, 404, 448, 505, 548, 566, 558, 520, 462})
	));

	private static final Map<String, TerritorySpec> BY_ID;

	static
	{
		Map<String, TerritorySpec> byId = new LinkedHashMap<>();
		for (TerritorySpec territory : TERRITORIES)
		{
			byId.put(territory.id, territory);
		}
		BY_ID = Collections.unmodifiableMap(byId);
	}

	private SixthDegreeDominionMapModel()
	{
	}

	static List<TerritorySpec> territories()
	{
		return TERRITORIES;
	}

	static TerritorySpec byId(String regionId)
	{
		return regionId == null ? null : BY_ID.get(regionId.toLowerCase());
	}

	static Polygon polygon(TerritorySpec territory, Rectangle bounds)
	{
		int[] xs = new int[territory.x.length];
		int[] ys = new int[territory.y.length];
		for (int i = 0; i < territory.x.length; i++)
		{
			xs[i] = bounds.x + (int) Math.round((territory.x[i] / (double) MODEL_WIDTH) * bounds.width);
			ys[i] = bounds.y + (int) Math.round((territory.y[i] / (double) MODEL_HEIGHT) * bounds.height);
		}
		return new Polygon(xs, ys, xs.length);
	}

	static Point center(TerritorySpec territory, Rectangle bounds)
	{
		Polygon polygon = polygon(territory, bounds);
		Rectangle box = polygon.getBounds();
		return new Point(box.x + box.width / 2, box.y + box.height / 2);
	}

	static TerritorySpec hitTest(Rectangle bounds, int x, int y)
	{
		for (TerritorySpec territory : TERRITORIES)
		{
			if (polygon(territory, bounds).contains(x, y))
			{
				return territory;
			}
		}
		return null;
	}

	static final class TerritorySpec
	{
		final String id;
		final String name;
		final int[] x;
		final int[] y;

		TerritorySpec(String id, String name, int[] x, int[] y)
		{
			if (id == null || id.isBlank() || name == null || name.isBlank() || x == null || y == null || x.length < 3 || x.length != y.length)
			{
				throw new IllegalArgumentException("Invalid Dominion territory geometry");
			}
			this.id = id;
			this.name = name;
			this.x = x.clone();
			this.y = y.clone();
		}
	}
}
