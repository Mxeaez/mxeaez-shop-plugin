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

import java.util.Random;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

@Slf4j
@Singleton
public class LostBossEffect
{
	/**
	 * { npcId, walkAnimId } pairs.  Use -1 for walkAnimId when no named walk
	 * animation exists — the boss will render in idle pose while moving.
	 * NPC IDs from net.runelite.api.gameval.NpcID.
	 * Animation IDs from net.runelite.api.gameval.AnimationID.
	 */
	private static final int[][] BOSSES = {
		{ 11762,  9649,  9665 }, // Tumeken's Warden
		{  2215,  7016,  7017 }, // General Graardor
		{  2205,  6965,  6966 }, // Commander Zilyana
		{  7221, 10690, 10687 }, // Scurrius
		{  2267,  2849,  2850 }, // Dagannoth Rex
		{ 11278,  9175,  9177 }, // Nex
		{  6619,  2769,  2770 }, // Chaos Fanatic
		{   239,  4635,    90 }, // King Black Dragon
		{ 15626, 13782, 13781 }, // Brutus
		{  7416,  4649,  4650 }, // Obor
		{ 14176, 12141, 12140 }, // Yama
		{ 12204, 10232, 10230 }, // The Whisperer
		{  9035,  8416,  8417 }, // Corrupted Hunllef
		{ 12821, 10878, 10874 }, // Sol Heredit
		{  7541,  7477,  7473 }, // Tekton
		{  8359,  8081,  8080 }, // Pestilent Bloat
		{ 10803,  8341,  8340 }, // Nylocas Vasilias
		{ 11778,  9737,  9741 }, // Ba-Ba
		{ 13662,  1660,   808 }, // Durial321
	};

	/**
	 * Local coordinate units to move per client tick toward the target.
	 * 128 local units = 1 tile. A game tick is ~600 ms; a client tick is ~20 ms,
	 * giving ~30 client ticks per game tick. Moving 128/30 ≈ 4.3 units/client tick
	 * makes the boss cross one tile per game tick — matching NPC walk speed.
	 */
	private static final float MOVE_SPEED = 4.5f;

	/** Game ticks to stand still after arriving at a waypoint (2–3 = 1.2–1.8 s). */
	private static final int PAUSE_MIN = 2;
	private static final int PAUSE_RANGE = 2;

	/** Jagex orientation units to rotate per client tick (2048 = full circle). */
	private static final float ROT_SPEED = 80f;

	@Inject
	private Client client;

	private RuneLiteObject boss = null;
	private final Random random = new Random();
	private int walkAnimId;
	private int idleAnimId;

	// Interpolation state (all in local coordinates)
	private float currentX;
	private float currentY;
	private int targetX;
	private int targetY;
	private boolean hasTarget;
	private int pauseTicksRemaining;
	private float currentOrientation;
	private int targetOrientation;

	/**
	 * Spawn a random boss near the local player.
	 * Must be called on the game thread.
	 */
	public void spawn()
	{
		despawn(); // clean up any leftover boss first

		int[] entry  = BOSSES[random.nextInt(BOSSES.length)];
		int npcId    = entry[0];
		walkAnimId   = entry[1];
		idleAnimId   = entry[2];

		NPCComposition comp = client.getNpcDefinition(npcId);
		if (comp == null)
		{
			log.debug("LostBossEffect: null composition for npcId={}", npcId);
			return;
		}

		int[] modelIds = comp.getModels();
		if (modelIds == null || modelIds.length == 0)
		{
			log.debug("LostBossEffect: no models for npcId={}", npcId);
			return;
		}

		// Load each sub-model from the cache and merge into one
		ModelData[] parts = new ModelData[modelIds.length];
		for (int i = 0; i < modelIds.length; i++)
		{
			parts[i] = client.loadModelData(modelIds[i]);
		}
		ModelData merged = client.mergeModels(parts);
		if (merged == null)
		{
			log.debug("LostBossEffect: failed to merge models for npcId={}", npcId);
			return;
		}

		// Apply the NPC's recolor table so the model looks correct
		short[] colorFrom = comp.getColorToReplace();
		short[] colorTo   = comp.getColorToReplaceWith();
		if (colorFrom != null && colorTo != null && colorFrom.length > 0)
		{
			merged = merged.cloneColors();
			for (int i = 0; i < Math.min(colorFrom.length, colorTo.length); i++)
			{
				merged.recolor(colorFrom[i], colorTo[i]);
			}
		}

		Model model = merged.light();

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		// Spawn 3 tiles east of the player so it's immediately visible
		WorldPoint spawnWp = localPlayer.getWorldLocation().dx(3);
		LocalPoint spawnLp = LocalPoint.fromWorld(client, spawnWp);
		if (spawnLp == null || !spawnLp.isInScene())
		{
			spawnLp = localPlayer.getLocalLocation();
		}

		int plane = client.getTopLevelWorldView().getPlane();
		boss = client.createRuneLiteObject();
		boss.setModel(model);
		if (idleAnimId >= 0)
		{
			boss.setAnimation(client.loadAnimation(idleAnimId));
			boss.setShouldLoop(true);
		}
		boss.setLocation(spawnLp, plane);
		boss.setActive(true);

		// Initialise interpolation at the spawn position
		currentX = spawnLp.getX();
		currentY = spawnLp.getY();
		targetX  = spawnLp.getX();
		targetY  = spawnLp.getY();
		hasTarget = false;
		pauseTicksRemaining = 0;
		currentOrientation = 0;
		targetOrientation  = 0;
		log.debug("LostBossEffect: spawned npcId={}", npcId);
	}

