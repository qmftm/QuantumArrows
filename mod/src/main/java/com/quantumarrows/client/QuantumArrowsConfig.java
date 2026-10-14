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

    public int targetSlot = DEFAULT_TARGET_SLOT;

    public static QuantumArrowsConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("quantumarrows.json");
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
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(config, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write {}", path, e);
        }
        return config;
    }
}
