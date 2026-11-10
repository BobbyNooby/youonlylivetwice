package bobbynooby.dev.skins;

import org.jspecify.annotations.Nullable;

/** A profile's "textures" property: the skin data and Mojang's signature over it, which other players' clients need. */
public record SkinTextures(String value, @Nullable String signature) {
}
