package bobbynooby.dev.hardcore;

/**
 * What the blood moon tells players: the warning's title and chat line, the line as it rises, the line for one that is
 * already up (to a player who arrives, or after a restart), the line as it sets, and its boss bar's title. YOLT's
 * config has each as a key of its messages, whose defaults come from here, so each text is written once.
 */
public record BloodMoonTexts(String warningTitle, String warningLine, String risenLine, String upLine, String setLine,
                             String barTitle) {
    public static final BloodMoonTexts DEFAULTS = new BloodMoonTexts(
            "The moon will rise red tonight…",
            "A blood moon rises tonight. Find somewhere safe before dark.",
            "The blood moon has risen. Mobs are stronger and more numerous until dawn.",
            "A blood moon is up. Mobs are stronger and more numerous until dawn.",
            "The blood moon has set.",
            "Blood Moon");
}
