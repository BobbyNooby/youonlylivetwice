package bobbynooby.dev.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** When a change to this config key takes effect. A key without it takes effect at once. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Applies {
    When value();

    enum When {
        NOW(""),
        NEXT_JOIN(" (applies when a player next joins)"),
        NEXT_FRAME(" (applies when a player next joins, respawns or changes dimension)"),
        NEW_BODIES(" (applies to new bodies)"),
        NEW_DEATHS(" (applies to deaths from now on)"),
        RESTART(" (needs a restart)");

        private final String note;

        When(String note) {
            this.note = note;
        }

        /** What a reply adds after the new value, so the admin isn't surprised when nothing seems to change. */
        public String note() {
            return note;
        }
    }
}
