package bobbynooby.dev.mixin;

import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import java.util.Map;

// A chunk's block entities, which the coordinate offset rebuilds when a tag in them holds a position; the blocks and
// heightmaps go into the copy as they are. The entries are a private record, so they are only Objects here.
@Mixin(ClientboundLevelChunkPacketData.class)
public interface LevelChunkPacketDataInvoker {

    @Invoker("<init>")
    static ClientboundLevelChunkPacketData yoltNew(Map<Heightmap.Types, long[]> heightmaps, byte[] buffer, List<?> blockEntities) {
        throw new AssertionError();
    }

    @Accessor("buffer")
    byte[] yoltBuffer();

    @Accessor("blockEntitiesData")
    List<Object> yoltBlockEntities();
}
