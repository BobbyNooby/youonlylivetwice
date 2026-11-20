package bobbynooby.dev.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {placeholders} a text in the messages section is filled with, and the ones it must keep, without which the text
 * would lose what it is for. A text without it is sent as it is.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Placeholders {
    String[] value();

    String[] required() default {};
}
