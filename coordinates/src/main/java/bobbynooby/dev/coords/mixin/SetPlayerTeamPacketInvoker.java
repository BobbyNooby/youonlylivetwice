package bobbynooby.dev.coords.mixin;

import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;
import java.util.Optional;

// The public factories build a team's packet from the team itself, so the coordinate offset builds its copy with the
// shifted texts here. YOLT's jar has an invoker of its own on this class, so the method's name is this mod's own.
@Mixin(ClientboundSetPlayerTeamPacket.class)
public interface SetPlayerTeamPacketInvoker {
    @Invoker("<init>")
    static ClientboundSetPlayerTeamPacket yoltCoordinatesCreate(String name, int method,
            Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters, Collection<String> players) {
        throw new AssertionError();
    }
}
