package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;

import java.util.function.Function;

public class GravesRegistry {


    public static final GraveBlock GRAVE_BLOCK = register("grave_block", (k) -> new GraveBlock(AbstractBlock.Settings.create().registryKey(k).nonOpaque().strength(1.0F).pistonBehavior(PistonBehavior.DESTROY)), Registries.BLOCK);

    public static <A extends T, T> A register(String key, Function<RegistryKey<T>, A> value, Registry<T> registry) {
        var id = YouOnlyLiveTwice.id(key);
        var v = value.apply(RegistryKey.of(registry.getKey(), id));
        if (v instanceof BlockEntityType<?> blockEntityType) {
            PolymerBlockUtils.registerBlockEntity(blockEntityType);
        }
        return Registry.register(registry, id, v);
    }

    public static void initialize() {
    }

    public static final BlockEntityType<GraveBlockEntity> GRAVE_BLOCK_ENTITY = register("grave_block", (k) -> FabricBlockEntityTypeBuilder.create(GraveBlockEntity::new, GRAVE_BLOCK).build(), Registries.BLOCK_ENTITY_TYPE);


}
