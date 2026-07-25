package zumito.zumactions.request;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import zumito.zumactions.emote.EmoteDefinition;
import zumito.zumactions.emote.EmoteParticipants;
import zumito.zumactions.emote.EmoteRegistry;
import zumito.zumactions.network.PendingRequestsPayload;

// Estado autoritativo en el servidor de las solicitudes pendientes.
// Invariante: como máximo MAX_INCOMING_REQUESTS entrantes por destinatario (una por cada
// emisor distinto) y una saliente por emisor. incomingByTarget es un mapa anidado
// (destinatario -> emisor -> solicitud) para poder tener varias entrantes a la vez sin
// perder la posibilidad de identificar de quién es cada una.
//
// Anti-abuso: cooldown global corto por emisor (frena macros/spam a cualquiera) + cooldown
// más largo por par emisor-destino después de un rechazo o timeout (frena insistirle a la
// misma persona hasta que ceda). Ninguno de los dos aplica al auto-target SOLO, porque ahí
// no hay a quién molestar. También hay bloqueo persistente vía BlockListData.
public final class RequestManager {
	private static final int TIMEOUT_TICKS = 30 * 20;
	private static final double MAX_DISTANCE = 5.0;
	private static final int GLOBAL_COOLDOWN_TICKS = 3 * 20;
	private static final int PAIR_COOLDOWN_TICKS = 60 * 20;
	private static final int MAX_INCOMING_REQUESTS = 3;

	private static final Map<UUID, Map<UUID, PendingRequest>> incomingByTarget = new HashMap<>();
	private static final Map<UUID, UUID> outgoingBySender = new HashMap<>();
	private static final Map<UUID, Long> lastRequestTick = new HashMap<>();
	private static final Map<PairKey, Long> pairCooldownUntilTick = new HashMap<>();

	private record PairKey(UUID sender, UUID target) {
	}

	private RequestManager() {
	}

