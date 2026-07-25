package zumito.zumactions.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import zumito.zumactions.ZumActionsDuoEmotes;

// S2C: le manda al destinatario el snapshot completo de sus solicitudes entrantes
// (de quién y qué emote), para que el cliente pueda dibujar el tag flotante arriba
// de la cabeza de cada emisor. Se manda entero cada vez que la lista cambia.
public record PendingRequestsPayload(List<Entry> requests) implements CustomPacketPayload {
	public record Entry(UUID senderId, String label) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
				UUIDUtil.STREAM_CODEC, Entry::senderId,
				ByteBufCodecs.STRING_UTF8, Entry::label,
				Entry::new);
	}

	public static final CustomPacketPayload.Type<PendingRequestsPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ZumActionsDuoEmotes.MOD_ID, "pending_requests"));

	public static final StreamCodec<RegistryFriendlyByteBuf, PendingRequestsPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.collection(ArrayList::new, Entry.STREAM_CODEC), PendingRequestsPayload::requests,
			PendingRequestsPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
