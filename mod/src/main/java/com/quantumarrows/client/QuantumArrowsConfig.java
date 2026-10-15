package com.quantumarrows.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stored in {@code config/quantumarrows.json}.
 *
 * <p>{@code targetSlot} is a player inventory index: 0-8 hotbar, 9-35 main inventory
 * (9-17 top row, 18-26 middle row, 27-35 bottom row), 40 offhand.
 * The default 10 is the second slot of the top row.
 */
public final class QuantumArrowsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumarrows");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final int DEFAULT_TARGET_SLOT = 10;

    /** When the wheel key is allowed to open the wheel. */
    public enum Activation {
        /** Only while a bow or crossbow is held (the wheel key keeps its normal function otherwise). */
        HOLD_ON_BOW("HOLD ON BOW"),
        /** Always, whatever is held. */
        EVERYTHING("EVERYTHING"),
        /** Never opens; the key keeps only its other bindings' behavior. */
        DISABLE("DISABLE");

        private final String label;

        Activation(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("quantumarrows.json");
    }

    public int targetSlot = DEFAULT_TARGET_SLOT;
    public Activation activation = Activation.HOLD_ON_BOW;
    /** Whether a bow or crossbow held in the offhand counts for {@link Activation#HOLD_ON_BOW}. */
    public boolean offhandBow = true;
    /** Finish loading a crossbow automatically once it is fully drawn, even while use is still held. */
    public boolean autoLoadCrossbow = true;

    public static QuantumArrowsConfig load() {
        Path path = path();
        QuantumArrowsConfig config = null;
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                config = GSON.fromJson(reader, QuantumArrowsConfig.class);
            } catch (Exception e) {
                LOGGER.warn("Failed to read {}, using defaults", path, e);
            }
        }
        if (config == null) {
            config = new QuantumArrowsConfig();
        }
        if (!ArrowInventory.isValidTargetSlot(config.targetSlot)) {
            LOGGER.warn("Invalid targetSlot {} in {}, using {}", config.targetSlot, path, DEFAULT_TARGET_SLOT);
            config.targetSlot = DEFAULT_TARGET_SLOT;
        }
        if (config.activation == null) {
            config.activation = Activation.HOLD_ON_BOW;
        }
        config.save();
        return config;
    }

    public void save() {
        Path path = path();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write {}", path, e);
        }
    }
}