	/**
	 * Manages waypoint selection and pause countdown. Called every game tick.
	 * Actual pixel-level movement is done in {@link #clientTick()} for smoothness.
	 * Must be called on the game thread.
	 */
	public void tick()
	{
		if (boss == null || !boss.isActive())
		{
			return;
		}

		// Still walking toward the current waypoint — nothing to do here
		if (hasTarget)
		{
			return;
		}

		// Arrived at waypoint — count down the standing-still pause
		if (pauseTicksRemaining > 0)
		{
			pauseTicksRemaining--;
			return;
		}

		// Pause is over — pick a new waypoint 3–5 tiles away in a random direction
		LocalPoint approxLp = new LocalPoint(Math.round(currentX), Math.round(currentY), client.getTopLevelWorldView());
		WorldPoint wp = WorldPoint.fromLocal(client, approxLp);

		double angle = random.nextDouble() * 2 * Math.PI;
		int dist  = 3 + random.nextInt(3); // 3, 4, or 5 tiles
		int dx = (int) Math.round(dist * Math.cos(angle));
		int dy = (int) Math.round(dist * Math.sin(angle));
		// Guarantee at least 1 tile of movement on either axis
		if (dx == 0 && dy == 0) { dx = 1; }

		LocalPoint nextLp = LocalPoint.fromWorld(client, wp.dx(dx).dy(dy));
		if (nextLp == null || !nextLp.isInScene())
		{
			// Wander back toward centre if the edge was hit
			nextLp = LocalPoint.fromWorld(client, wp.dx(-dx).dy(-dy));
		}
		if (nextLp == null || !nextLp.isInScene())
		{
			return; // try again next tick
		}

		targetX = nextLp.getX();
		targetY = nextLp.getY();
		hasTarget = true;
		if (walkAnimId >= 0)
		{
			boss.setAnimation(client.loadAnimation(walkAnimId));
			boss.setShouldLoop(true);
		}
		targetOrientation = directionToOrientation(dx, dy);
	}

	/**
	 * Smoothly interpolate toward the current target tile.
	 * Called every client tick (~20 ms) while active.
	 * Must be called on the game thread.
	 */
	public void clientTick()
	{
		if (boss == null || !boss.isActive() || !hasTarget)
		{
			return;
		}

		float dx   = targetX - currentX;
		float dy   = targetY - currentY;
		float dist = (float) Math.sqrt(dx * dx + dy * dy);

		if (dist <= MOVE_SPEED)
		{
			// Arrived — snap to target, update terrain height, start pause, switch to idle
			currentX  = targetX;
			currentY  = targetY;
			hasTarget = false;
			pauseTicksRemaining = PAUSE_MIN + random.nextInt(PAUSE_RANGE);
			if (idleAnimId >= 0)
			{
				boss.setAnimation(client.loadAnimation(idleAnimId));
				boss.setShouldLoop(true);
			}
			LocalPoint lp = new LocalPoint(targetX, targetY, client.getTopLevelWorldView());
			int z = Perspective.getTileHeight(client, lp, boss.getLevel());
			boss.setZ(z);
		}
		else
		{
			currentX += dx / dist * MOVE_SPEED;
			currentY += dy / dist * MOVE_SPEED;
		}

		boss.setX(Math.round(currentX));
		boss.setY(Math.round(currentY));

		// Smooth rotation — shortest path around the 0-2047 circle
		float diff = ((targetOrientation - currentOrientation) % 2048 + 2048) % 2048;
		if (diff > 1024) { diff -= 2048; }
		if (Math.abs(diff) <= ROT_SPEED)
		{
			currentOrientation = targetOrientation;
		}
		else
		{
			currentOrientation = ((currentOrientation + Math.signum(diff) * ROT_SPEED) % 2048 + 2048) % 2048;
		}
		boss.setOrientation(Math.round(currentOrientation));
	}

	/**
	 * Maps a (dx, dy) world-tile delta to a Jagex orientation value (0–2047).
	 * 0 = south, 512 = west, 1024 = north, 1536 = east.
	 */
	private static int directionToOrientation(int dx, int dy)
	{
		if (dx == 0)  return dy > 0 ? 1024 : 0;    // N / S
		if (dy == 0)  return dx > 0 ? 1536 : 512;   // E / W
		// Diagonals
		if (dx > 0)   return dy > 0 ? 1280 : 1792;  // NE / SE
		            return dy > 0 ?  768 :  256;   // NW / SW
	}

	/**
	 * Despawn the boss and free the RuneLiteObject.
	 * Safe to call when nothing is spawned. Must be called on the game thread.
	 */
	public void despawn()
	{
		if (boss != null)
		{
			boss.setActive(false);
			boss = null;
		}
	}

	public boolean isSpawned()
	{
		return boss != null && boss.isActive();
	}
}
