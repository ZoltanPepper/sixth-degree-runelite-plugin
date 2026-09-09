package com.sixthdegree;

import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SixthDegreeDominionMapModelTest
{
	private static final Set<String> CANONICAL = Set.of(
		"varlamore",
		"karamja",
		"asgarnia",
		"fremennik",
		"kandarin",
		"desert",
		"morytania",
		"tirannwn",
		"wilderness",
		"kourend"
	);

	@Test
	public void hasExactlyTheTenDominionTerritories()
	{
		assertEquals(10, SixthDegreeDominionMapModel.territories().size());
		assertEquals(
			CANONICAL,
			SixthDegreeDominionMapModel.territories().stream()
				.map(spec -> spec.id)
				.collect(Collectors.toSet()));
	}

	@Test
	public void everyTerritoryCenterCanBeHitTested()
	{
		Rectangle bounds = new Rectangle(40, 60, 1000, 600);
		for (SixthDegreeDominionMapModel.TerritorySpec spec : SixthDegreeDominionMapModel.territories())
		{
			Point center = SixthDegreeDominionMapModel.center(spec, bounds);
			SixthDegreeDominionMapModel.TerritorySpec hit = SixthDegreeDominionMapModel.hitTest(bounds, center.x, center.y);
			assertNotNull(spec.id + " center should hit a territory", hit);
			assertEquals(spec.id, hit.id);
		}
	}

	@Test
	public void scaledTerritoryPolygonsStayInsideMapBounds()
	{
		Rectangle bounds = new Rectangle(17, 23, 713, 419);
		for (SixthDegreeDominionMapModel.TerritorySpec spec : SixthDegreeDominionMapModel.territories())
		{
			Polygon polygon = SixthDegreeDominionMapModel.polygon(spec, bounds);
			for (int i = 0; i < polygon.npoints; i++)
			{
				assertTrue(polygon.xpoints[i] >= bounds.x);
				assertTrue(polygon.xpoints[i] <= bounds.x + bounds.width);
				assertTrue(polygon.ypoints[i] >= bounds.y);
				assertTrue(polygon.ypoints[i] <= bounds.y + bounds.height);
			}
		}
	}
}
