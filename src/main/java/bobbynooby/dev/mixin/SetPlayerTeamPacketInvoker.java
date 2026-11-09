package bobbynooby.dev.mixin;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;
import java.util.Optional;

// The public factories always list every member and the server's own settings; NameTagTeam needs its own per viewer
@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface SetPlayerTeamPacketInvoker {
    @Invoker("<init>")
    static ClientboundSetPlayerTeamPacket create(String name, int method, Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters,
                                                 Collection<String> players) {
        throw new AssertionError();
    }
}
