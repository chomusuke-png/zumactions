package zumito.zumactions;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import zumito.zumactions.command.ZumActionsCommand;
import zumito.zumactions.emote.EmoteReloadListener;
import zumito.zumactions.network.PendingRequestsPayload;
import zumito.zumactions.network.PlayAnimationPayload;
import zumito.zumactions.network.StopAnimationPayload;
import zumito.zumactions.request.RequestManager;
import zumito.zumactions.request.SessionManager;

public class ZumActionsDuoEmotes implements ModInitializer {
	public static final String MOD_ID = "zumactions";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("ZumActions initialized");
		PayloadTypeRegistry.playS2C().register(PendingRequestsPayload.TYPE, PendingRequestsPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(PlayAnimationPayload.TYPE, PlayAnimationPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(StopAnimationPayload.TYPE, StopAnimationPayload.STREAM_CODEC);
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new EmoteReloadListener());
		RequestManager.registerEvents();
		SessionManager.registerEvents();
		ZumActionsCommand.register();
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
	}
}
