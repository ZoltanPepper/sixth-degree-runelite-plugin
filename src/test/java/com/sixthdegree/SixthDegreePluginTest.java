package com.sixthdegree;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SixthDegreePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SixthDegreePlugin.class);
		RuneLite.main(args);
	}
}
