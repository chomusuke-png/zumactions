package zumito.zumactions.emote;

// id es el ResourceLocation completo del emote como string ("zumactions:hug"), calculado
// por EmoteReloadListener a partir de dónde vive el archivo JSON en el datapack.
//
// distance: solo se usa en ONESHOT/LOOP con participants=duo. Es la distancia (en bloques)
// a la que SessionManager teletransporta al jugador que acepta, parado justo enfrente de
// quien pidió el emote, para que no queden animaciones "al aire" a 5 bloques de distancia.
public record EmoteDefinition(
		String id,
		String label,
		EmoteParticipants participants,
		EmoteBehavior behavior,
		int durationTicks,
		int maxChainLength,
		double distance) {
}
