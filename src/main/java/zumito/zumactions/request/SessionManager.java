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
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import zumito.zumactions.emote.EmoteBehavior;
import zumito.zumactions.emote.EmoteDefinition;

import java.util.stream.Collectors;

// Estado autoritativo de los emotes en curso (ya aceptados, o ejecutados en solitario).
// Separado de RequestManager, que solo maneja el ciclo de vida de la solicitud previa
// a que el emote arranque.
//
// MOVEMENT usa el sistema nativo de passengers de Minecraft: el líder (quien pidió el
// emote) es el "vehicle", el resto son passengers. El desmonte por agacharse (shift) lo
// maneja Minecraft solo; acá solo detectamos cuando ya no están montados para limpiar
// la sesión.
//
// Minecraft nunca le avisa a un jugador sobre cambios en los passengers de SU PROPIA
// entidad (ChunkMap$TrackedEntity#updatePlayer excluye explícitamente al dueño de la
// entidad de su propia lista de "seenBy") porque en vanilla un jugador nunca es vehicle
// de otro jugador. Como acá sí lo es, hay que reenviarle el ClientboundSetPassengersPacket
// a mano al líder cada vez que su lista de passengers cambia (ver syncLeaderPassengers).
//
// LOOP se corta si alguno de los participantes se aleja de dónde estaba parado al empezar
// (más que MOVE_THRESHOLD_SQ) o se agacha. La espera previa a aceptar (RequestManager) NO
// usa esta lógica a propósito: caminar con los brazos abiertos hacia quien vas a abrazar,
// mientras esperás que acepte, no debería cancelar nada.
public final class SessionManager {
	private static final double MOVE_THRESHOLD_SQ = 0.1 * 0.1;

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
		UUID leader = emote.behavior() == EmoteBehavior.MOVEMENT ? participants.get(0).getUUID() : null;
		Map<UUID, Vec3> anchors = emote.behavior() == EmoteBehavior.LOOP
				? participants.stream().collect(Collectors.toMap(ServerPlayer::getUUID, ServerPlayer::position))
				: Map.of();
		ActiveSession session = new ActiveSession(ids, emote.id(), emote.behavior(), leader, anchors, expiresAtTick);
		for (UUID id : ids) {
			sessionsByParticipant.put(id, session);
		}

		if (emote.behavior() == EmoteBehavior.MOVEMENT) {
			ServerPlayer leaderPlayer = participants.get(0);
			ServerPlayer passenger = participants.get(1);
			passenger.startRiding(leaderPlayer, true);
			syncLeaderPassengers(leaderPlayer);
		}

		Component message = participants.size() > 1
				? Component.translatable("zumactions.info.playing_together")
				: Component.translatable("zumactions.info.playing_solo", emote.label());
		for (ServerPlayer player : participants) {
			player.sendSystemMessage(message);
		}
	}

	public static void stop(ServerPlayer player) {
		ActiveSession session = sessionsByParticipant.get(player.getUUID());
		if (session == null) {
			player.sendSystemMessage(Component.translatable("zumactions.error.no_active_session"));
			return;
		}
		end(session, player.getServer(), Component.translatable("zumactions.info.animation_stopped"));
	}

	private static void tick(MinecraftServer server) {
		if (sessionsByParticipant.isEmpty()) {
			return;
		}

		long currentTick = server.getTickCount();
		for (ActiveSession session : distinctSessions()) {
			if (session.expires() && session.expiresAtTick() <= currentTick) {
				end(session, server, null);
				continue;
			}

			if (session.behavior() == EmoteBehavior.MOVEMENT && !isStillMounted(session, server)) {
				end(session, server, null);
				continue;
			}

			if (session.behavior() == EmoteBehavior.LOOP && didSomeoneMove(session, server)) {
				end(session, server, Component.translatable("zumactions.info.animation_moved"));
			}
		}
	}

	private static boolean didSomeoneMove(ActiveSession session, MinecraftServer server) {
		for (UUID id : session.participants()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			Vec3 anchor = session.anchors().get(id);
			if (player == null || anchor == null) {
				continue;
			}
			if (player.isShiftKeyDown() || player.position().distanceToSqr(anchor) > MOVE_THRESHOLD_SQ) {
				return true;
			}
		}
		return false;
	}

	private static boolean isStillMounted(ActiveSession session, MinecraftServer server) {
		ServerPlayer leader = server.getPlayerList().getPlayer(session.leader());
		ServerPlayer passenger = passengerOf(session, server);
		return leader != null && passenger != null && passenger.isPassenger() && passenger.getVehicle() == leader;
	}

	private static ServerPlayer passengerOf(ActiveSession session, MinecraftServer server) {
		return session.participants().stream()
				.filter(id -> !id.equals(session.leader()))
				.findFirst()
				.map(id -> server.getPlayerList().getPlayer(id))
				.orElse(null);
	}

	private static void onDisconnect(UUID playerId, MinecraftServer server) {
		ActiveSession session = sessionsByParticipant.get(playerId);
		if (session != null) {
			end(session, server, Component.translatable("zumactions.info.animation_disconnect"));
		}
	}

	private static void end(ActiveSession session, MinecraftServer server, Component customMessage) {
		for (UUID id : session.participants()) {
			sessionsByParticipant.remove(id);
		}

		if (session.behavior() == EmoteBehavior.MOVEMENT) {
			ServerPlayer passenger = passengerOf(session, server);
			if (passenger != null && passenger.isPassenger()) {
				passenger.stopRiding();
			}
			ServerPlayer leaderPlayer = server.getPlayerList().getPlayer(session.leader());
			if (leaderPlayer != null) {
				syncLeaderPassengers(leaderPlayer);
			}
		}

		Component message = customMessage != null ? customMessage : Component.translatable("zumactions.info.animation_ended");
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

	// Ver nota de clase: un jugador nunca recibe el ClientboundSetPassengersPacket sobre
	// su propia entidad a través del sistema normal de tracking, hay que mandárselo directo.
	private static void syncLeaderPassengers(ServerPlayer leader) {
		leader.connection.send(new ClientboundSetPassengersPacket(leader));
	}
}
