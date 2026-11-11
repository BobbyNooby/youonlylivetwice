package bobbynooby.dev.mixin;

import bobbynooby.dev.config.Settings;
import bobbynooby.dev.visibility.DebugPrivacy;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class DebugPrivacyGameRuleMixin {
    // Vanilla has just sent every player the rule's flag, which would undo each player's own. With the switch off the
    // rule's flag is already everyone's own.
    @Inject(method = "onGameRuleChanged", at = @At("TAIL"))
    private void afterGameRuleChanged(GameRule<?> rule, Object value, CallbackInfo ci) {
        if (rule == GameRules.REDUCED_DEBUG_INFO && Settings.on(c -> c.hide.debugScreen)) {
            DebugPrivacy.applyAll((MinecraftServer) (Object) this);
        }
    }
}