	public static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(RequestManager::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.getPlayer().getUUID(), server));
	}

	public static void sendRequest(ServerPlayer sender, ServerPlayer target, String emoteId) {
		MinecraftServer server = sender.getServer();
		UUID senderId = sender.getUUID();
		UUID targetId = target.getUUID();

		EmoteDefinition emote = EmoteRegistry.get(emoteId);
		if (emote == null) {
			sender.sendSystemMessage(Component.translatable("zumactions.error.unknown_emote", emoteId));
			return;
		}

		if (senderId.equals(targetId)) {
			if (emote.participants() != EmoteParticipants.SOLO) {
				sender.sendSystemMessage(Component.translatable("zumactions.error.not_solo", emote.label()));
				return;
			}
			if (SessionManager.isBusy(senderId)) {
				sender.sendSystemMessage(Component.translatable("zumactions.error.you_are_busy"));
				return;
			}
			SessionManager.start(List.of(sender), emote);
			return;
		}

		if (emote.participants() != EmoteParticipants.DUO) {
			sender.sendSystemMessage(Component.translatable(
					"zumactions.error.not_duo", emote.label(), sender.getGameProfile().getName(), emote.id()));
			return;
		}

		if (SessionManager.isBusy(senderId)) {
			sender.sendSystemMessage(Component.translatable("zumactions.error.you_are_busy"));
			return;
		}

		if (BlockListData.get(server).isBlocked(targetId, senderId)) {
			sender.sendSystemMessage(Component.translatable("zumactions.error.blocked"));
			return;
		}

		long currentTick = server.getTickCount();
		Long lastRequest = lastRequestTick.get(senderId);
		if (lastRequest != null && currentTick - lastRequest < GLOBAL_COOLDOWN_TICKS) {
			sender.sendSystemMessage(Component.translatable("zumactions.error.global_cooldown"));
			return;
		}

		Long pairCooldown = pairCooldownUntilTick.get(new PairKey(senderId, targetId));
		if (pairCooldown != null && pairCooldown > currentTick) {
			sender.sendSystemMessage(Component.translatable("zumactions.error.pair_cooldown", target.getGameProfile().getName()));
			return;
		}

		Map<UUID, PendingRequest> targetIncoming = incomingByTarget.get(targetId);
		boolean alreadyPendingFromSender = targetIncoming != null && targetIncoming.containsKey(senderId);
		if (!alreadyPendingFromSender && targetIncoming != null && targetIncoming.size() >= MAX_INCOMING_REQUESTS) {
			sender.sendSystemMessage(Component.translatable("zumactions.error.target_full", target.getGameProfile().getName()));
			return;
		}

		UUID previousTarget = outgoingBySender.get(senderId);
		if (previousTarget != null && !previousTarget.equals(targetId)) {
			if (removeIncoming(previousTarget, senderId) != null) {
				ServerPlayer previousTargetPlayer = server.getPlayerList().getPlayer(previousTarget);
				if (previousTargetPlayer != null) {
					previousTargetPlayer.sendSystemMessage(
							Component.translatable("zumactions.info.previous_request_cancelled", sender.getGameProfile().getName()));
					sendIncomingSnapshot(previousTargetPlayer);
				}
			}
		}

		PendingRequest request = new PendingRequest(senderId, targetId, emote.id(), currentTick + TIMEOUT_TICKS);
		incomingByTarget.computeIfAbsent(targetId, id -> new LinkedHashMap<>()).put(senderId, request);
		outgoingBySender.put(senderId, targetId);
		lastRequestTick.put(senderId, currentTick);
		sendIncomingSnapshot(target);

		sender.sendSystemMessage(Component.translatable("zumactions.info.request_sent", target.getGameProfile().getName()));
		target.sendSystemMessage(Component.translatable("zumactions.info.invited", sender.getGameProfile().getName(), emote.label()));
	}

	// Sin especificar emisor: solo funciona si hay una única solicitud entrante (el caso
	// común). Con varias, hay que decir de quién con /zumactions accept <jugador>.
	public static void acceptAny(ServerPlayer target) {
		ServerPlayer sender = resolveSoleSender(target, "accept");
		if (sender != null) {
			accept(target, sender);
		}
	}

	public static void accept(ServerPlayer target, ServerPlayer sender) {
		PendingRequest request = getIncoming(target.getUUID(), sender.getUUID());
		if (request == null) {
			target.sendSystemMessage(Component.translatable("zumactions.error.no_request_from", sender.getGameProfile().getName()));
			return;
		}

		if (sender.level() != target.level() || sender.distanceTo(target) > MAX_DISTANCE) {
			clearRequest(request);
			sendIncomingSnapshot(target);
			Component tooFar = Component.translatable("zumactions.error.too_far");
			sender.sendSystemMessage(tooFar);
			target.sendSystemMessage(tooFar);
			return;
		}

		if (SessionManager.isBusy(sender.getUUID())) {
			target.sendSystemMessage(Component.translatable("zumactions.error.sender_busy", sender.getGameProfile().getName()));
			return;
		}
		if (SessionManager.isBusy(target.getUUID())) {
			target.sendSystemMessage(Component.translatable("zumactions.error.you_are_busy"));
			return;
		}

		clearRequest(request);
		sendIncomingSnapshot(target);
		EmoteDefinition emote = EmoteRegistry.get(request.emoteId());
		SessionManager.start(List.of(sender, target), emote);
	}

	public static void rejectAny(ServerPlayer target) {
		ServerPlayer sender = resolveSoleSender(target, "reject");
		if (sender != null) {
			reject(target, sender);
		}
	}

	public static void reject(ServerPlayer target, ServerPlayer sender) {
		PendingRequest request = getIncoming(target.getUUID(), sender.getUUID());
		if (request == null) {
			target.sendSystemMessage(Component.translatable("zumactions.error.no_request_from", sender.getGameProfile().getName()));
			return;
		}

		clearRequest(request);
		sendIncomingSnapshot(target);
		startPairCooldown(target.getServer(), request);
		target.sendSystemMessage(Component.translatable("zumactions.info.you_rejected", sender.getGameProfile().getName()));
		sender.sendSystemMessage(Component.translatable("zumactions.info.rejected_by", target.getGameProfile().getName()));
	}

	private static ServerPlayer resolveSoleSender(ServerPlayer target, String commandVerb) {
		Map<UUID, PendingRequest> incoming = incomingByTarget.get(target.getUUID());
		if (incoming == null || incoming.isEmpty()) {
			target.sendSystemMessage(Component.translatable("zumactions.error.no_pending_requests"));
			return null;
		}

		if (incoming.size() > 1) {
			String names = incoming.keySet().stream()
					.map(id -> playerName(target.getServer(), id))
					.collect(Collectors.joining(", "));
			target.sendSystemMessage(Component.translatable("zumactions.error.multiple_pending", names, commandVerb));
			return null;
		}

		UUID senderId = incoming.keySet().iterator().next();
		ServerPlayer sender = target.getServer().getPlayerList().getPlayer(senderId);
		if (sender == null) {
			target.sendSystemMessage(Component.translatable("zumactions.error.player_offline"));
			return null;
		}
		return sender;
	}

	private static String playerName(MinecraftServer server, UUID id) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		return player != null ? player.getGameProfile().getName() : id.toString();
	}

	private static void startPairCooldown(MinecraftServer server, PendingRequest request) {
		pairCooldownUntilTick.put(new PairKey(request.sender(), request.target()), (long) server.getTickCount() + PAIR_COOLDOWN_TICKS);
	}

	private static void tick(MinecraftServer server) {
		if (incomingByTarget.isEmpty()) {
			return;
		}

		long currentTick = server.getTickCount();
		Iterator<Map<UUID, PendingRequest>> targetIterator = incomingByTarget.values().iterator();
		while (targetIterator.hasNext()) {
			Map<UUID, PendingRequest> byTarget = targetIterator.next();
			Iterator<PendingRequest> requestIterator = byTarget.values().iterator();
			while (requestIterator.hasNext()) {
				PendingRequest request = requestIterator.next();
				if (request.expiresAtTick() > currentTick) {
					continue;
				}

				requestIterator.remove();
				outgoingBySender.remove(request.sender());
				startPairCooldown(server, request);

				ServerPlayer sender = server.getPlayerList().getPlayer(request.sender());
				if (sender != null) {
					sender.sendSystemMessage(Component.translatable("zumactions.info.your_request_expired"));
				}
				ServerPlayer target = server.getPlayerList().getPlayer(request.target());
				if (target != null) {
					target.sendSystemMessage(Component.translatable("zumactions.info.incoming_request_expired"));
					sendIncomingSnapshot(target);
				}
			}
			if (byTarget.isEmpty()) {
				targetIterator.remove();
			}
		}
	}

	private static void onDisconnect(UUID playerId, MinecraftServer server) {
		Map<UUID, PendingRequest> asTarget = incomingByTarget.remove(playerId);
		if (asTarget != null) {
			for (PendingRequest request : asTarget.values()) {
				outgoingBySender.remove(request.sender());
				ServerPlayer sender = server.getPlayerList().getPlayer(request.sender());
				if (sender != null) {
					sender.sendSystemMessage(Component.translatable("zumactions.info.sender_disconnected"));
				}
			}
		}

		UUID targetId = outgoingBySender.remove(playerId);
		if (targetId != null) {
			if (removeIncoming(targetId, playerId) != null) {
				ServerPlayer target = server.getPlayerList().getPlayer(targetId);
				if (target != null) {
					target.sendSystemMessage(Component.translatable("zumactions.info.request_cancelled"));
					sendIncomingSnapshot(target);
				}
			}
		}

		lastRequestTick.remove(playerId);
		pairCooldownUntilTick.keySet().removeIf(key -> key.sender().equals(playerId) || key.target().equals(playerId));
	}

	public static void block(ServerPlayer blocker, ServerPlayer toBlock) {
		if (blocker.getUUID().equals(toBlock.getUUID())) {
			blocker.sendSystemMessage(Component.translatable("zumactions.error.cant_block_self"));
			return;
		}

		boolean added = BlockListData.get(blocker.getServer()).block(blocker.getUUID(), toBlock.getUUID());
		if (!added) {
			blocker.sendSystemMessage(Component.translatable("zumactions.info.already_blocked", toBlock.getGameProfile().getName()));
			return;
		}

		blocker.sendSystemMessage(Component.translatable("zumactions.info.blocked", toBlock.getGameProfile().getName()));
		cancelPendingRequestBetween(toBlock.getUUID(), blocker.getUUID(), blocker.getServer());
	}

	public static void unblock(ServerPlayer blocker, ServerPlayer toUnblock) {
		boolean removed = BlockListData.get(blocker.getServer()).unblock(blocker.getUUID(), toUnblock.getUUID());
		blocker.sendSystemMessage(removed
				? Component.translatable("zumactions.info.unblocked", toUnblock.getGameProfile().getName())
				: Component.translatable("zumactions.error.not_blocked", toUnblock.getGameProfile().getName()));
	}

	private static void cancelPendingRequestBetween(UUID senderId, UUID targetId, MinecraftServer server) {
		PendingRequest request = getIncoming(targetId, senderId);
		if (request == null) {
			return;
		}

		clearRequest(request);
		ServerPlayer blockerTarget = server.getPlayerList().getPlayer(targetId);
		if (blockerTarget != null) {
			sendIncomingSnapshot(blockerTarget);
		}
		ServerPlayer otherSender = server.getPlayerList().getPlayer(senderId);
		if (otherSender != null) {
			otherSender.sendSystemMessage(Component.translatable("zumactions.info.your_request_cancelled"));
		}
	}

	private static PendingRequest getIncoming(UUID targetId, UUID senderId) {
		Map<UUID, PendingRequest> byTarget = incomingByTarget.get(targetId);
		return byTarget != null ? byTarget.get(senderId) : null;
	}

	private static PendingRequest removeIncoming(UUID targetId, UUID senderId) {
		Map<UUID, PendingRequest> byTarget = incomingByTarget.get(targetId);
		if (byTarget == null) {
			return null;
		}
		PendingRequest removed = byTarget.remove(senderId);
		if (byTarget.isEmpty()) {
			incomingByTarget.remove(targetId);
		}
		return removed;
	}

	private static void clearRequest(PendingRequest request) {
		removeIncoming(request.target(), request.sender());
		outgoingBySender.remove(request.sender());
	}

	// Le manda al destinatario el snapshot completo de sus solicitudes entrantes, para
	// que el cliente dibuje (o deje de dibujar) el tag flotante sobre cada emisor.
	static void sendIncomingSnapshot(ServerPlayer target) {
		Map<UUID, PendingRequest> incoming = incomingByTarget.getOrDefault(target.getUUID(), Map.of());
		List<PendingRequestsPayload.Entry> entries = incoming.values().stream()
				.map(request -> {
					EmoteDefinition emote = EmoteRegistry.get(request.emoteId());
					String label = emote != null ? emote.label() : request.emoteId();
					return new PendingRequestsPayload.Entry(request.sender(), label);
				})
				.toList();
		ServerPlayNetworking.send(target, new PendingRequestsPayload(entries));
	}
}
