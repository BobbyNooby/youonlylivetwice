package bobbynooby.dev.mixin;

import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

// The packet keeps its spot as eighths of a block, which the coordinate offset shifts in a copy, as its getters give floats
@Mixin(ClientboundSoundPacket.class)
public interface SoundPacketAccessor {

    @Accessor("x")
    int yoltX();

    @Accessor("z")
    int yoltZ();

    @Accessor("x")
    @Mutable
    void yoltSetX(int x);

    @Accessor("z")
    @Mutable
    void yoltSetZ(int z);
}
