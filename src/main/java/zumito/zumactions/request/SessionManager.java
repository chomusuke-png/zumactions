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
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
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
	private static final String NO_COLLISION_TEAM_NAME = "zumactions_no_collision";

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

		boolean facesEachOther = (emote.behavior() == EmoteBehavior.ONESHOT || emote.behavior() == EmoteBehavior.LOOP) && participants.size() > 1;

		// ONESHOT/LOOP no usan passengers, así que sin esto quedarían "al aire" a la
		// distancia máxima permitida para aceptar (5 bloques). Se hace ANTES de tomar los
		// anchors de LOOP para que el teleport no cuente como "se movió" al toque.
		if (facesEachOther) {
			positionFaceToFace(participants.get(0), participants.get(1), emote.distance());
		}

		// Distancias tan cortas hacen que las hitboxes se solapen y Minecraft los empuje
		// para separarlos (lo que además corta el LOOP, porque cuenta como "se movió") y
		// deja que cualquiera de afuera los empuje también. Se sacan de la física de
		// colisión mientras dura la sesión.
		Map<UUID, String> previousTeams = facesEachOther ? disableCollision(server, participants) : Map.of();

		List<UUID> ids = participants.stream().map(ServerPlayer::getUUID).toList();
		UUID leader = emote.behavior() == EmoteBehavior.MOVEMENT ? participants.get(0).getUUID() : null;
		Map<UUID, Vec3> anchors = emote.behavior() == EmoteBehavior.LOOP
				? participants.stream().collect(Collectors.toMap(ServerPlayer::getUUID, ServerPlayer::position))
				: Map.of();
		ActiveSession session = new ActiveSession(ids, emote.id(), emote.behavior(), leader, anchors, previousTeams, expiresAtTick);
		for (UUID id : ids) {
			sessionsByParticipant.put(id, session);
		}

		if (emote.behavior() == EmoteBehavior.MOVEMENT) {
			ServerPlayer leaderPlayer = participants.get(0);
			ServerPlayer passenger = participants.get(1);
			// teleportTo hace stopRiding() internamente, por eso va antes de montar: si lo
			// llamáramos después, se bajaría al pasajero apenas lo acabábamos de subir.
			passenger.teleportTo(leaderPlayer.serverLevel(), passenger.getX(), passenger.getY(), passenger.getZ(),
					leaderPlayer.getYRot(), leaderPlayer.getXRot());
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

			if (session.behavior() == EmoteBehavior.MOVEMENT) {
				if (!isStillMounted(session, server)) {
					end(session, server, null);
					continue;
				}
				syncPassengerLook(session, server);
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

	// Fuerza la cámara del pasajero a mirar hacia donde mira el líder, cada tick, mientras
	// dure la montura. Usa el connection.teleport "liviano" (no el ServerPlayer#teleportTo)
	// porque ese último hace stopRiding() y desmontaría al pasajero en cada tick.
	private static void syncPassengerLook(ActiveSession session, MinecraftServer server) {
		ServerPlayer leader = server.getPlayerList().getPlayer(session.leader());
		ServerPlayer passenger = passengerOf(session, server);
		if (leader == null || passenger == null) {
			return;
		}
		passenger.connection.teleport(passenger.getX(), passenger.getY(), passenger.getZ(), leader.getYRot(), leader.getXRot());
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

		restoreCollision(server, session);

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

	// Teletransporta al que acepta justo enfrente de quien pidió el emote (a "distance"
	// bloques, en la dirección a la que está mirando el emisor) y lo orienta para que
	// quede cara a cara. El emisor no se mueve ni se reorienta.
	private static void positionFaceToFace(ServerPlayer sender, ServerPlayer acceptor, double distance) {
		float yawRad = (float) Math.toRadians(sender.getYRot());
		double x = sender.getX() - Math.sin(yawRad) * distance;
		double z = sender.getZ() + Math.cos(yawRad) * distance;
		float facingYaw = sender.getYRot() + 180F;
		acceptor.teleportTo(sender.serverLevel(), x, sender.getY(), z, facingYaw, acceptor.getXRot());
	}

	// Mete a los participantes en un equipo compartido con collisionRule NEVER, para que
	// no se empujen entre ellos ni los empuje nadie de afuera. Devuelve, por jugador, el
	// nombre del equipo que tenían antes (o null si no tenían), para poder restaurarlo.
	private static Map<UUID, String> disableCollision(MinecraftServer server, List<ServerPlayer> participants) {
		ServerScoreboard scoreboard = server.getScoreboard();
		PlayerTeam team = scoreboard.getPlayerTeam(NO_COLLISION_TEAM_NAME);
		if (team == null) {
			team = scoreboard.addPlayerTeam(NO_COLLISION_TEAM_NAME);
			team.setCollisionRule(Team.CollisionRule.NEVER);
		}

		Map<UUID, String> previousTeams = new HashMap<>();
		for (ServerPlayer player : participants) {
			PlayerTeam previous = scoreboard.getPlayersTeam(player.getScoreboardName());
			previousTeams.put(player.getUUID(), previous != null ? previous.getName() : null);
			scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
		}
		return previousTeams;
	}

	private static void restoreCollision(MinecraftServer server, ActiveSession session) {
		if (session.previousTeams().isEmpty()) {
			return;
		}

		ServerScoreboard scoreboard = server.getScoreboard();
		for (Map.Entry<UUID, String> entry : session.previousTeams().entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				continue;
			}

			scoreboard.removePlayerFromTeam(player.getScoreboardName());
			String previousTeamName = entry.getValue();
			if (previousTeamName != null) {
				PlayerTeam previousTeam = scoreboard.getPlayerTeam(previousTeamName);
				if (previousTeam != null) {
					scoreboard.addPlayerToTeam(player.getScoreboardName(), previousTeam);
				}
			}
		}
	}
}
