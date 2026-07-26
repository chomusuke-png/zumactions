package zumito.zumactions.emote;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zumito.zumactions.ZumActionsDuoEmotes;

// Lee data/<namespace>/zumactions/<id>.json de todos los datapacks cargados (el nuestro
// incluido, ver src/main/resources/data/zumactions/zumactions/) y puebla EmoteRegistry.
// Esquema esperado, ver diseño acordado:
//   label (string, siempre)
//   participants: "solo" | "duo" (siempre)
//   behavior: "oneshot" | "loop" | "movement" (siempre)
//   duration_ticks (int, solo relevante en oneshot, default 0)
//   max_chain_length (int, solo relevante en movement, default 2)
//   distance (double, solo relevante en oneshot/loop con participants=duo, default 1.0)
public class EmoteReloadListener extends SimpleJsonResourceReloadListener implements IdentifiableResourceReloadListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(ZumActionsDuoEmotes.MOD_ID + "/emotes");
	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(ZumActionsDuoEmotes.MOD_ID, "emotes");

	public EmoteReloadListener() {
		super(new Gson(), "zumactions");
	}

	@Override
	protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager resourceManager, ProfilerFiller profiler) {
		Map<ResourceLocation, EmoteDefinition> parsed = new LinkedHashMap<>();
		for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
			try {
				parsed.put(entry.getKey(), parse(entry.getKey(), entry.getValue().getAsJsonObject()));
			} catch (RuntimeException exception) {
				LOGGER.error("No se pudo cargar el emote {}", entry.getKey(), exception);
			}
		}
		EmoteRegistry.reload(parsed);
		LOGGER.info("Cargados {} emotes", parsed.size());
	}

	private static EmoteDefinition parse(ResourceLocation id, com.google.gson.JsonObject json) {
		String label = GsonHelper.getAsString(json, "label");
		EmoteParticipants participants = parseEnum(EmoteParticipants.values(), GsonHelper.getAsString(json, "participants"), "participants");
		EmoteBehavior behavior = parseEnum(EmoteBehavior.values(), GsonHelper.getAsString(json, "behavior"), "behavior");

		if (behavior == EmoteBehavior.MOVEMENT && participants != EmoteParticipants.DUO) {
			throw new IllegalArgumentException("Los emotes con behavior=movement deben tener participants=duo");
		}

		int durationTicks = GsonHelper.getAsInt(json, "duration_ticks", 0);
		int maxChainLength = GsonHelper.getAsInt(json, "max_chain_length", 2);
		double distance = GsonHelper.getAsDouble(json, "distance", 1.0);

		return new EmoteDefinition(id.toString(), label, participants, behavior, durationTicks, maxChainLength, distance);
	}

	private static <T extends Enum<T>> T parseEnum(T[] values, String raw, String field) {
		String normalized = raw.toUpperCase(Locale.ROOT);
		for (T value : values) {
			if (value.name().equals(normalized)) {
				return value;
			}
		}
		throw new IllegalArgumentException("Valor inválido para '" + field + "': " + raw);
	}

	@Override
	public ResourceLocation getFabricId() {
		return ID;
	}
}
