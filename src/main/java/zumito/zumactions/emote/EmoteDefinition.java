package zumito.zumactions.emote;

// id es el ResourceLocation completo del emote como string ("zumactions:hug"), calculado
// por EmoteReloadListener a partir de dónde vive el archivo JSON en el datapack.
public record EmoteDefinition(
		String id,
		String label,
		EmoteParticipants participants,
		EmoteBehavior behavior,
		int durationTicks,
		int maxChainLength) {
}
