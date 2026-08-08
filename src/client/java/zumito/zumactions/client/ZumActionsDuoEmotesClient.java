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
import zumito.zumactions.network.PlayAnimationPayload;
import zumito.zumactions.network.StopAnimationPayload;

public class ZumActionsDuoEmotesClient implements ClientModInitializer {
	private static final KeyMapping ACCEPT_KEY = new KeyMapping(
			"key.zumactions.accept", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.zumactions.category");
	// Sin tecla por defecto: rechazar sigue funcionando por comando/tag, y quien quiera
	// una tecla dedicada la asigna desde el menú de controles.
	private static final KeyMapping REJECT_KEY = new KeyMapping(
			"key.zumactions.reject", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "key.zumactions.category");

	@Override
	public void onInitializeClient() {
		KeyBindingHelper.registerKeyBinding(ACCEPT_KEY);
		KeyBindingHelper.registerKeyBinding(REJECT_KEY);

		ClientPlayNetworking.registerGlobalReceiver(PendingRequestsPayload.TYPE, (payload, context) -> {
			List<PendingRequestsPayload.Entry> added = ClientPendingRequests.update(payload.requests());
			if (!added.isEmpty()) {
				Component message = Component.translatable("zumactions.hud.press_to_accept", ACCEPT_KEY.getTranslatedKeyMessage());
				context.player().displayClientMessage(message, false);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PlayAnimationPayload.TYPE,
				(payload, context) -> ClientAnimationTracker.play(payload.playerId(), payload.animationId()));
		ClientPlayNetworking.registerGlobalReceiver(StopAnimationPayload.TYPE,
				(payload, context) -> ClientAnimationTracker.stop(payload.playerId()));

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientPendingRequests.clear();
			ClientAnimationTracker.clear();
		});

		ClientTickEvents.END_CLIENT_TICK.register(ZumActionsDuoEmotesClient::handleKeys);
	}

	private static void handleKeys(Minecraft client) {
		while (ACCEPT_KEY.consumeClick()) {
			respondToLookedAtSender(client, "accept");
		}
		while (REJECT_KEY.consumeClick()) {
			respondToLookedAtSender(client, "reject");
		}
	}

	// Solo funciona mientras el crosshair esté sobre un jugador que te mandó una
	// solicitud (a propósito, como pidió el diseño original: "mirando a ese jugador").
	private static void respondToLookedAtSender(Minecraft client, String action) {
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

		player.connection.sendCommand("zumactions " + action + " " + targetPlayer.getGameProfile().getName());
	}
}
