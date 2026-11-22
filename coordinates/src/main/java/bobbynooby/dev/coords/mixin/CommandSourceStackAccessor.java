package bobbynooby.dev.coords.mixin;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// Lets /yolt-coordinates real tell who ran it: /execute as changes the entity a command runs as, but its replies still
// go to this source, the one who ran it
@Mixin(CommandSourceStack.class)
public interface CommandSourceStackAccessor {

    @Accessor("source")
    CommandSource yoltSource();
}
