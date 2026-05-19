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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS `AS IS'' AND
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

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.audio.AudioPlayer;

@Slf4j
@Singleton
public class SqueakyWeaponEffect
{
	@Inject
	private AudioPlayer audioPlayer;

	private volatile long expiresAtMs = 0;
	private int lastPlayedTick = -1;

	void activate(long durationMs)
	{
		expiresAtMs = System.currentTimeMillis() + durationMs;
	}

	boolean isActive()
	{
		return System.currentTimeMillis() < expiresAtMs;
	}

	void clear()
	{
		expiresAtMs = 0;
	}

	void playSqueaky(int currentTick)
	{
		if (currentTick == lastPlayedTick)
		{
			return;
		}
		lastPlayedTick = currentTick;
		try
		{
			audioPlayer.play(SqueakyWeaponEffect.class, "Squeaky_Toy.wav", 0f);
		}
		catch (Exception e)
		{
			log.debug("SqueakyWeapon: failed to play squeaky sound", e);
		}
	}

	void destroy()
	{
		expiresAtMs = 0;
	}
}
