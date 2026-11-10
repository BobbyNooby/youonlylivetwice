package bobbynooby.dev.mixin;

import net.minecraft.server.players.CachedUserNameToIdResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

// Lets SkinOwners read vanilla's name cache without its lookup, which asks Mojang on a miss
@Mixin(CachedUserNameToIdResolver.class)
public interface NameCacheAccessor {
    // Keyed by the name in lower case
    @Accessor("profilesByName")
    Map<String, ?> yoltProfilesByName();
}
