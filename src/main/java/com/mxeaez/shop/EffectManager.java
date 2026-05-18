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

import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Singleton;

/**
 * Tracks which {@link GameEffect}s are currently active.
 * Thread-safe: accessed from the game thread and the overlay render thread.
 */
@Singleton
public class EffectManager
{
	private final ConcurrentHashMap<EffectType, GameEffect> active = new ConcurrentHashMap<>();

	/**
	 * Activates an effect, replacing any existing effect of the same type.
	 * Always called on the game thread.
	 */
	public void activate(GameEffect effect)
	{
		active.put(effect.getType(), effect);
	}

	/** Returns true if this effect type is active and not yet expired. */
	public boolean isActive(EffectType type)
	{
		GameEffect effect = active.get(type);
		return effect != null && !effect.isExpired();
	}

	/**
	 * Returns the active {@link GameEffect} for the given type, or {@code null}
	 * if there is none or it has expired.
	 */
	public GameEffect getActive(EffectType type)
	{
		GameEffect effect = active.get(type);
		if (effect == null || effect.isExpired())
		{
			return null;
		}
		return effect;
	}

	/** Removes all expired effects. Called once per game tick. */
	public void tickAll()
	{
		active.values().removeIf(GameEffect::isExpired);
	}

	/** Clears all effects. Called on plugin shutdown. */
	public void clear()
	{
		active.clear();
	}
}
