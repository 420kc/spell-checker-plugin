package com.spellchecker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;

public class SpellcheckerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		java.util.List<Class<? extends Plugin>> plugins = new java.util.ArrayList<>();
		plugins.add(SpellcheckerPlugin.class);
		// Sibling plugins for dev-session detection — conditional, ignored if absent.
		tryLoad(plugins, "com.rank1.Rank1Plugin");
		tryLoad(plugins, "com.fourtwentykc.FourTwentyKcPlugin");
		tryLoad(plugins, "com.killclog.KillClogPlugin");
		@SuppressWarnings("unchecked")
		Class<? extends Plugin>[] arr = plugins.toArray(new Class[0]);
		ExternalPluginManager.loadBuiltin(arr);
		RuneLite.main(args);
	}

	private static void tryLoad(java.util.List<Class<? extends Plugin>> list, String className)
	{
		try
		{
			@SuppressWarnings("unchecked")
			Class<? extends Plugin> cls = (Class<? extends Plugin>) Class.forName(className);
			list.add(cls);
		}
		catch (ClassNotFoundException ignored)
		{
		}
	}
}
