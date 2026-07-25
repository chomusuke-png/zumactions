package zumito.zumactions.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import zumito.zumactions.emote.EmoteRegistry;
import zumito.zumactions.request.RequestManager;
import zumito.zumactions.request.SessionManager;

public final class ZumActionsCommand {
	private static final SuggestionProvider<CommandSourceStack> EMOTE_SUGGESTIONS =
			(context, builder) -> SharedSuggestionProvider.suggest(EmoteRegistry.ids(), builder);

	private ZumActionsCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("zumactions")
						.then(Commands.argument("target", EntityArgument.player())
								.then(Commands.argument("emote", StringArgumentType.word())
										.suggests(EMOTE_SUGGESTIONS)
										.executes(ZumActionsCommand::request)))
						.then(Commands.literal("accept").executes(ZumActionsCommand::accept))
						.then(Commands.literal("reject").executes(ZumActionsCommand::reject))
						.then(Commands.literal("stop").executes(ZumActionsCommand::stop))));
	}

	private static int request(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		ServerPlayer sender = context.getSource().getPlayerOrException();
		ServerPlayer target = EntityArgument.getPlayer(context, "target");
		String emoteId = StringArgumentType.getString(context, "emote");
		RequestManager.sendRequest(sender, target, emoteId);
		return 1;
	}

	private static int accept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		RequestManager.accept(context.getSource().getPlayerOrException());
		return 1;
	}

	private static int reject(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		RequestManager.reject(context.getSource().getPlayerOrException());
		return 1;
	}

	private static int stop(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		SessionManager.stop(context.getSource().getPlayerOrException());
		return 1;
	}
}
