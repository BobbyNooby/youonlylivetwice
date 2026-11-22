package bobbynooby.dev.hosted;

import bobbynooby.dev.bodies.BodyRecord;
import bobbynooby.dev.bodies.BodyRules;
import bobbynooby.dev.bodies.BodyStore;
import bobbynooby.dev.config.Settings;
import bobbynooby.dev.config.YoltConfig;
import bobbynooby.dev.core.StandIn;
import bobbynooby.dev.hardcore.BloodMoonTexts;
import bobbynooby.dev.hardcore.HardcoreConfig;
import bobbynooby.dev.hardcore.HardcoreHost;
import bobbynooby.dev.hardcore.YoltHardcore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Optional;
import java.util.UUID;

/**
 * The hardcore layer is the YOLT Hardcore mod, shipped inside YOLT's jar, which YOLT's fabric.mod.json declares it
 * hosts. YOLT hands it the hardcore block and the six blood moon texts of its own config, live, so a reload or /yolt
 * config change applies at once, its single-player switch, and its stand-ins and bodies. The mod sets itself up inside
 * this call, so its hooks register here, after YOLT's own.
 */
public final class HardcoreLayer {
    private HardcoreLayer() {}

    public static void init() {
        YoltHardcore.hostedBy(new HardcoreHost() {
            @Override
            public String name() {
                return "You Only Live Twice";
            }

            @Override
            public boolean active() {
                return Settings.active();
            }

            @Override
            public HardcoreConfig.HardcoreSection settings() {
                return Settings.get().hardcore;
            }

            @Override
            public BloodMoonTexts texts() {
                YoltConfig.MessagesSection messages = Settings.get().messages;
                return new BloodMoonTexts(messages.bloodMoonWarningTitle, messages.bloodMoonWarningLine,
                        messages.bloodMoonRisenLine, messages.bloodMoonUpLine, messages.bloodMoonSetLine,
                        messages.bloodMoonBarTitle);
            }

            @Override
            public boolean isStandIn(Entity entity) {
                return StandIn.is(entity);
            }

            @Override
            public boolean isBody(Entity entity) {
                return BodyRules.isBody(entity);
            }

            // Mannequin and player bodies alike are found by their entity in the body store
            @Override
            public Optional<UUID> bodyOwner(LivingEntity entity) {
                if (!BodyRules.isBody(entity) || !(entity.level() instanceof ServerLevel level)) {
                    return Optional.empty();
                }
                return BodyStore.get(level.getServer()).byEntity(entity.getUUID()).map(BodyRecord::owner);
            }
        });
    }
}
