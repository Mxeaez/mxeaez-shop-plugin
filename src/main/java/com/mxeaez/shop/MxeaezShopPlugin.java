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
import com.google.inject.Provides;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.AreaSoundEffectPlayed;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.client.util.Text;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@Slf4j
@PluginDescriptor(
	name = "Mxeaez Shop",
	description = "In-game effects triggered by mxeaez-shop channel point redeems",
	tags = {"twitch", "shop", "effects", "overlay", "mxeaez"}
)
public class MxeaezShopPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private MxeaezShopConfig config;

	@Inject
	private EffectManager effectManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Gson gson;

	@Inject
	private LostBossEffect lostBossEffect;

	@Inject
	private ItemSwapEffect itemSwapEffect;

	@Inject
	private SqueakyWeaponEffect squeakyWeaponEffect;

	@Inject
	private NpcRenameEffect npcRenameEffect;

	@Inject
	private OutfitSwapEffect outfitSwapEffect;

	private WebSocket pluginSocket = null;
	private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
	private volatile long reconnectDelayMs = 1_000;
	private volatile boolean shuttingDown = false;

	@Override
	protected void startUp()
	{
		shuttingDown = false;
		npcRenameEffect.load();
		connectWebSocket();
		log.debug("Mxeaez Shop started");
	}

	@Override
	protected void shutDown()
	{
		shuttingDown = true;
		if (pluginSocket != null)
		{
			pluginSocket.close(1000, "Plugin shut down");
			pluginSocket = null;
		}
		effectManager.clear();
		lostBossEffect.despawn();
		itemSwapEffect.clear();
		squeakyWeaponEffect.destroy();
		outfitSwapEffect.clear();
		log.debug("Mxeaez Shop stopped");
	}

	private void connectWebSocket()
	{
		String baseUrl = config.serverUrl();
		String apiKey  = config.apiKey();
		if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank())
		{
			log.debug("WebSocket: server URL or API key not configured");
			return;
		}

		String wsUrl = baseUrl.replaceAll("/+$", "")
			.replaceFirst("^http://", "ws://")
			.replaceFirst("^https://", "wss://")
			+ "/plugin/ws";

		Request request = new Request.Builder()
			.url(wsUrl)
			.header("Authorization", "Bearer " + apiKey)
			.build();

		pluginSocket = okHttpClient.newWebSocket(request, new WebSocketListener()
		{
			@Override
			public void onOpen(WebSocket webSocket, Response response)
			{
				reconnectDelayMs = 1_000;
				log.debug("Plugin WebSocket connected to {}", wsUrl);
			}

			@Override
			public void onMessage(WebSocket webSocket, String text)
			{
				try
				{
					GameEffect effect = gson.fromJson(text, GameEffect.class);
					clientThread.invokeLater(() -> applyEffect(effect));
				}
				catch (Exception e)
				{
					log.debug("WebSocket message parse error: {}", e.getMessage());
				}
			}

			@Override
			public void onFailure(WebSocket webSocket, Throwable t, Response response)
			{
				log.debug("Plugin WebSocket failure: {}", t.getMessage());
				scheduleReconnect();
			}

			@Override
			public void onClosed(WebSocket webSocket, int code, String reason)
			{
				log.debug("Plugin WebSocket closed: {} {}", code, reason);
				if (!shuttingDown)
				{
					scheduleReconnect();
				}
			}
		});
	}

	private void scheduleReconnect()
	{
		if (shuttingDown)
		{
			return;
		}
		long delay = reconnectDelayMs;
		reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000);
		log.debug("Plugin WebSocket reconnecting in {}ms", delay);
		reconnectExecutor.schedule(this::connectWebSocket, delay, TimeUnit.MILLISECONDS);
	}

	// -----------------------------------------------------------------------
	// Effect dispatch
	// -----------------------------------------------------------------------

	private void applyEffect(GameEffect effect)
	{
		if (effect.getType() == null)
		{
			return;
		}
		log.debug("Applying effect {} from viewer={}", effect.getType(), effect.getViewer());

		switch (effect.getType())
		{
			case NPC_RENAME:
				applyNpcRename(effect);
				break;
			case LOST_BOSS:
				effectManager.activate(effect);
				lostBossEffect.spawn();
				break;
			case ITEM_SWAP:
				applyItemSwap(effect);
				break;
			case SQUEAKY_WEAPON:
				squeakyWeaponEffect.activate(effect.getDurationMs());
				break;
			case OUTFIT_SWAP:
				outfitSwapEffect.activate(effect.getParam(), effect.getDurationMs());
				break;
			case SOUND_EFFECT:
				playSoundEffect(effect);
				break;
			case CHAT_MESSAGE:
				showChatMessage(effect);
				break;
		}
	}

	private void applyNpcRename(GameEffect effect)
	{
		String param = effect.getParam();
		if (param == null || !param.contains(":"))
		{
			log.debug("NpcRename: invalid param '{}'", param);
			return;
		}
		String[] parts = param.split(":", 2);
		String targetName = parts[0].trim();
		String newName    = parts[1].trim();
		if (targetName.isEmpty() || newName.isEmpty())
		{
			return;
		}
		npcRenameEffect.put(targetName, newName);
		log.debug("NpcRename: '{}' -> '{}'", targetName, newName);
	}

	private void applyItemSwap(GameEffect effect)
	{
		String param = effect.getParam();
		if (param == null || !param.contains(":"))
		{
			log.debug("ItemSwap: invalid param '{}'", param);
			return;
		}
		try
		{
			String[] parts = param.split(":", 2);
			int fromItemId = Integer.parseInt(parts[0].trim());
			int toItemId   = Integer.parseInt(parts[1].trim());
			itemSwapEffect.activate(fromItemId, toItemId, effect.getDurationMs());
		}
		catch (NumberFormatException e)
		{
			log.debug("ItemSwap: bad item IDs in param '{}'", param);
		}
	}

	private void showChatMessage(GameEffect effect)
	{
		String viewer = effect.getViewer() != null ? effect.getViewer() : "Someone";
		String text = effect.getParam() != null ? effect.getParam() : "???";
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("[Shop] ")
			.append(ChatColorType.NORMAL)
			.append(viewer + ": " + text)
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	private void playSoundEffect(GameEffect effect)
	{
		if (effect.getParam() == null)
		{
			return;
		}
		try
		{
			int soundId = Integer.parseInt(effect.getParam().trim());
			client.playSoundEffect(soundId);
		}
		catch (NumberFormatException e)
		{
			log.debug("Invalid sound ID: {}", effect.getParam());
		}
	}

	// -----------------------------------------------------------------------
	// Menu entry hook — renames NPC in right-click context menu
	// -----------------------------------------------------------------------

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (npcRenameEffect.isEmpty())
		{
			return;
		}
		net.runelite.api.MenuEntry entry = event.getMenuEntry();
		net.runelite.api.NPC npc = entry.getNpc();
		if (npc == null)
		{
			return;
		}
		String cleanName = Text.removeTags(npc.getName() != null ? npc.getName() : "");
		String newName = npcRenameEffect.getAll().get(cleanName.toLowerCase());
		if (newName != null)
		{
			String target = entry.getTarget();
			entry.setTarget(target.replace(cleanName, newName));
		}
	}

	// -----------------------------------------------------------------------
	// Sound interception — Squeaky Weapon
	// -----------------------------------------------------------------------

	@Subscribe
	public void onAreaSoundEffectPlayed(AreaSoundEffectPlayed event)
	{
		if (!squeakyWeaponEffect.isActive())
		{
			return;
		}
		net.runelite.api.Actor source = event.getSource();
		if (source == null || source == client.getLocalPlayer())
		{
			event.consume();
		}
	}

	@Subscribe
	public void onSoundEffectPlayed(SoundEffectPlayed event)
	{
		if (!squeakyWeaponEffect.isActive())
		{
			return;
		}
		// Attack sounds come through with source == null (server-sent global sounds)
		net.runelite.api.Actor source = event.getSource();
		if (source == null || source == client.getLocalPlayer())
		{
			event.consume();
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (!squeakyWeaponEffect.isActive())
		{
			return;
		}
		// isMine() = this damage was dealt by the local player.
		// Also require the target is not the local player so enemy hitsplats on
		// the player (incoming damage) don't trigger the squeak.
		if (event.getHitsplat().isMine() && event.getActor() != client.getLocalPlayer())
		{
			squeakyWeaponEffect.playSqueaky(client.getTickCount());
		}
	}

	// -----------------------------------------------------------------------
	// Game tick — apply ongoing effects
	// -----------------------------------------------------------------------

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (effectManager.isActive(EffectType.LOST_BOSS))
		{
			lostBossEffect.clientTick();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		boolean wasBossActive = lostBossEffect.isSpawned();
		effectManager.tickAll();

		// Lost Boss: tick movement, or despawn if the timed effect just expired
		if (effectManager.isActive(EffectType.LOST_BOSS))
		{
			lostBossEffect.tick();
		}
		else if (wasBossActive)
		{
			lostBossEffect.despawn();
		}

	// Item Swap: re-applies overrides and reverts expired entries each tick
		itemSwapEffect.tick();

		// Outfit Swap: re-applies visual overrides and reverts on expiry each tick
		outfitSwapEffect.tick();

	}

	/**
	 * Fires with priority=1 (before other plugins) whenever the server sends a
	 * fresh {@link net.runelite.api.PlayerComposition} for a player.  We
	 * immediately re-apply item-swap overrides on the local player so there is
	 * no visible flicker between equip and swap — matching the technique used by
	 * weapon-animation-replacer.
	 */
	@Subscribe(priority = 1)
	public void onPlayerChanged(PlayerChanged event)
	{
		if (event.getPlayer() != client.getLocalPlayer())
		{
			return;
		}
		// Apply item-swap and outfit-swap immediately on each server-sent
		// PlayerComposition update (priority=1 = before other plugins).
		// For outfit swap, applyNow() also stores currentActualState so expiry
		// reverts to exactly what the server knows, not a stale activation snapshot.
		itemSwapEffect.applyNow(event.getPlayer());
		outfitSwapEffect.applyNow(event.getPlayer());
	}

	@Provides
	MxeaezShopConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MxeaezShopConfig.class);
	}
}
