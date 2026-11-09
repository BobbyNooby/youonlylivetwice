package bobbynooby.dev.graves;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.core.Stacks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A dead player's items, kept at one block until someone revives them or breaks the grave. */
public record Grave(
        UUID id,
        UUID owner,
        String ownerName,
        ResolvableProfile profile,
        ResourceKey<Level> dimension,
        BlockPos pos,
        List<ItemStack> items,
        long createdAt
) {
    // Compact constructor: an empty stack can't be encoded and would crash the world save
    public Grave {
        items = List.copyOf(Stacks.nonEmpty(items));
    }

    private static final Codec<Grave> STRICT_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Grave::id),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Grave::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(Grave::ownerName),
            ResolvableProfile.CODEC.fieldOf("profile").forGetter(Grave::profile),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Grave::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Grave::pos),
            ItemStack.CODEC.listOf().fieldOf("items").forGetter(Grave::items),
            Codec.LONG.fieldOf("created_at").forGetter(Grave::createdAt)
    ).apply(i, Grave::new));

    // Same fields, but items stay undecoded so one bad stack can be skipped instead of failing the grave
    private record Raw(UUID id, UUID owner, String ownerName, ResolvableProfile profile,
                       ResourceKey<Level> dimension, BlockPos pos, List<Dynamic<?>> items, long createdAt) {
        static final Codec<Raw> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Raw::id),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Raw::owner),
                Codec.STRING.fieldOf("owner_name").forGetter(Raw::ownerName),
                ResolvableProfile.CODEC.fieldOf("profile").forGetter(Raw::profile),
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Raw::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Raw::pos),
                Codec.PASSTHROUGH.listOf().fieldOf("items").forGetter(Raw::items),
                Codec.LONG.fieldOf("created_at").forGetter(Raw::createdAt)
        ).apply(i, Raw::new));

        Grave toGrave() {
            List<ItemStack> stacks = new ArrayList<>();
            for (Dynamic<?> entry : items) {
                DataResult<ItemStack> parsed = ItemStack.CODEC.parse(entry);
                parsed.resultOrPartial(error -> YouOnlyLiveTwice.LOGGER.error(
                        "Skipping unreadable item in grave of {} at {} {}: {}", ownerName, dimension.identifier(), pos, error))
                        .ifPresent(stacks::add);
            }
            return new Grave(id, owner, ownerName, profile, dimension, pos, stacks, createdAt);
        }
    }

    public static final Codec<Grave> CODEC = Codec.of(STRICT_CODEC, new com.mojang.serialization.Decoder<>() {
        @Override
        public <T> DataResult<Pair<Grave, T>> decode(DynamicOps<T> ops, T input) {
            return Raw.CODEC.decode(ops, input).map(pair -> pair.mapFirst(Raw::toGrave));
        }
    });

    public Grave withItems(List<ItemStack> items) {
        return new Grave(id, owner, ownerName, profile, dimension, pos, items, createdAt);
    }
}
