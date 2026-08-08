package zumito.zumactions.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import zumito.zumactions.ZumActionsDuoEmotes;

// S2C: avisa que el jugador "playerId" tiene que reproducir la animación "animationId"
// (el mismo id que el emote, ver convención en EmoteReloadListener/player_animations).
// El cliente decide en silencio si no tiene esa animación cargada (fallback normal a texto).
public record PlayAnimationPayload(UUID playerId, String animationId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<PlayAnimationPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZumActionsDuoEmotes.MOD_ID, "play_animation"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PlayAnimationPayload> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, PlayAnimationPayload::playerId,
			ByteBufCodecs.STRING_UTF8, PlayAnimationPayload::animationId,
			PlayAnimationPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
