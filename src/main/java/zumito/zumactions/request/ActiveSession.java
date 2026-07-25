package zumito.zumactions.request;

import java.util.List;
import java.util.UUID;

import zumito.zumactions.emote.EmoteBehavior;

// leader es no-nulo únicamente para sesiones MOVEMENT: quien pidió el emote es quien
// lleva/carga (es el "vehicle"), el resto de los participantes son passengers.
record ActiveSession(List<UUID> participants, String emoteId, EmoteBehavior behavior, UUID leader, long expiresAtTick) {
	static final long NO_EXPIRY = -1;

	boolean expires() {
		return expiresAtTick != NO_EXPIRY;
	}
}
