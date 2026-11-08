package bobbynooby.dev.graves;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.Level;

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
    public static final Codec<Grave> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Grave::id),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Grave::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(Grave::ownerName),
            ResolvableProfile.CODEC.fieldOf("profile").forGetter(Grave::profile),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Grave::dimension),
            BlockPos.CODEC.fieldOf("pos").forGetter(Grave::pos),
            ItemStack.CODEC.listOf().fieldOf("items").forGetter(Grave::items),
            Codec.LONG.fieldOf("created_at").forGetter(Grave::createdAt)
    ).apply(i, Grave::new));

    public Grave withItems(List<ItemStack> items) {
        return new Grave(id, owner, ownerName, profile, dimension, pos, items, createdAt);
    }
}
