package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.CommandRestriction;
import bobbynooby.dev.config.Settings;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommandSuggestionsProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerCommandSuggestionsProvider.class)
public abstract class ServerCommandSuggestionsProviderMixin {
    @Shadow
    @Final
    private ServerPlayer player;

    // A modified client can ask about commands it was never sent, and /msg would suggest every online name
    @WrapOperation(method = "tryProcessRequest", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;getDispatcher()Lcom/mojang/brigadier/CommandDispatcher;"))
    private CommandDispatcher<CommandSourceStack> suggestFromNonOpTree(Commands commands,
                                                                      Operation<CommandDispatcher<CommandSourceStack>> original) {
        if (CommandRestriction.restricted(this.player)) {
            return new CommandDispatcher<>(CommandRestriction.nonOpTree(Settings.get().chat.allowWhispers));
        }
        return original.call(commands);
    }
}
