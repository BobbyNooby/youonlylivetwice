package bobbynooby.dev.mixin;

import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Player.class)
public interface PlayerInvoker {
    // Lets graves destroy curse of vanishing items exactly as a vanilla death does
    @Invoker("destroyVanishingCursedItems")
    void invokeDestroyVanishingCursedItems();
}
