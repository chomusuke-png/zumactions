package zumito.zumactions.emote;

public record EmoteDefinition(
		String id,
		String label,
		EmoteParticipants participants,
		EmoteBehavior behavior,
		int durationTicks,
		int maxChainLength) {

	public static EmoteDefinition oneshot(String id, String label, EmoteParticipants participants, int durationTicks) {
		return new EmoteDefinition(id, label, participants, EmoteBehavior.ONESHOT, durationTicks, 1);
	}

	public static EmoteDefinition loop(String id, String label, EmoteParticipants participants) {
		return new EmoteDefinition(id, label, participants, EmoteBehavior.LOOP, 0, 1);
	}

	public static EmoteDefinition movement(String id, String label, int maxChainLength) {
		return new EmoteDefinition(id, label, EmoteParticipants.DUO, EmoteBehavior.MOVEMENT, 0, maxChainLength);
	}
}
