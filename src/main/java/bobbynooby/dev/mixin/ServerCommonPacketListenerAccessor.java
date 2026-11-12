package bobbynooby.dev.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets the coordinate offset find a player's connection, which holds their frame
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerAccessor {

    @Accessor("connection")
    Connection yoltConnection();
}
