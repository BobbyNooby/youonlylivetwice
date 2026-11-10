package bobbynooby.dev.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerList.class)
public interface PlayerListInvoker {
    // Vanilla's own save of one online player, the one a logout or an autosave runs
    @Invoker("save")
    void yoltSave(ServerPlayer player);
}
