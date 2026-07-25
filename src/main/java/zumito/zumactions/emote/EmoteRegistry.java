package zumito.zumactions.emote;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// Registro en memoria por ahora. Pensado para que el consumidor (comandos, RequestManager,
// SessionManager) solo dependa de EmoteRegistry#get e ids(), así después esto se puede
// reemplazar por un SimpleJsonResourceReloadListener que lea las definiciones desde datapacks
// sin tocar el resto.
public final class EmoteRegistry {
	private static final Map<String, EmoteDefinition> EMOTES = new LinkedHashMap<>();

	private EmoteRegistry() {
	}

	public static void bootstrap() {
		register(EmoteDefinition.oneshot("wave", "saludo", EmoteParticipants.SOLO, 20));
		register(EmoteDefinition.loop("sit", "sentarse", EmoteParticipants.SOLO));

		register(EmoteDefinition.oneshot("kiss", "beso", EmoteParticipants.DUO, 30));
		register(EmoteDefinition.oneshot("high_five", "chócala", EmoteParticipants.DUO, 20));
		register(EmoteDefinition.oneshot("pat", "pat pat", EmoteParticipants.DUO, 20));
		register(EmoteDefinition.loop("hug", "abrazo", EmoteParticipants.DUO));
		register(EmoteDefinition.movement("hold_hand", "llevar de la mano", 6));
		register(EmoteDefinition.movement("piggyback", "caballito", 6));
	}

	private static void register(EmoteDefinition emote) {
		EMOTES.put(emote.id(), emote);
	}

	public static EmoteDefinition get(String id) {
		return EMOTES.get(id.toLowerCase(Locale.ROOT));
	}

	public static Collection<String> ids() {
		return EMOTES.keySet();
	}
}
