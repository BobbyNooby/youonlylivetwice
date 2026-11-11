package bobbynooby.dev.gametest.mixin;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerInvoker {
    // The private step every typed command goes through, before vanilla checks its signatures
    @Invoker("parseCommand")
    ParseResults<CommandSourceStack> yoltParseCommand(String command);
}
