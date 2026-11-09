package bobbynooby.dev.mixin;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

// Brigadier can add commands but not remove them; this lets us take vanilla's /w out before adding ours
@Mixin(CommandNode.class)
public interface CommandNodeAccessor {
    @Accessor("children")
    Map<String, CommandNode<?>> yoltChildren();

    @Accessor("literals")
    Map<String, LiteralCommandNode<?>> yoltLiterals();
}
