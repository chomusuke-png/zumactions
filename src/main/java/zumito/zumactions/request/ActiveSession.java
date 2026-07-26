package zumito.zumactions.request;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.phys.Vec3;
import zumito.zumactions.emote.EmoteBehavior;

// leader es no-nulo únicamente para sesiones MOVEMENT: quien pidió el emote es quien
// lleva/carga (es el "vehicle"), el resto de los participantes son passengers.
//
// anchors solo se puebla para sesiones LOOP: la posición de cada participante al empezar,
// usada para detectar que alguien se movió y cortar la animación.
//
// previousTeams solo se puebla para ONESHOT/LOOP en dúo: a esos participantes se los mete
// en un equipo temporal sin colisión (para que no se empujen entre ellos ni los empuje
// nadie de afuera mientras dura la animación). El valor es el nombre de su equipo anterior
// (o null si no tenían), para restaurarlo al terminar.
record ActiveSession(
		List<UUID> participants,
		String emoteId,
		EmoteBehavior behavior,
		UUID leader,
		Map<UUID, Vec3> anchors,
		Map<UUID, String> previousTeams,
		long expiresAtTick) {
	static final long NO_EXPIRY = -1;

	boolean expires() {
		return expiresAtTick != NO_EXPIRY;
	}
}
