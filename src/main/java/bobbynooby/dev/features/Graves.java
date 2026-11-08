package bobbynooby.dev.features;

import bobbynooby.dev.graves.GraveBlockEntity;
import bobbynooby.dev.graves.GravesRegistry;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class Graves {


    public static void initialize() {
    }

    public static void spawnGrave(ServerPlayerEntity player, ServerWorld world, DamageSource damageSource) {
        BlockPos pos = player.getBlockPos();


        if (!world.getBlockState(pos).isReplaceable()) {
            pos = pos.up();

        }


        world.setBlockState(pos, GravesRegistry.GRAVE_BLOCK.getDefaultState());

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof GraveBlockEntity graveBlockEntity) {
            graveBlockEntity.setInventory(player.getInventory());
            graveBlockEntity.setHead(player);
        }


        player.getInventory().clear();
        PseudoHardcore.handleDeath(player);
    }
}