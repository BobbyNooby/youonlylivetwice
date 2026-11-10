package bobbynooby.dev.mixin;

import bobbynooby.dev.chat.CommandRestriction;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CommandParseMixin {
    @Shadow
    public ServerPlayer player;

    // Read a restricted player's command against the tree its client has. Against the full tree a /msg the client sent
    // unsigned looks like it needs a signature, and an online-mode server answers with a signature error.
    @WrapOperation(method = "parseCommand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/Commands;getDispatcher()Lcom/mojang/brigadier/CommandDispatcher;"))
    private CommandDispatcher<CommandSourceStack> parseWithNonOpTree(Commands commands,
                                                                     Operation<CommandDispatcher<CommandSourceStack>> original) {
        if (CommandRestriction.restricted(this.player)) {
            return CommandRestriction.nonOpDispatcher();
        }
        return original.call(commands);
    }
}
