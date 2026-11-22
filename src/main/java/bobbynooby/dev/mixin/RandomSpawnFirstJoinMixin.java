package bobbynooby.dev.mixin;

import bobbynooby.dev.world.RandomSpawn;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Optional;

// The last step of a login, run when the client has finished its configuration: vanilla makes the player and puts them
// at the spot its spawn task found or read as they logged in, then places them in the world
@Mixin(targets = "net.minecraft.server.network.config.PrepareSpawnTask$Ready")
public abstract class RandomSpawnFirstJoinMixin {
    // The save is the one vanilla has just read for this player. With no position in it, the task searched for the spot,
    // as for a first join; a returning player goes back to the position they saved. The position is read the way the
    // task's start reads it to decide on the search. That read is deprecated in 26.3, and vanilla still uses it there.
    @SuppressWarnings("deprecation")
    @ModifyArg(method = "spawn", index = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;snapTo(Lnet/minecraft/world/phys/Vec3;FF)V"))
    private Vec3 checkFirstJoinSpot(Vec3 spot, @Local ServerPlayer player, @Local Optional<ValueInput> saved) {
        boolean searched = saved.flatMap(input -> input.read(ServerPlayer.SavedPosition.MAP_CODEC))
                .flatMap(ServerPlayer.SavedPosition::position)
                .isEmpty();
        return searched ? RandomSpawn.firstJoinSpot(player.level(), spot, player.nameAndId().name()) : spot;
    }
}
