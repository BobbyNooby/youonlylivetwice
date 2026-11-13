package bobbynooby.dev.hardcore;

import bobbynooby.dev.config.HardcoreConfig;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.core.Guard;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.core.Yolt;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Curse of the Undead: every return from death, by dying or by a totem, takes a max heart, up to a cap, and the
 * hearts come back one at a time with the player's own online play. The hearts lost live in the hardcore store, so a
 * body's death or an offline kill reaches a player who isn't online; the modifier itself is transient and is put back
 * on every join, respawn and change.
 */
public final class Curse {
    static final Identifier MODIFIER = Yolt.id("undead_curse");
    private static final int STATUS_ORDER = 40;
    private static final double TICKS_PER_DAY = 24000.0;
    // 6,000 ticks; vanilla keeps the player's last move or look in milliseconds
    private static final long AFK_MILLIS = 6000L * 50L;
    private static final String NAME = "Curse of the Undead";

    // Hearts a death took, told to the player when they next come back, since a dead or kicked player can't see the
    // action bar. Server thread only.
    private static final Map<UUID, Integer> TELL_ON_RETURN = new HashMap<>();

    private Curse() {}

    static void init() {
        Hardcore.onTick(Curse::tick);
        Hardcore.onRefresh(Curse::refresh);
        Hardcore.onExempt(Curse::clear);
        Hardcore.onDeath(Curse::onDeath);
        Status.register(STATUS_ORDER, Curse::statusLine);
    }

    /** A death, online or not. Only one in survival or adventure counts, whatever the lockout counts. */
    public static void onDeath(UUID uuid, GameType modeAtDeath) {
        if (!on()) {
            return;
        }
        if (!modeAtDeath.isSurvival()) {
            // Still the latest death, so an unkill of it gives back nothing an older one added
            if (HardcoreStates.read(uuid).curseLastDeathAdded != 0) {
                HardcoreStates.update(uuid, s -> s.curseLastDeathAdded = 0);
            }
            return;
        }
        HardcoreConfig.CurseSection cfg = cfg();
        int[] added = new int[1];
        HardcoreStates.update(uuid, s -> added[0] = CurseMaths.onReturn(s, cfg.heartsPerDeath, cfg.maxLostHearts, true));
        if (added[0] > 0) {
            TELL_ON_RETURN.merge(uuid, added[0], Integer::sum);
        }
    }

    /** A totem, or anything with death protection, has just saved this entity: a player, or a body for its owner. */
    public static void onTotem(LivingEntity saved) {
        if (!on()) {
            return;
        }
        Guard.run(() -> totem(saved), "Could not add the Curse of the Undead for a totem on {}", saved.getStringUUID());
    }

    /** Puts the owner's curse on their logout body, so it never has more hearts than they do. */
    public static void applyTo(LivingEntity body, UUID owner) {
        if (!on()) {
            return;
        }
        apply(body, HardcoreStates.read(owner));
    }

    /** Gives back what the latest death added, online or not, and returns how many hearts came back. */
    public static int refund(UUID uuid) {
        int[] given = new int[1];
        HardcoreStates.update(uuid, s -> given[0] = CurseMaths.refundLatestDeath(s));
        if (given[0] > 0) {
            TELL_ON_RETURN.remove(uuid);
        }
        return given[0];
    }

    /** A revive paper's share back, only when the server sets one. Returns how many hearts came back. */
    public static int refundRevivePaper(UUID uuid) {
        if (!on() || cfg().reviveRefundHearts <= 0) {
            return 0;
        }
        int hearts = cfg().reviveRefundHearts;
        int[] given = new int[1];
        HardcoreStates.update(uuid, s -> given[0] = CurseMaths.giveBack(s, hearts));
        return given[0];
    }

    /** Sets the hearts lost, online or not, with nothing left for an unkill to give back. The heal timer carries on. */
    public static void set(UUID uuid, int hearts) {
        TELL_ON_RETURN.remove(uuid);
        HardcoreStates.update(uuid, s -> {
            s.curseHeartsLost = Math.max(hearts, 0);
            s.curseLastDeathAdded = 0;
            if (s.curseHeartsLost == 0) {
                s.curseOnlineTicks = 0;
            }
        });
    }

    /** Puts the stored curse on an online player now, or takes it off while the curse is off or they are exempt. */
    public static void apply(ServerPlayer player) {
        if (StandIn.is(player)) {
            return;
        }
        if (!on() || Hardcore.exempt(player)) {
            clear(player);
            return;
        }
        apply(player, HardcoreStates.of(player));
    }

    private static boolean on() {
        return Settings.on(c -> c.hardcore.curse.enabled);
    }

    private static HardcoreConfig.CurseSection cfg() {
        return Settings.get().hardcore.curse;
    }

    private static long ticksPerHeal(HardcoreConfig.CurseSection cfg) {
        return Math.max(1L, Math.round(cfg.healEveryDays * TICKS_PER_DAY));
    }

