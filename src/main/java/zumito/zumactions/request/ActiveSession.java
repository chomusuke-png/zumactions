package zumito.zumactions.request;

import java.util.List;
import java.util.UUID;

record ActiveSession(List<UUID> participants, String emoteId, long expiresAtTick) {
	static final long NO_EXPIRY = -1;

	boolean expires() {
		return expiresAtTick != NO_EXPIRY;
	}
}
