package zumito.zumactions.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.kosmx.playerAnim.api.IPlayable;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

// Aplica/saca animaciones de Player Animator sobre otros jugadores según lo que avisa el
// servidor (PlayAnimationPayload/StopAnimationPayload). El registro de animaciones de
// Player Animator (qué .paj hay disponibles) es propio del cliente, así que si no
// encuentra el id no hace nada — el mensaje de texto de siempre sigue funcionando igual.
public final class ClientAnimationTracker {
	private static final Map<UUID, IAnimation> activeByPlayer = new HashMap<>();

	private ClientAnimationTracker() {
	}

	public static void play(UUID playerId, String animationId) {
		ResourceLocation id = ResourceLocation.tryParse(animationId);
		AbstractClientPlayer player = findPlayer(playerId);
		if (id == null || player == null) {
			return;
		}

		IPlayable playable = PlayerAnimationRegistry.getAnimation(id);
		if (playable == null) {
			return;
		}

		stop(playerId);
		IAnimation animation = playable.playAnimation();
		PlayerAnimationAccess.getPlayerAnimLayer(player).addAnimLayer(1000, animation);
		activeByPlayer.put(playerId, animation);
	}

	public static void stop(UUID playerId) {
		IAnimation animation = activeByPlayer.remove(playerId);
		if (animation == null) {
			return;
		}

		AbstractClientPlayer player = findPlayer(playerId);
		if (player != null) {
			PlayerAnimationAccess.getPlayerAnimLayer(player).removeLayer(animation);
		}
	}

	public static void clear() {
		activeByPlayer.clear();
	}

	private static AbstractClientPlayer findPlayer(UUID playerId) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return null;
		}
		for (AbstractClientPlayer player : level.players()) {
			if (player.getUUID().equals(playerId)) {
				return player;
			}
		}
		return null;
	}
}
