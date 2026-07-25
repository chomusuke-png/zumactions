package zumito.zumactions.request;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import zumito.zumactions.emote.EmoteDefinition;
import zumito.zumactions.emote.EmoteParticipants;
import zumito.zumactions.emote.EmoteRegistry;

// Estado autoritativo en el servidor de las solicitudes pendientes.
// Invariante: como máximo una solicitud entrante por destinatario y una saliente por emisor,
// ambos mapas se actualizan siempre juntos (ver clearRequest).
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

	private static final Map<UUID, PendingRequest> incomingByTarget = new HashMap<>();
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
			sender.sendSystemMessage(Component.literal("El emote '" + emoteId + "' no existe."));
			return;
		}

		if (senderId.equals(targetId)) {
			if (emote.participants() != EmoteParticipants.SOLO) {
				sender.sendSystemMessage(Component.literal("'" + emote.label() + "' no es una acción en solitario."));
				return;
			}
			if (SessionManager.isBusy(senderId)) {
				sender.sendSystemMessage(Component.literal("Ya tienes una animación en curso. Usa /zumactions stop primero."));
				return;
			}
			SessionManager.start(List.of(sender), emote);
			return;
		}

		if (emote.participants() != EmoteParticipants.DUO) {
			sender.sendSystemMessage(Component.literal("'" + emote.label() + "' no es una acción en solitario. Pruébala con tu propio nombre: /zumactions "
					+ sender.getGameProfile().getName() + " " + emote.id()));
			return;
		}

		if (SessionManager.isBusy(senderId)) {
			sender.sendSystemMessage(Component.literal("Ya tienes una animación en curso. Usa /zumactions stop primero."));
			return;
		}

		if (BlockListData.get(server).isBlocked(targetId, senderId)) {
			sender.sendSystemMessage(Component.literal("No podés enviarle una solicitud a ese jugador."));
			return;
		}

		long currentTick = server.getTickCount();
		Long lastRequest = lastRequestTick.get(senderId);
		if (lastRequest != null && currentTick - lastRequest < GLOBAL_COOLDOWN_TICKS) {
			sender.sendSystemMessage(Component.literal("Esperá un momento antes de mandar otra solicitud."));
			return;
		}

		Long pairCooldown = pairCooldownUntilTick.get(new PairKey(senderId, targetId));
		if (pairCooldown != null && pairCooldown > currentTick) {
			sender.sendSystemMessage(Component.literal(target.getGameProfile().getName() + " no está aceptando solicitudes tuyas por ahora, esperá un poco."));
			return;
		}

		PendingRequest existingIncoming = incomingByTarget.get(targetId);
		if (existingIncoming != null && !existingIncoming.sender().equals(senderId)) {
			sender.sendSystemMessage(Component.literal(target.getGameProfile().getName() + " ya tiene una solicitud pendiente de otro jugador."));
			return;
		}

		UUID previousTarget = outgoingBySender.get(senderId);
		if (previousTarget != null && !previousTarget.equals(targetId)) {
			incomingByTarget.remove(previousTarget);
			ServerPlayer previousTargetPlayer = server.getPlayerList().getPlayer(previousTarget);
			if (previousTargetPlayer != null) {
				previousTargetPlayer.sendSystemMessage(Component.literal(sender.getGameProfile().getName() + " canceló su solicitud anterior."));
			}
		}

		PendingRequest request = new PendingRequest(senderId, targetId, emote.id(), currentTick + TIMEOUT_TICKS);
		incomingByTarget.put(targetId, request);
		outgoingBySender.put(senderId, targetId);
		lastRequestTick.put(senderId, currentTick);

		sender.sendSystemMessage(Component.literal("Solicitud enviada a " + target.getGameProfile().getName() + ". animation idle"));

		MutableComponent invite = Component.literal(sender.getGameProfile().getName() + " te invitó a: " + emote.label() + " ")
				.append(actionButton("[Aceptar]", ChatFormatting.GREEN, "/zumactions accept", "Click para aceptar"))
				.append(Component.literal(" "))
				.append(actionButton("[Rechazar]", ChatFormatting.RED, "/zumactions reject", "Click para rechazar"));
		target.sendSystemMessage(invite);
	}

	private static MutableComponent actionButton(String text, ChatFormatting color, String command, String hoverText) {
		Style style = Style.EMPTY
				.withColor(color)
				.withBold(true)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hoverText)));
		return Component.literal(text).setStyle(style);
	}

	public static void accept(ServerPlayer target) {
		PendingRequest request = incomingByTarget.get(target.getUUID());
		if (request == null) {
			target.sendSystemMessage(Component.literal("No tienes solicitudes pendientes."));
			return;
		}

		MinecraftServer server = target.getServer();
		ServerPlayer sender = server.getPlayerList().getPlayer(request.sender());
		if (sender == null) {
			clearRequest(request);
			target.sendSystemMessage(Component.literal("Ese jugador ya no está conectado."));
			return;
		}

		if (sender.level() != target.level() || sender.distanceTo(target) > MAX_DISTANCE) {
			clearRequest(request);
			Component tooFar = Component.literal("Están muy lejos para hacer esto.");
			sender.sendSystemMessage(tooFar);
			target.sendSystemMessage(tooFar);
			return;
		}

		if (SessionManager.isBusy(sender.getUUID())) {
			target.sendSystemMessage(Component.literal(sender.getGameProfile().getName() + " ya tiene una animación en curso, espera a que termine."));
			return;
		}
		if (SessionManager.isBusy(target.getUUID())) {
			target.sendSystemMessage(Component.literal("Ya tienes una animación en curso. Usa /zumactions stop primero."));
			return;
		}

		clearRequest(request);
		EmoteDefinition emote = EmoteRegistry.get(request.emoteId());
		SessionManager.start(List.of(sender, target), emote);
	}

	public static void reject(ServerPlayer target) {
		PendingRequest request = incomingByTarget.get(target.getUUID());
		if (request == null) {
			target.sendSystemMessage(Component.literal("No tienes solicitudes pendientes."));
			return;
		}

		clearRequest(request);
		startPairCooldown(target.getServer(), request);
		target.sendSystemMessage(Component.literal("Rechazaste la solicitud."));

		ServerPlayer sender = target.getServer().getPlayerList().getPlayer(request.sender());
		if (sender != null) {
			sender.sendSystemMessage(Component.literal(target.getGameProfile().getName() + " rechazó tu solicitud."));
		}
	}

	private static void startPairCooldown(MinecraftServer server, PendingRequest request) {
		pairCooldownUntilTick.put(new PairKey(request.sender(), request.target()), (long) server.getTickCount() + PAIR_COOLDOWN_TICKS);
	}

	private static void tick(MinecraftServer server) {
		if (incomingByTarget.isEmpty()) {
			return;
		}

		long currentTick = server.getTickCount();
		Iterator<PendingRequest> iterator = incomingByTarget.values().iterator();
		while (iterator.hasNext()) {
			PendingRequest request = iterator.next();
			if (request.expiresAtTick() > currentTick) {
				continue;
			}

			iterator.remove();
			outgoingBySender.remove(request.sender());
			startPairCooldown(server, request);

			ServerPlayer sender = server.getPlayerList().getPlayer(request.sender());
			if (sender != null) {
				sender.sendSystemMessage(Component.literal("Tu solicitud expiró."));
			}
			ServerPlayer target = server.getPlayerList().getPlayer(request.target());
			if (target != null) {
				target.sendSystemMessage(Component.literal("La solicitud pendiente expiró."));
			}
		}
	}

	private static void onDisconnect(UUID playerId, MinecraftServer server) {
		PendingRequest asTarget = incomingByTarget.remove(playerId);
		if (asTarget != null) {
			outgoingBySender.remove(asTarget.sender());
			ServerPlayer sender = server.getPlayerList().getPlayer(asTarget.sender());
			if (sender != null) {
				sender.sendSystemMessage(Component.literal("El jugador se desconectó, solicitud cancelada."));
			}
		}

		UUID targetId = outgoingBySender.remove(playerId);
		if (targetId != null) {
			incomingByTarget.remove(targetId);
			ServerPlayer target = server.getPlayerList().getPlayer(targetId);
			if (target != null) {
				target.sendSystemMessage(Component.literal("La solicitud fue cancelada."));
			}
		}

		lastRequestTick.remove(playerId);
		pairCooldownUntilTick.keySet().removeIf(key -> key.sender().equals(playerId) || key.target().equals(playerId));
	}

	public static void block(ServerPlayer blocker, ServerPlayer toBlock) {
		if (blocker.getUUID().equals(toBlock.getUUID())) {
			blocker.sendSystemMessage(Component.literal("No podés bloquearte a vos mismo."));
			return;
		}

		boolean added = BlockListData.get(blocker.getServer()).block(blocker.getUUID(), toBlock.getUUID());
		if (!added) {
			blocker.sendSystemMessage(Component.literal("Ya tenías bloqueado a " + toBlock.getGameProfile().getName() + "."));
			return;
		}

		blocker.sendSystemMessage(Component.literal("Bloqueaste a " + toBlock.getGameProfile().getName() + "."));
		cancelPendingRequestBetween(toBlock.getUUID(), blocker.getUUID(), blocker.getServer());
	}

	public static void unblock(ServerPlayer blocker, ServerPlayer toUnblock) {
		boolean removed = BlockListData.get(blocker.getServer()).unblock(blocker.getUUID(), toUnblock.getUUID());
		blocker.sendSystemMessage(Component.literal(removed
				? "Desbloqueaste a " + toUnblock.getGameProfile().getName() + "."
				: "No tenías bloqueado a " + toUnblock.getGameProfile().getName() + "."));
	}

	private static void cancelPendingRequestBetween(UUID senderId, UUID targetId, MinecraftServer server) {
		PendingRequest request = incomingByTarget.get(targetId);
		if (request == null || !request.sender().equals(senderId)) {
			return;
		}

		clearRequest(request);
		ServerPlayer otherSender = server.getPlayerList().getPlayer(senderId);
		if (otherSender != null) {
			otherSender.sendSystemMessage(Component.literal("Tu solicitud fue cancelada."));
		}
	}

	private static void clearRequest(PendingRequest request) {
		incomingByTarget.remove(request.target());
		outgoingBySender.remove(request.sender());
	}
}
