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

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.ExecutorServiceExceptionLogger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Singleton
public class SqueakyWeaponEffect
{
	private volatile long expiresAtMs = 0;
	private Clip clip;

	void activate(long durationMs)
	{
		expiresAtMs = System.currentTimeMillis() + durationMs;
		loadClipIfNeeded();
	}

	boolean isActive()
	{
		return System.currentTimeMillis() < expiresAtMs;
	}

	void clear()
	{
		expiresAtMs = 0;
	}

	void playSqueaky()
	{
		if (clip == null)
		{
			loadClipIfNeeded();
		}
		if (clip == null)
		{
			return;
		}
		try
		{
			clip.stop();
			clip.setFramePosition(0);
			clip.start();
		}
		catch (Exception e)
		{
			log.debug("SqueakyWeapon: failed to play clip", e);
		}
	}

	private void loadClipIfNeeded()
	{
		if (clip != null && clip.isOpen())
		{
			return;
		}
		try (InputStream raw = SqueakyWeaponEffect.class.getResourceAsStream("Squeaky_Toy.wav"))
		{
			if (raw == null)
			{
				log.warn("SqueakyWeapon: Squeaky_Toy.wav not found in resources");
				return;
			}
			AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
			clip = AudioSystem.getClip();
			clip.open(ais);
		}
		catch (UnsupportedAudioFileException | IOException | LineUnavailableException e)
		{
			log.warn("SqueakyWeapon: failed to load Squeaky_Toy.wav", e);
			clip = null;
		}
	}

	void destroy()
	{
		expiresAtMs = 0;
		if (clip != null)
		{
			clip.close();
			clip = null;
		}
	}
}
