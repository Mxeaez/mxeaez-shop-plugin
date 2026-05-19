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
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
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
	private int lastPlayedTick = -1;

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

	void playSqueaky(int currentTick)
	{
		if (currentTick == lastPlayedTick)
		{
			return;
		}
		lastPlayedTick = currentTick;
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
			// Read all bytes first so the stream stays valid after try-with-resources closes
			byte[] bytes = raw.readAllBytes();
			AudioInputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(new ByteArrayInputStream(bytes)));
			AudioFormat format = ais.getFormat();

			// Java only natively plays PCM_SIGNED/PCM_UNSIGNED; convert if needed
			if (!format.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED)
				&& !format.getEncoding().equals(AudioFormat.Encoding.PCM_UNSIGNED))
			{
				float sampleRate = format.getSampleRate() == AudioSystem.NOT_SPECIFIED ? 44100f : format.getSampleRate();
				int channels = format.getChannels() == AudioSystem.NOT_SPECIFIED ? 1 : format.getChannels();
				AudioFormat pcmFormat = new AudioFormat(
					AudioFormat.Encoding.PCM_SIGNED,
					sampleRate,
					16,
					channels,
					channels * 2,
					sampleRate,
					false
				);
				ais = AudioSystem.getAudioInputStream(pcmFormat, ais);
				format = pcmFormat;
			}

			// Normalize amplitude: scale PCM samples so the peak hits ~90% of max
			byte[] pcmBytes = ais.readAllBytes();
			if (format.getSampleSizeInBits() == 16)
			{
				boolean bigEndian = format.isBigEndian();
				short peak = 1;
				for (int i = 0; i + 1 < pcmBytes.length; i += 2)
				{
					short s = bigEndian
						? (short) ((pcmBytes[i] << 8) | (pcmBytes[i + 1] & 0xFF))
						: (short) ((pcmBytes[i + 1] << 8) | (pcmBytes[i] & 0xFF));
					if (Math.abs(s) > peak)
					{
						peak = (short) Math.abs(s);
					}
				}
				float scale = (Short.MAX_VALUE * 0.9f) / peak;
				if (scale > 1.0f)
				{
					for (int i = 0; i + 1 < pcmBytes.length; i += 2)
					{
						short s = bigEndian
							? (short) ((pcmBytes[i] << 8) | (pcmBytes[i + 1] & 0xFF))
							: (short) ((pcmBytes[i + 1] << 8) | (pcmBytes[i] & 0xFF));
						s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) (s * scale)));
						if (bigEndian)
						{
							pcmBytes[i] = (byte) ((s >> 8) & 0xFF);
							pcmBytes[i + 1] = (byte) (s & 0xFF);
						}
						else
						{
							pcmBytes[i] = (byte) (s & 0xFF);
							pcmBytes[i + 1] = (byte) ((s >> 8) & 0xFF);
						}
					}
				}
			}

			AudioInputStream normalizedAis = new AudioInputStream(
				new ByteArrayInputStream(pcmBytes), format, pcmBytes.length / format.getFrameSize());
			clip = AudioSystem.getClip();
			clip.open(normalizedAis);
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
