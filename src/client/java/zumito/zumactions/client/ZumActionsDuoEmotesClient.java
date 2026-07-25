package zumito.zumactions.client;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import zumito.zumactions.network.PendingRequestsPayload;

public class ZumActionsDuoEmotesClient implements ClientModInitializer {
	private static final KeyMapping ACCEPT_KEY = new KeyMapping(
			"key.zumactions.accept", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.zumactions.category");

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(ACCEPT_KEY);

		ClientPlayNetworking.registerGlobalReceiver(PendingRequestsPayload.TYPE, (payload, context) -> {
			List<PendingRequestsPayload.Entry> added = ClientPendingRequests.update(payload.requests());
			if (!added.isEmpty()) {
				Component message = Component.literal("Miralo y presioná ")
						.append(ACCEPT_KEY.getTranslatedKeyMessage())
						.append(Component.literal(" para aceptar."));
				context.player().displayClientMessage(message, false);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPendingRequests.clear());

		ClientTickEvents.END_CLIENT_TICK.register(ZumActionsDuoEmotesClient::handleAcceptKey);
	}

	private static void handleAcceptKey(Minecraft client) {
		while (ACCEPT_KEY.consumeClick()) {
			tryAcceptLookedAtPlayer(client);
		}
	}

	// Solo funciona mientras el crosshair esté sobre un jugador que te mandó una
	// solicitud (a propósito, como pidió el diseño original: "mirando a ese jugador").
	private static void tryAcceptLookedAtPlayer(Minecraft client) {
		LocalPlayer player = client.player;
		HitResult hitResult = client.hitResult;
		if (player == null || !(hitResult instanceof EntityHitResult entityHitResult)) {
			return;
		}

		Entity target = entityHitResult.getEntity();
		if (!(target instanceof Player targetPlayer)) {
			return;
		}

		if (ClientPendingRequests.labelFor(targetPlayer.getUUID()) == null) {
			return;
		}

		player.connection.sendCommand("zumactions accept " + targetPlayer.getGameProfile().getName());
	}
}
