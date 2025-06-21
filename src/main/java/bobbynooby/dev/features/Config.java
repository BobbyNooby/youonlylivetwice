package bobbynooby.dev.features;

import bobbynooby.dev.YouOnlyLiveTwice;
import bobbynooby.dev.database.DatabaseHandler;
import bobbynooby.dev.graves.GravesRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.GameRules;

import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Config {
    private static final String CONFIG_FILE = "config/you-only-live-twice.json";

    private static final double DEFAULT_END_BORDER_SCALE = 2.0;
    private static final int DEFAULT_WORLD_DIAMETER = 10000;

    private static final int DEFAULT_RESPAWN_COOLDOWN = 7;

    static double END_BORDER_SCALE = DEFAULT_END_BORDER_SCALE; // Default value
    static int WORLD_DIAMETER = DEFAULT_WORLD_DIAMETER; // Default value
    static int RESPAWN_COOLDOWN = DEFAULT_RESPAWN_COOLDOWN; // Default value


    // Initialize configuration (load or create the config file)
    public static void initialize() {
        Path configPath = getConfigPath();

        if (Files.exists(configPath)) {

            YouOnlyLiveTwice.LOGGER.info("File at {} exists", configPath);
            loadConfig(configPath); // Load the configuration
        } else {
            YouOnlyLiveTwice.LOGGER.info("File at {} does not exist", configPath);

            try {
                Files.createDirectories(configPath.getParent());
                Files.createFile(configPath);
                saveConfig(configPath); // Save default values if the file doesn't exist
            } catch (Exception e) {
                YouOnlyLiveTwice.LOGGER.error("Failed to create config file: ", e);
            }

            if (Files.exists(configPath)) {
                YouOnlyLiveTwice.LOGGER.info("File at {} created.", configPath);
            } else {
                YouOnlyLiveTwice.LOGGER.error("File at {} could not be created.", configPath);
            }
        }


        ServerLifecycleEvents.SERVER_STARTED.register((server) -> {
            //Initialize DB
            DatabaseHandler.initialize(server);

            // Initialize GameRules
            server.getGameRules().get(GameRules.DO_IMMEDIATE_RESPAWN).set(true, server);
        });

        // Initialize WorldBorders
        ServerLifecycleEvents.SERVER_STARTED.register(WorldBorders::setupWorldBorder);

        // Initialize Custom Portal
        CustomCommands.register();

        // Initialize Custom grave blocks
        GravesRegistry.initialize();

        // Initialize PseudoHardcore
        PseudoHardcore.initialize();

        // Initialize Graves
        Graves.initialize();

        // Initialize NameTagHider
        NameTagHider.registerInitTeams();
        NameTagHider.registerAddPlayerToTeamOnJoin();

    }

    // Load the configuration from the JSON file
    private static void loadConfig(Path configPath) {
        try (FileReader reader = new FileReader(configPath.toFile())) {
            Gson gson = new Gson();
            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);

            // Check for missing or invalid values, replace with defaults if necessary
            if (jsonObject.has("END_BORDER_SCALE")) {
                try {
                    END_BORDER_SCALE = jsonObject.get("END_BORDER_SCALE").getAsInt();
                } catch (NumberFormatException e) {
                    YouOnlyLiveTwice.LOGGER.warn("Invalid value for END_BORDER_SCALE, using default: {}", DEFAULT_END_BORDER_SCALE);
                    END_BORDER_SCALE = DEFAULT_END_BORDER_SCALE;
                    saveConfig(configPath); // Save the corrected config
                    return; // Exit after fixing and saving
                }
            } else {
                YouOnlyLiveTwice.LOGGER.warn("Missing END_BORDER_SCALE, using default: {}", DEFAULT_END_BORDER_SCALE);
                END_BORDER_SCALE = DEFAULT_END_BORDER_SCALE;
                saveConfig(configPath); // Save the corrected config
                return; // Exit after fixing and saving
            }

            if (jsonObject.has("WORLD_DIAMETER")) {
                try {
                    WORLD_DIAMETER = jsonObject.get("WORLD_DIAMETER").getAsInt();
                } catch (NumberFormatException e) {
                    YouOnlyLiveTwice.LOGGER.warn("Invalid value for WORLD_DIAMETER, using default: {}", DEFAULT_WORLD_DIAMETER);
                    WORLD_DIAMETER = DEFAULT_WORLD_DIAMETER;
                    saveConfig(configPath); // Save the corrected config
                    return; // Exit after fixing and saving
                }
            } else {
                YouOnlyLiveTwice.LOGGER.warn("Missing WORLD_DIAMETER, using default: {}", DEFAULT_WORLD_DIAMETER);
                WORLD_DIAMETER = DEFAULT_WORLD_DIAMETER;
                saveConfig(configPath); // Save the corrected config
                return; // Exit after fixing and saving
            }

            if (jsonObject.has("RESPAWN_COOLDOWN")) {
                try {
                    RESPAWN_COOLDOWN = jsonObject.get("RESPAWN_COOLDOWN").getAsInt();
                } catch (NumberFormatException e) {
                    YouOnlyLiveTwice.LOGGER.warn("Invalid value for RESPAWN_COOLDOWN, using default: {}", DEFAULT_RESPAWN_COOLDOWN);
                    RESPAWN_COOLDOWN = DEFAULT_RESPAWN_COOLDOWN;
                    saveConfig(configPath);
                    return;
                }
            } else {
                YouOnlyLiveTwice.LOGGER.warn("Missing RESPAWN_COOLDOWN, using default: {}", DEFAULT_RESPAWN_COOLDOWN);
                RESPAWN_COOLDOWN = DEFAULT_RESPAWN_COOLDOWN;
                saveConfig(configPath);
                return;
            }

            YouOnlyLiveTwice.LOGGER.info("Configuration loaded successfully.");
        } catch (JsonParseException e) {
            YouOnlyLiveTwice.LOGGER.error("Failed to parse config file, using default values.", e);
            setDefaults(); // Set default values if JSON parsing fails
            saveConfig(configPath); // Save the corrected config
        } catch (Exception e) {
            YouOnlyLiveTwice.LOGGER.error("An error occurred while loading the config file:", e);
            setDefaults(); // Fallback to defaults in case of any other error
            saveConfig(configPath); // Save the corrected config
        }
    }

    // Set default values if the configuration is invalid or missing
    private static void setDefaults() {
        END_BORDER_SCALE = DEFAULT_END_BORDER_SCALE;
        WORLD_DIAMETER = DEFAULT_WORLD_DIAMETER;
        RESPAWN_COOLDOWN = DEFAULT_RESPAWN_COOLDOWN;
    }

    // Save the current configuration to the JSON file
    public static void saveConfig(Path configPath) {
        try (FileWriter writer = new FileWriter(configPath.toFile())) {
            Gson gson = new Gson();
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("END_BORDER_SCALE", END_BORDER_SCALE);
            jsonObject.addProperty("WORLD_DIAMETER", WORLD_DIAMETER);
            jsonObject.addProperty("RESPAWN_COOLDOWN", RESPAWN_COOLDOWN);

            gson.toJson(jsonObject, writer);
        } catch (Exception e) {
            YouOnlyLiveTwice.LOGGER.error("Failed to save config file: ", e);
        }
    }

    // Getters and Setters for the configuration properties
    public static int getWorldDiameter() {
        return WORLD_DIAMETER;
    }

    public static void setWorldDiameter(int diameter) {
        WORLD_DIAMETER = diameter;
        saveConfig(getConfigPath()); // Save the updated config immediately
    }

    public static double getEndBorderScale() {
        return END_BORDER_SCALE;
    }

    public static void setEndBorderScale(double scale) {
        END_BORDER_SCALE = scale;
        saveConfig(getConfigPath()); // Save the updated config immediately
    }

    public static int getRespawnCooldown() {
        return RESPAWN_COOLDOWN;
    }

    public static void setRespawnCooldown(int cooldown) {
        RESPAWN_COOLDOWN = cooldown;
        saveConfig(getConfigPath());
    }

    // Get the path to the config file
    private static Path getConfigPath() {
        return Paths.get(CONFIG_FILE);
    }
}
