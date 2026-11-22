package bobbynooby.dev.coords;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.WorldCoordinates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/**
 * Coordinates a player types in a command. Absolute X and Z come in the frame the player's client sends in, so its offset
 * comes off them when the command runs. Only a command parsed from the player's own chat line counts: functions,
 * advancement rewards, scheduled functions, command blocks and the console keep real coordinates, even when they run as
 * the player.
 */
public final class TypedCoordinates {
    // The incoming offset of the player whose typed command is being parsed, set only while it is. Commands are parsed on
    // the server thread, but a thread-local keeps any other thread out of it.
    private static final ThreadLocal<Offset> TYPING = new ThreadLocal<>();

    private TypedCoordinates() {}

    /** Parses a command the player typed, so that the spots in it are read in their frame. */
    public static <T> T parse(ServerPlayer player, Supplier<T> parse) {
        Offset in = CoordinateOffsets.incomingOffset(player);
        if (in.isZero()) {
            return parse.get();
        }
        TYPING.set(in);
        try {
            return parse.get();
        } finally {
            TYPING.remove();
        }
    }

    /** A parsed spot: in a typed command, the typist's offset comes off its absolute X and Z when it is read. */
    public static Coordinates spot(Coordinates parsed) {
        Offset in = TYPING.get();
        if (in == null || !(parsed instanceof WorldCoordinates typed) || (typed.isXRelative() && typed.isZRelative())) {
            return parsed;
        }
        return new TypedSpot(typed, in);
    }

    /**
     * The same for a 3D position argument, which is a spot when it centres whole numbers. One that doesn't is a vector, such
     * as a particle spread, whose numbers stay as typed: a spread of 0 read as a spot would go out as the offset itself.
     */
    public static Coordinates spotOrVector(Coordinates parsed, boolean centred) {
        return centred ? spot(parsed) : parsed;
    }

    // Read as vanilla reads it, then moved by the offset on each absolute axis. A relative step is from a real spot already.
    private record TypedSpot(WorldCoordinates typed, Offset in) implements Coordinates {
        @Override
        public Vec3 getPosition(CommandSourceStack source) {
            Vec3 position = typed.getPosition(source);
            return new Vec3(typed.isXRelative() ? position.x : position.x - in.x(), position.y,
                    typed.isZRelative() ? position.z : position.z - in.z());
        }

        @Override
        public Vec2 getRotation(CommandSourceStack source) {
            return typed.getRotation(source);
        }

        @Override
        public boolean isXRelative() {
            return typed.isXRelative();
        }

        @Override
        public boolean isYRelative() {
            return typed.isYRelative();
        }

        @Override
        public boolean isZRelative() {
            return typed.isZRelative();
        }
    }
}
