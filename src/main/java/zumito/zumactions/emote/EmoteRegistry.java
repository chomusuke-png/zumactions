package zumito.zumactions.emote;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import zumito.zumactions.ZumActionsDuoEmotes;

// Poblado por EmoteReloadListener en cada (re)carga de datapacks. El resto del mod
// (comandos, RequestManager, SessionManager) solo depende de get()/ids(), sin saber
// que los datos vienen de JSON.
public final class EmoteRegistry {
	private static Map<String, EmoteDefinition> emotes = Map.of();

	private EmoteRegistry() {
	}

	public static void reload(Map<ResourceLocation, EmoteDefinition> loaded) {
		Map<String, EmoteDefinition> next = new LinkedHashMap<>();
		for (Map.Entry<ResourceLocation, EmoteDefinition> entry : loaded.entrySet()) {
			next.put(entry.getKey().toString(), entry.getValue());
		}
		emotes = next;
	}

	public static EmoteDefinition get(String rawId) {
		return emotes.get(normalize(rawId));
	}

	public static Collection<String> ids() {
		return emotes.keySet();
	}

	// Un id sin namespace ("hug") se asume del namespace del mod ("zumactions:hug"),
	// igual que hacen los comandos vanilla con items/bloques.
	private static String normalize(String rawId) {
		String lower = rawId.toLowerCase(Locale.ROOT);
		return lower.contains(":") ? lower : ZumActionsDuoEmotes.MOD_ID + ":" + lower;
	}
}
