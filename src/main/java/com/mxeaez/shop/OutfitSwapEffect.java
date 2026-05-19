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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.kit.KitType;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;

/**
 * Visually overrides the local player's equipment appearance for a fixed duration.
 *
 * <p>The viewer sends a JSON param such as {@code {"head":4552,"weapon":4151,"legs":4553}}.
 * Only slots present in the map are overridden; unspecified slots keep their current look.
 * The original slot values are captured at activation and restored on expiry or shutdown.</p>
 *
 * <p>All methods must be called on the client thread.</p>
 */
@Slf4j
@Singleton
class OutfitSwapEffect
{
	@Inject
	private Client client;

	@Inject
	private Gson gson;

	/**
	 * Maps equipment.json slot name → KitType for slots that actually affect the 3D model.
	 * Ring and ammo are excluded — they have no visual representation on PlayerComposition.
	 */
	private static final Map<String, KitType> SLOT_TO_KIT;
	static
	{
		Map<String, KitType> m = new HashMap<>();
		m.put("head",   KitType.HEAD);
		m.put("cape",   KitType.CAPE);
		m.put("neck",   KitType.AMULET);
		m.put("weapon", KitType.WEAPON);
		m.put("body",   KitType.TORSO);
		m.put("shield", KitType.SHIELD);
		m.put("legs",   KitType.LEGS);
		m.put("hands",  KitType.HANDS);
		m.put("feet",   KitType.BOOTS);
		SLOT_TO_KIT = Collections.unmodifiableMap(m);
	}

	/** Slot name → item id to display (from the viewer's JSON param). */
	private Map<String, Integer> overrideMap = new HashMap<>();

	/**
	 * Full kit array captured at first activation — fallback only if {@link #currentActualState}
	 * was never populated by {@code onPlayerChanged}.
	 */
	private int[] originalIds = null;

	/**
	 * The player's REAL kit array as last sent by the server, stored in
	 * {@link #applyNow} before we overlay our transmog.  Used to restore
	 * exactly what the server knows on expiry — correctly handles bare-skin
	 * kit slots that have no matching ItemContainer entry.
	 */
	private int[] currentActualState = null;

	private long expiresAtMs = 0;
	private boolean active = false;

	/**
	 * Parses the JSON param and activates the outfit override.
	 *
	 * @param paramJson  JSON string like {@code {"head":4552,"weapon":4151}}
	 * @param durationMs how long the outfit should last
	 */
	void activate(String paramJson, long durationMs)
	{
		Map<String, Integer> incoming = gson.fromJson(
			paramJson, new TypeToken<Map<String, Integer>>() {}.getType());
		if (incoming == null || incoming.isEmpty())
		{
			log.warn("OutfitSwap: empty or unparseable param: {}", paramJson);
			return;
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return;
		}

		// On first activation, snapshot the full kit array as both the fallback
		// (originalIds) and the initial real-state cache (currentActualState).
		// On re-activation while already active, leave both intact — they track
		// the player's real gear, not a mid-swap snapshot.
		if (!active)
		{
			originalIds       = Arrays.copyOf(comp.getEquipmentIds(), comp.getEquipmentIds().length);
			currentActualState = Arrays.copyOf(originalIds, originalIds.length);
		}
		overrideMap = new HashMap<>(incoming);
		expiresAtMs = System.currentTimeMillis() + durationMs;
		active = true;
		log.debug("OutfitSwap: {} slots for {}ms (re-activate={})", overrideMap.size(), durationMs, active);
	}

	boolean isActive()
	{
		return active;
	}

	/**
	 * Called from {@code onPlayerChanged} (priority=1) whenever the server sends a fresh
	 * {@link PlayerComposition} for the local player.  Stores the REAL kit state before
	 * re-applying the transmog overlay, so expiry can restore exactly what the server knows.
	 *
	 * <p>When the body slot is overridden the arms kit is forced to 0 to prevent the bare-arms
	 * model from clipping through the transmog armour's own shoulder geometry.</p>
	 */
	void applyNow(Player player)
	{
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return;
		}
		int[] kits = comp.getEquipmentIds();

