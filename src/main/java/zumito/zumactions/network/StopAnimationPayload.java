package zumito.zumactions.network;

import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import zumito.zumactions.ZumActionsDuoEmotes;

// S2C: avisa que el jugador "playerId" tiene que dejar de reproducir su animación actual.
public record StopAnimationPayload(UUID playerId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<StopAnimationPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZumActionsDuoEmotes.MOD_ID, "stop_animation"));

	public static final StreamCodec<RegistryFriendlyByteBuf, StopAnimationPayload> STREAM_CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, StopAnimationPayload::playerId,
			StopAnimationPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
