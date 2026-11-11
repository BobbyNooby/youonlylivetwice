package bobbynooby.dev.graves;

import bobbynooby.dev.core.Stacks;
import bobbynooby.dev.core.Yolt;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A dead player's items, kept at one block until someone revives them or breaks the grave. deathPos is where the owner
 * died, so they can be told when the grave is far from there; null for a grave made before graves kept it.
 * compassPending is true while the owner is still owed a compass to this grave.
 */
public record Grave(
        UUID id,
        UUID owner,
        String ownerName,
        ResolvableProfile profile,
        ResourceKey<Level> dimension,
        BlockPos pos,
        List<ItemStack> items,
        long createdAt,
        @Nullable GlobalPos deathPos,
        boolean compassPending
) {
    // Compact constructor: an empty stack can't be encoded and would crash the world save
    public Grave {
        items = List.copyOf(Stacks.nonEmpty(items));
    }

    /** A grave with no death spot known. */
    public Grave(UUID id, UUID owner, String ownerName, ResolvableProfile profile, ResourceKey<Level> dimension, BlockPos pos,
                 List<ItemStack> items, long createdAt) {
        this(id, owner, ownerName, profile, dimension, pos, items, createdAt, null);
    }

    /** A grave that owes no compass. */
    public Grave(UUID id, UUID owner, String ownerName, ResolvableProfile profile, ResourceKey<Level> dimension, BlockPos pos,
                 List<ItemStack> items, long createdAt, @Nullable GlobalPos deathPos) {
        this(id, owner, ownerName, profile, dimension, pos, items, createdAt, deathPos, false);
    }

    private static final Codec<Grave> STRICT_CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Grave::id),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Grave::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(Grave::ownerName),
            ResolvableProfile.CODEC.fieldOf("profile").forGetter(Grave::profile),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Grave::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Grave::pos),
            ItemStack.CODEC.listOf().fieldOf("items").forGetter(Grave::items),
            Codec.LONG.fieldOf("created_at").forGetter(Grave::createdAt),
            // Optional, so a grave saved before it was kept still reads
            GlobalPos.CODEC.optionalFieldOf("death_pos").forGetter(grave -> Optional.ofNullable(grave.deathPos())),
            // Optional too, for a grave saved before compasses. False isn't written, so a grave owing none saves as before.
            Codec.BOOL.optionalFieldOf("compass_pending", false).forGetter(Grave::compassPending)
    ).apply(i, (id, owner, ownerName, profile, dimension, pos, items, createdAt, deathPos, compassPending) ->
            new Grave(id, owner, ownerName, profile, dimension, pos, items, createdAt, deathPos.orElse(null), compassPending)));

    // Same fields, but items stay undecoded so one bad stack can be skipped instead of failing the grave. A grave that
    // left out an item, or part of one, comes back as an error that still carries it, so its store knows. The death
    // spot too: one that can't be read is only a hint lost, so the grave reads whole without it. So is a compass flag
    // that can't be read, which reads as no compass owed. Either is logged, so an admin can tell why.
    private record Raw(UUID id, UUID owner, String ownerName, ResolvableProfile profile,
                       ResourceKey<Level> dimension, BlockPos pos, List<Dynamic<?>> items, long createdAt,
                       Optional<Dynamic<?>> deathPos, Optional<Dynamic<?>> compassPending) {
        static final Codec<Raw> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Raw::id),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Raw::owner),
                Codec.STRING.fieldOf("owner_name").forGetter(Raw::ownerName),
                ResolvableProfile.CODEC.fieldOf("profile").forGetter(Raw::profile),
                Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Raw::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Raw::pos),
                Codec.PASSTHROUGH.listOf().fieldOf("items").forGetter(Raw::items),
                Codec.LONG.fieldOf("created_at").forGetter(Raw::createdAt),
                Codec.PASSTHROUGH.optionalFieldOf("death_pos").forGetter(Raw::deathPos),
                Codec.PASSTHROUGH.optionalFieldOf("compass_pending").forGetter(Raw::compassPending)
        ).apply(i, Raw::new));

        DataResult<Grave> toGrave() {
            List<ItemStack> stacks = new ArrayList<>();
            int unread = 0;
            for (Dynamic<?> entry : items) {
                DataResult<ItemStack> parsed = ItemStack.CODEC.parse(entry);
                if (parsed.isError()) {
                    unread++;
                }
                parsed.resultOrPartial(error -> Yolt.LOGGER.error(
                        "Skipping unreadable item in grave of {} at {} {}: {}", ownerName, dimension.identifier(), pos, error))
                        .ifPresent(stacks::add);
            }
            GlobalPos died = deathPos.flatMap(entry -> GlobalPos.CODEC.parse(entry).resultOrPartial(error -> Yolt.LOGGER.warn(
                    "Ignoring the unreadable death spot of {}'s grave at {} {}: {}", ownerName, dimension.identifier(), pos, error)))
                    .orElse(null);
            boolean owesCompass = compassPending.flatMap(entry -> Codec.BOOL.parse(entry).resultOrPartial(error -> Yolt.LOGGER.warn(
                    "Ignoring the unreadable compass flag of {}'s grave at {} {}: {}", ownerName, dimension.identifier(), pos, error)))
                    .orElse(false);
            Grave grave = new Grave(id, owner, ownerName, profile, dimension, pos, stacks, createdAt, died, owesCompass);
            int left = unread;
            return left == 0 ? DataResult.success(grave)
                    : DataResult.error(() -> left + " items in the grave of " + ownerName + " could not be read whole", grave);
        }
    }

    public static final Codec<Grave> CODEC = Codec.of(STRICT_CODEC, new com.mojang.serialization.Decoder<>() {
        @Override
        public <T> DataResult<Pair<Grave, T>> decode(DynamicOps<T> ops, T input) {
            return Raw.CODEC.decode(ops, input)
                    .flatMap(pair -> pair.getFirst().toGrave().map(grave -> Pair.of(grave, pair.getSecond())));
        }
    });

    public Grave withItems(List<ItemStack> items) {
        return new Grave(id, owner, ownerName, profile, dimension, pos, items, createdAt, deathPos, compassPending);
    }

    public Grave withPos(BlockPos pos) {
        return new Grave(id, owner, ownerName, profile, dimension, pos, items, createdAt, deathPos, compassPending);
    }

    public Grave withCompassPending(boolean compassPending) {
        return new Grave(id, owner, ownerName, profile, dimension, pos, items, createdAt, deathPos, compassPending);
    }
}
