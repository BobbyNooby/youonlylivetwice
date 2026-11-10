package bobbynooby.dev.mixin;

import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// An entry of vanilla's name cache, whose class is private
@Mixin(targets = "net.minecraft.server.players.CachedUserNameToIdResolver$GameProfileInfo")
public interface NameCacheEntryAccessor {
    @Accessor("nameAndId")
    NameAndId yoltNameAndId();
}
