package bobbynooby.dev.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerLookAtPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// The packet has no getters and no field constructor for its entity form, so the coordinate offset shifts a copy's fields
@Mixin(ClientboundPlayerLookAtPacket.class)
public interface PlayerLookAtPacketAccessor {

    @Accessor("x")
    double yoltX();

    @Accessor("z")
    double yoltZ();

    @Accessor("x")
    @Mutable
    void yoltSetX(double x);

    @Accessor("z")
    @Mutable
    void yoltSetZ(double z);
}
