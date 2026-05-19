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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.client.game.ItemManager;

/**
 * Manages one or more concurrent item-swap visual overrides on the local player.
 *
 * <p>Each swap is keyed by {@code fromItemId}.  Redeeming the same "from" item
 * again simply replaces the existing entry (same-from overrides cancel each other).
 * Different "from" items stack independently with their own 60-second timers.</p>
 *
 * <p>{@link #tick()} must be called every game tick.  It:</p>
 * <ul>
 *   <li>Re-applies active swaps — if the player re-equips the {@code fromItem}
 *       after previously unequipping it, the visual override is restored.</li>
 *   <li>Reverts expired swaps — only touches a slot if it still shows the
 *       {@code toItem}; if the player already changed equipment, the slot is
 *       left alone (no phantom restores).</li>
 * </ul>
 *
 * <p>All methods must be called on the client thread.</p>
 */
@Slf4j
@Singleton
class ItemSwapEffect
{
	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	private static class Entry
	{
		final int fromItemId;
		final int toItemId;
		final long expiresAtMs;

		Entry(int from, int to, long durationMs)
		{
			this.fromItemId  = from;
			this.toItemId    = to;
			this.expiresAtMs = System.currentTimeMillis() + durationMs;
		}

		boolean isExpired()
		{
			return System.currentTimeMillis() >= expiresAtMs;
		}
	}

	/** Active swaps keyed by fromItemId — same-from redeems replace each other. */
	private final Map<Integer, Entry> entries = new LinkedHashMap<>();

	/**
	 * Adds (or replaces) a swap entry.
	 *
	 * @param fromItemId item currently equipped whose visual should be replaced
	 * @param toItemId   item whose visual should appear instead
	 * @param durationMs how long the swap should last
	 */
	void activate(int fromItemId, int toItemId, long durationMs)
	{
		entries.put(fromItemId, new Entry(fromItemId, toItemId, durationMs));
		log.debug("ItemSwap: activate {} -> {} for {}ms", fromItemId, toItemId, durationMs);
	}

	/**
	 * Called every game tick.  Re-applies active swaps (handles equip/unequip
	 * cycles) and reverts any entries whose timer has elapsed.
	 */
	void tick()
	{
		if (entries.isEmpty())
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

		int[] ids     = comp.getEquipmentIds();
		boolean dirty = false;
		List<Integer> toRemove = new ArrayList<>();

		for (Entry e : entries.values())
		{
			if (e.isExpired())
			{
				// Revert: only touch slots that still show the swap target
				for (int i = 0; i < ids.length; i++)
				{
					if (ids[i] == e.toItemId + PlayerComposition.ITEM_OFFSET)
					{
						ids[i] = e.fromItemId + PlayerComposition.ITEM_OFFSET;
						dirty = true;
						log.debug("ItemSwap: expired — reverted slot {} {} -> {}", i, e.toItemId, e.fromItemId);
					}
				}
				toRemove.add(e.fromItemId);
			}
			else
			{
				// Apply: replace any fromItemId slot with toItemId (canonicalize for variant-item support)
				for (int i = 0; i < ids.length; i++)
				{
					int raw = ids[i] - PlayerComposition.ITEM_OFFSET;
					if (raw >= 0 && itemManager.canonicalize(raw) == itemManager.canonicalize(e.fromItemId))
					{
						ids[i] = e.toItemId + PlayerComposition.ITEM_OFFSET;
						dirty = true;
					}
				}
			}
		}

		toRemove.forEach(entries::remove);

		if (dirty)
		{
			comp.setHash();
		}
	}

	/**
	 * Applies all active (non-expired) swaps to the given player immediately.
	 * Called from {@code onPlayerChanged} (priority=1) so the swap takes effect
	 * the instant the server sends a fresh {@link PlayerComposition}, before
	 * any other plugin or the next game-tick can render the real item.
	 *
	 * <p>Uses {@link ItemManager#canonicalize} so cosmetic variants (e.g.
	 * ornament-kit versions of an item) are treated as the same base item.</p>
	 */
	void applyNow(Player player)
	{
		if (entries.isEmpty())
		{
			return;
		}
		PlayerComposition comp = player.getPlayerComposition();
		if (comp == null)
		{
			return;
		}
		int[] ids     = comp.getEquipmentIds();
		boolean dirty = false;
		for (Entry e : entries.values())
		{
			if (!e.isExpired())
			{
				for (int i = 0; i < ids.length; i++)
				{
					int raw = ids[i] - PlayerComposition.ITEM_OFFSET;
					if (raw >= 0 && itemManager.canonicalize(raw) == itemManager.canonicalize(e.fromItemId))
					{
						ids[i] = e.toItemId + PlayerComposition.ITEM_OFFSET;
						dirty = true;
						log.debug("ItemSwap: onPlayerChanged — applied {} -> {} at slot {}", e.fromItemId, e.toItemId, i);
					}
				}
			}
		}
		if (dirty)
		{
			comp.setHash();
		}
	}

	/**
	 * Reverts all active swaps and clears the entry map.  Called on plugin
	 * shutdown so the player model is restored immediately.
	 */
	void clear()
	{
		if (entries.isEmpty())
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
				boolean dirty = false;
				for (Entry e : entries.values())
				{
					for (int i = 0; i < ids.length; i++)
					{
						if (ids[i] == e.toItemId + PlayerComposition.ITEM_OFFSET)
						{
							ids[i] = e.fromItemId + PlayerComposition.ITEM_OFFSET;
							dirty = true;
						}
					}
				}
				if (dirty)
				{
					comp.setHash();
				}
			}
		}

		entries.clear();
		log.debug("ItemSwap: cleared all entries");
	}
}
