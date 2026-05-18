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

import lombok.Data;

/**
 * A single game-effect event received from the mxeaez-shop EBS server.
 */
@Data
public class GameEffect
{
	/** Server-assigned unique ID for deduplication. */
	private String id;

	/** The type of effect to apply. */
	private EffectType type;

	/**
	 * Optional string parameter for this effect. Interpretation depends on type:
	 * <ul>
	 *   <li>SCREEN_FLASH   — hex color, e.g. {@code "#ff0000"}</li>
	 *   <li>NPC_RENAME     — {@code "target:replacement"}, e.g. {@code "Kephri:Brody"}</li>
	 *   <li>SOUND_EFFECT   — OSRS sound ID as a string, e.g. {@code "3813"}</li>
	 *   <li>CHAT_MESSAGE   — message text</li>
	 * </ul>
	 */
	private String param;

	/** How long the effect should last in milliseconds. */
	private long durationMs;

	/**
	 * Unix timestamp (ms) from the server when this effect was triggered.
	 * Used to compute expiry relative to the server clock.
	 */
	private long triggeredAt;

	/** Display name of the viewer who triggered this effect, for chat messages. */
	private String viewer;

	boolean isExpired()
	{
		return System.currentTimeMillis() > triggeredAt + durationMs;
	}
}
