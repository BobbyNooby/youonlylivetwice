package bobbynooby.dev.coords.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

// Lets the coordinate offset send a copy of the entries with the positions in their names shifted. YOLT's jar has an
// accessor of its own on this class, so the method's name is this mod's own.
@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface PlayerInfoUpdatePacketAccessor {

    @Accessor("entries")
    @Mutable
    void yoltCoordinatesSetEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);
}
