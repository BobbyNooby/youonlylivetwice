package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.polymer.core.api.block.PolymerHeadBlock;
import net.fabricmc.fabric.api.block.BlockAttackInteractionAware;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemScatterer;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.packettweaker.PacketContext;

import java.util.function.Supplier;

public class GraveBlock extends BlockWithEntity implements PolymerHeadBlock, BlockAttackInteractionAware {


    public GraveBlock(Settings settings) {
        super(settings);

    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return null;
    }


    @Override
    public String getPolymerSkinValue(BlockState blockState, BlockPos blockPos, PacketContext packetContext) {
        // Skin https://minesk.in/9935d5ac00e04878ba8eb029b221445c
        return "ewogICJ0aW1lc3RhbXAiIDogMTc0MjI0NjUxNzQ5NCwKICAicHJvZmlsZUlkIiA6ICJjN2UxZTAwZjI5ZTk0OGI1YWJjMDZmMzA4OWM2NDljOSIsCiAgInByb2ZpbGVOYW1lIiA6ICJUdXJyZXRlZGFzaDciLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWU1N2RjYThjM2UyY2NlNTg4NDQ5MjgzNTQ0ZDZjYjQzYzFhOTY1MDZmOGRkNTg5N2UwMDIwNzE2MmEwYzhmZSIsCiAgICAgICJtZXRhZGF0YSIgOiB7CiAgICAgICAgIm1vZGVsIiA6ICJzbGltIgogICAgICB9CiAgICB9CiAgfQp9";
    }

    @Override
    public boolean onAttackInteraction(BlockState blockState, World world, BlockPos blockPos, PlayerEntity playerEntity, Hand hand, Direction direction) {
        return false;
    }


    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (player instanceof ServerPlayerEntity) {
            if (world.getBlockEntity(pos) instanceof GraveBlockEntity) {
                world.playSound(null, pos, state.getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 1.0f, 1.0f);
                world.setBlockState(pos, Blocks.AIR.getDefaultState());
            }

            return ActionResult.SUCCESS_SERVER;
        }

        return super.onUse(state, world, pos, player, hit);
    }


    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new GraveBlockEntity(pos, state);
    }

    private void scatterItems(DefaultedList<ItemStack> items, World world, BlockPos pos) {
        ItemScatterer.spawn(world, pos, items);
    }


}
