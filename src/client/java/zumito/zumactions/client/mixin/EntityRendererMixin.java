package zumito.zumactions.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import zumito.zumactions.client.ClientPendingRequests;

// Reusa el sistema de nametag de Minecraft (ya maneja el billboard hacia la cámara, el
// fade por distancia, etc.) para mostrar el aviso de solicitud pendiente, en vez de
// dibujar texto en el mundo a mano.
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@ModifyArgs(
			method = "render",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/entity/EntityRenderer;renderNameTag(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IF)V"))
	private void zumactions$appendPendingRequestTag(Args args) {
		Entity entity = args.get(0);
		if (!(entity instanceof Player player)) {
			return;
		}

		String label = ClientPendingRequests.labelFor(player.getUUID());
		if (label == null) {
			return;
		}

		Component original = args.get(1);
		args.set(1, original.copy().append(Component.literal(" ⏳ " + label)));
	}
}
