package zumito.zumactions.request;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import zumito.zumactions.emote.EmoteBehavior;
import zumito.zumactions.emote.EmoteDefinition;

// Estado autoritativo de los emotes en curso (ya aceptados, o ejecutados en solitario).
// Separado de RequestManager, que solo maneja el ciclo de vida de la solicitud previa
// a que el emote arranque.
//
// Por ahora LOOP y MOVEMENT solo terminan con /zumactions stop o desconexión: la
// cancelación automática por movimiento (LOOP) y por desmonte (MOVEMENT) se agrega
// en los próximos pasos.
public final class SessionManager {
	private static final Map<UUID, ActiveSession> sessionsByParticipant = new HashMap<>();

	private SessionManager() {
	}

	public static void registerEvents() {
		ServerTickEvents.END_SERVER_TICK.register(SessionManager::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onDisconnect(handler.getPlayer().getUUID(), server));
	}

	public static boolean isBusy(UUID playerId) {
		return sessionsByParticipant.containsKey(playerId);
	}

	public static void start(List<ServerPlayer> participants, EmoteDefinition emote) {
		MinecraftServer server = participants.get(0).getServer();
		long expiresAtTick = emote.behavior() == EmoteBehavior.ONESHOT
				? server.getTickCount() + emote.durationTicks()
				: ActiveSession.NO_EXPIRY;

		List<UUID> ids = participants.stream().map(ServerPlayer::getUUID).toList();
		ActiveSession session = new ActiveSession(ids, emote.id(), expiresAtTick);
		for (UUID id : ids) {
			sessionsByParticipant.put(id, session);
		}

		Component message = Component.literal(participants.size() > 1
				? "playing animation together"
				: "animation idle: " + emote.label());
		for (ServerPlayer player : participants) {
			player.sendSystemMessage(message);
		}
	}

	public static void stop(ServerPlayer player) {
		ActiveSession session = sessionsByParticipant.get(player.getUUID());
		if (session == null) {
			player.sendSystemMessage(Component.literal("No tienes ninguna animación en curso."));
			return;
		}
		end(session, player.getServer(), "Animación detenida.");
	}

	private static void tick(MinecraftServer server) {
		if (sessionsByParticipant.isEmpty()) {
			return;
		}

		long currentTick = server.getTickCount();
		for (ActiveSession session : distinctSessions()) {
			if (session.expires() && session.expiresAtTick() <= currentTick) {
				end(session, server, null);
			}
		}
	}

	private static void onDisconnect(UUID playerId, MinecraftServer server) {
		ActiveSession session = sessionsByParticipant.get(playerId);
		if (session != null) {
			end(session, server, "El jugador se desconectó, animación cancelada.");
		}
	}

	private static void end(ActiveSession session, MinecraftServer server, String customMessage) {
		for (UUID id : session.participants()) {
			sessionsByParticipant.remove(id);
		}

		Component message = Component.literal(customMessage != null ? customMessage : "La animación terminó.");
		for (UUID id : session.participants()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				player.sendSystemMessage(message);
			}
		}
	}

	private static Set<ActiveSession> distinctSessions() {
		return new HashSet<>(sessionsByParticipant.values());
	}
}
