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

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayUtil;

public class MxeaezShopOverlay extends Overlay
{
	private static final Color LIGHTS_OUT_COLOR = new Color(0, 0, 0, 210);
	private static final Color FLASH_FALLBACK_COLOR = new Color(255, 0, 0, 140);
	private static final Color NPC_LABEL_COLOR = Color.YELLOW;

	private final Client client;
	private final EffectManager effectManager;
	private final NpcRenameEffect npcRenameEffect;

	@Inject
	private MxeaezShopOverlay(Client client, EffectManager effectManager, NpcRenameEffect npcRenameEffect)
	{
		this.client = client;
		this.effectManager = effectManager;
		this.npcRenameEffect = npcRenameEffect;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(OverlayPriority.HIGHEST);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// --- Fullscreen effects ---
		if (effectManager.isActive(EffectType.LIGHTS_OUT))
		{
			fillScreen(graphics, LIGHTS_OUT_COLOR);
		}
		else if (effectManager.isActive(EffectType.SCREEN_FLASH))
		{
			GameEffect flash = effectManager.getActive(EffectType.SCREEN_FLASH);
			Color color = parseHexColor(flash != null ? flash.getParam() : null, FLASH_FALLBACK_COLOR);
			fillScreen(graphics, color);
		}

		// --- NPC rename labels (persistent across sessions) ---
		if (!npcRenameEffect.isEmpty())
		{
			for (java.util.Map.Entry<String, String> entry : npcRenameEffect.getAll().entrySet())
			{
				renderNpcLabels(graphics, entry.getKey(), entry.getValue());
			}
		}

		return null;
	}

	private void renderNpcLabels(Graphics2D graphics, String targetName, String replacement)
	{
		List<NPC> npcs = client.getNpcs();
		for (NPC npc : npcs)
		{
			NPCComposition comp = npc.getTransformedComposition();
			if (comp == null)
			{
				continue;
			}
			String npcName = comp.getName();
			if (npcName != null && npcName.toLowerCase().equals(targetName))
			{
				Point textLocation = npc.getCanvasTextLocation(graphics, replacement, npc.getLogicalHeight() + 40);
				if (textLocation != null)
				{
					OverlayUtil.renderTextLocation(graphics, textLocation, replacement, NPC_LABEL_COLOR);
				}
			}
		}
	}

	private void fillScreen(Graphics2D graphics, Color color)
	{
		Rectangle bounds = graphics.getClipBounds();
		if (bounds == null)
		{
			return;
		}
		graphics.setColor(color);
		graphics.fill(bounds);
	}

	private Color parseHexColor(String hex, Color fallback)
	{
		if (hex == null || hex.isBlank())
		{
			return fallback;
		}
		try
		{
			return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}
}
