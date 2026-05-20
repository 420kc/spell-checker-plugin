package com.spellchecker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SpellcheckerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		@SuppressWarnings("unchecked")
		Class<? extends net.runelite.client.plugins.Plugin>[] plugins = new Class[]{SpellcheckerPlugin.class};
		ExternalPluginManager.loadBuiltin(plugins);
		RuneLite.main(args);
	}
}
