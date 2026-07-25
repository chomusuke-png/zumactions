package zumito.zumactions.request;

import java.util.UUID;

record PendingRequest(UUID sender, UUID target, String emoteId, long expiresAtTick) {
}