    private static void tick(ServerPlayer player) {
        // A player body's curse is set when it is made, from its owner's
        if (StandIn.is(player)) {
            return;
        }
        if (!on()) {
            clear(player);
            return;
        }
        HardcoreConfig.CurseSection cfg = cfg();
        HardcoreState state = HardcoreStates.of(player);
        if (state.curseHeartsLost > 0 && !player.isDeadOrDying() && (cfg.countAfk || active(player))) {
            int before = CurseMaths.shown(state, cfg.maxLostHearts);
            long perHeal = ticksPerHeal(cfg);
            HardcoreStates.update(player.getUUID(), s -> CurseMaths.onOnlineTick(s, cfg.healHearts, perHeal));
            tellChange(player, before);
        }
        // Every tick, so a change to the cap or the switch shows at once; it only touches the attribute on a change
        apply(player, HardcoreStates.of(player));
    }

    private static void refresh(ServerPlayer player) {
        apply(player);
        Integer lost = TELL_ON_RETURN.remove(player.getUUID());
        if (lost != null && on()) {
            Hardcore.actionBar(player, NAME + ": −" + hearts(lost) + ".");
        }
    }

    private static void clear(ServerPlayer player) {
        if (StandIn.is(player)) {
            return;
        }
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.removeModifier(MODIFIER);
        }
    }

    private static void totem(LivingEntity saved) {
        HardcoreConfig.CurseSection cfg = cfg();
        if (saved instanceof ServerPlayer player && !StandIn.is(player)) {
            if (Hardcore.exempt(player)) {
                return;
            }
            int before = CurseMaths.shown(HardcoreStates.of(player), cfg.maxLostHearts);
            HardcoreStates.update(player.getUUID(), s -> CurseMaths.onReturn(s, cfg.heartsPerTotem, cfg.maxLostHearts, false));
            // Vanilla set the health to 1 already, so the lower maximum can't kill
            apply(player, HardcoreStates.of(player));
            tellChange(player, before);
            return;
        }
        // The body is its owner, so the owner pays for its totem
        Legs.ownerOfBody(saved).ifPresent(owner -> {
            HardcoreStates.update(owner, s -> CurseMaths.onReturn(s, cfg.heartsPerTotem, cfg.maxLostHearts, false));
            applyTo(saved, owner);
        });
    }

    // Only touches the attribute when the amount changes, since every change is sent to the client
    private static void apply(LivingEntity entity, HardcoreState state) {
        AttributeInstance maxHealth = entity.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        double amount = CurseMaths.modifier(state, cfg().maxLostHearts);
        AttributeModifier current = maxHealth.getModifier(MODIFIER);
        if (amount == 0.0) {
            maxHealth.removeModifier(MODIFIER);
            return;
        }
        if (current != null && current.amount() == amount) {
            return;
        }
        // When the maximum drops, vanilla lowers the health to it; when it rises, the health stays and regen fills it
        maxHealth.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, amount, AttributeModifier.Operation.ADD_VALUE));
    }

    // A loss stored over a lowered cap changes nothing the player can see, so it isn't announced
    private static void tellChange(ServerPlayer player, int before) {
        int now = CurseMaths.shown(HardcoreStates.of(player), cfg().maxLostHearts);
        if (now > before) {
            Hardcore.actionBar(player, NAME + ": −" + hearts(now - before) + ".");
        } else if (now < before) {
            Hardcore.actionBar(player, NAME + ": +" + hearts(before - now) + ".");
        }
    }

    private static boolean active(ServerPlayer player) {
        return Util.getMillis() - player.getLastActionTime() < AFK_MILLIS;
    }

    private static String hearts(int count) {
        return count == 1 ? "1 max heart" : count + " max hearts";
    }

    private static Optional<Component> statusLine(ServerPlayer player) {
        if (!on() || Hardcore.exempt(player) || StandIn.is(player)) {
            return Optional.empty();
        }
        HardcoreConfig.CurseSection cfg = cfg();
        HardcoreState state = HardcoreStates.of(player);
        int shown = CurseMaths.shown(state, cfg.maxLostHearts);
        if (shown <= 0) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder(NAME + "  −" + hearts(shown));
        if (shown >= cfg.maxLostHearts) {
            text.append(" (the most it takes)");
        }
        if (cfg.healHearts > 0) {
            long left = ticksPerHeal(cfg) - state.curseOnlineTicks;
            text.append(" · next ").append(cfg.healHearts == 1 ? "heart" : cfg.healHearts + " hearts")
                    .append(" back after ").append(CurseMaths.playTime(left)).append(" more play");
        }
        return Optional.of(Status.line(text.toString(), "Every return from death, by dying or by a totem, takes a max"
                + " heart, up to " + hearts(cfg.maxLostHearts) + " in all. They come back one at a time as you play;"
                + " only your own time online counts."));
    }
}