		// Always capture the real state the server just sent (before our overlay).
		currentActualState = kits.clone();

		if (!active)
		{
			return;
		}

		// Only write and mark dirty when the value actually changes — avoids calling
		// setHash() for gear changes that don't touch any overridden slot (e.g. equipping
		// boots while only the chest slot is transmog'd).  Unnecessary setHash() calls
		// trigger model rebuilds that produce a visible single-frame flicker.
		boolean dirty = false;
		for (Map.Entry<String, Integer> entry : overrideMap.entrySet())
		{
			KitType kit = SLOT_TO_KIT.get(entry.getKey());
			if (kit == null)
			{
				continue;
			}
			int idx = kit.getIndex();
			if (idx < kits.length)
			{
				int newVal = entry.getValue() + PlayerComposition.ITEM_OFFSET;
				if (kits[idx] != newVal)
				{
					kits[idx] = newVal;
					dirty = true;
				}
			}
		}
		// Prevent bare-arms kit from clipping through body armour geometry.
		if (overrideMap.containsKey("body"))
		{
			int armsIdx = KitType.ARMS.getIndex();
			if (armsIdx < kits.length && kits[armsIdx] != 0)
			{
				kits[armsIdx] = 0;
				dirty = true;
			}
		}
		if (dirty)
		{
			comp.setHash();
		}
	}

	/**
	 * Called every client tick.  Applies or reverts the outfit override.
	 */
	void tick()
	{
		if (!active)
		{
			return;
		}

		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return;
		}

		int[] ids = comp.getEquipmentIds();
		boolean dirty = false;

		if (System.currentTimeMillis() >= expiresAtMs)
		{
			// Restore the exact kit state the server last sent us.  Using
			// currentActualState (updated each onPlayerChanged) means un-equipping
			// during the effect is correctly reflected — no phantom restore of
			// activation-time gear, and no zeroed-out slots causing invisible body parts.
			int[] restore = currentActualState != null ? currentActualState : originalIds;
			if (restore != null)
			{
				System.arraycopy(restore, 0, ids, 0, ids.length);
			}
			active             = false;
			overrideMap.clear();
			originalIds        = null;
			currentActualState = null;
			dirty = true;
			log.debug("OutfitSwap: expired, reverted to last real state");
		}
		else
		{
			// Re-apply each tick as a safety net (applyNow handles the reactive path).
			// Only write when the value has actually changed — same guard as applyNow().
			for (Map.Entry<String, Integer> entry : overrideMap.entrySet())
			{
				KitType kit = SLOT_TO_KIT.get(entry.getKey());
				if (kit == null)
				{
					continue;
				}
				int idx = kit.getIndex();
				if (idx < ids.length)
				{
					int newVal = entry.getValue() + PlayerComposition.ITEM_OFFSET;
					if (ids[idx] != newVal)
					{
						ids[idx] = newVal;
						dirty = true;
					}
				}
			}
			// Prevent arms clipping when body slot is overridden.
			if (overrideMap.containsKey("body"))
			{
				int armsIdx = KitType.ARMS.getIndex();
				if (armsIdx < ids.length && ids[armsIdx] != 0)
				{
					ids[armsIdx] = 0;
					dirty = true;
				}
			}
		}

		if (dirty)
		{
			comp.setHash();
		}
	}

	/**
	 * Immediately reverts all overrides.  Called on plugin shutdown.
	 */
	void clear()
	{
		if (!active)
		{
			return;
		}

		Player player = client.getLocalPlayer();
		if (player != null)
		{
			PlayerComposition comp = player.getPlayerComposition();
			if (comp != null)
			{
				int[] ids     = comp.getEquipmentIds();
				int[] restore = currentActualState != null ? currentActualState : originalIds;
				if (restore != null)
				{
					System.arraycopy(restore, 0, ids, 0, ids.length);
					comp.setHash();
				}
			}
		}

		active             = false;
		overrideMap.clear();
		originalIds        = null;
		currentActualState = null;
		log.debug("OutfitSwap: cleared");
	}
}
