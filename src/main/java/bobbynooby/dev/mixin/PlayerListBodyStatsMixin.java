package bobbynooby.dev.mixin;

import bobbynooby.dev.bodies.PlayerBody;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// The player list keeps these by id until the player leaves, which a body never does, and saves them to disk.
// The stats lookup can also rename an old name-keyed file, which would hand the owner's stats to the body.
@Mixin(PlayerList.class)
public abstract class PlayerListBodyStatsMixin {
    @Inject(method = "getPlayerStats", at = @At("HEAD"), cancellable = true)
    private void ownStatsForBody(Player player, CallbackInfoReturnable<ServerStatsCounter> cir) {
        if (player instanceof PlayerBody body) {
            cir.setReturnValue(PlayerBody.detachedStats(body));
        }
    }

    @Inject(method = "getPlayerAdvancements", at = @At("HEAD"), cancellable = true)
    private void ownAdvancementsForBody(ServerPlayer player, CallbackInfoReturnable<PlayerAdvancements> cir) {
        if (player instanceof PlayerBody body) {
            cir.setReturnValue(PlayerBody.detachedAdvancements(body));
        }
    }
}
