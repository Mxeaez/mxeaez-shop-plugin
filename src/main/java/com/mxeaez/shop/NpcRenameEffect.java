/*
 * Copyright (c) 2026, mxeaez
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.mxeaez.shop;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Manages the persistent NPC rename map.
 * Entries survive plugin restarts: they are serialised to RuneLite's ConfigManager
 * under the key "npcRenameMap" and reloaded on startUp.
 * Multiple viewers can contribute renames for different NPCs simultaneously.
 */
@Slf4j
@Singleton
public class NpcRenameEffect
{
	private static final String CONFIG_GROUP = "mxeaezshop";
	private static final String CONFIG_KEY   = "npcRenameMap";

	/** Map of lowercased NPC name → display replacement */
	private final Map<String, String> renames = new LinkedHashMap<>();

	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	/** Called from MxeaezShopPlugin.startUp() */
	void load()
	{
		renames.clear();
		String json = configManager.getConfiguration(CONFIG_GROUP, CONFIG_KEY);
		if (json == null || json.isBlank())
		{
			return;
		}
		try
		{
			Type mapType = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
			Map<String, String> loaded = gson.fromJson(json, mapType);
			if (loaded != null)
			{
				renames.putAll(loaded);
				log.debug("NpcRenameEffect: loaded {} entries", renames.size());
			}
		}
		catch (Exception e)
		{
			log.debug("NpcRenameEffect: failed to load persistent map", e);
		}
	}

	/** Add or overwrite a rename entry and persist immediately. */
	void put(String targetName, String newName)
	{
		renames.put(targetName.toLowerCase(), newName);
		save();
	}

	/** Read-only view used by the overlay and menu-entry hook. */
	Map<String, String> getAll()
	{
		return Collections.unmodifiableMap(renames);
	}

	boolean isEmpty()
	{
		return renames.isEmpty();
	}

	private void save()
	{
		configManager.setConfiguration(CONFIG_GROUP, CONFIG_KEY, gson.toJson(renames));
	}
}
