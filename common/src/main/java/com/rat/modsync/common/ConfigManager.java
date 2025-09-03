package com.rat.modsync.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Manages client and server configuration for ModSync
 */
public class ConfigManager {
    private static final Logger LOGGER = Logger.getLogger(ConfigManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Platform platform;
    private final Path configDir;
    private ClientConfig clientConfig;
    private ServerConfig serverConfig;

    public ConfigManager(Platform platform) {
        this.platform = platform;
        this.configDir = platform.getModsDirectory().getParent().resolve("config").resolve("modsync");

        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            LOGGER.warning("Failed to create config directory: " + e.getMessage());
        }

        loadConfigs();
    }

    private void loadConfigs() {
        if (platform.isClient()) {
            clientConfig = loadConfig("client.json", ClientConfig.class, ClientConfig::createDefault);
        } else if (platform.isServer()) {
            serverConfig = loadConfig("server.json", ServerConfig.class, ServerConfig::createDefault);
        }
    }

    private <T> T loadConfig(String fileName, Class<T> clazz, ConfigSupplier<T> defaultSupplier) {
        Path configFile = configDir.resolve(fileName);

        if (!Files.exists(configFile)) {
            T defaultConfig = defaultSupplier.get();
            saveConfig(fileName, defaultConfig);
            return defaultConfig;
        }

        try {
            String json = Files.readString(configFile);
            return GSON.fromJson(json, clazz);
        } catch (Exception e) {
            LOGGER.warning("Failed to load " + fileName + ", using defaults: " + e.getMessage());
            T defaultConfig = defaultSupplier.get();
            saveConfig(fileName, defaultConfig);
            return defaultConfig;
        }
    }

    private <T> void saveConfig(String fileName, T config) {
        Path configFile = configDir.resolve(fileName);
        try {
            String json = GSON.toJson(config);
            Files.writeString(configFile, json);
        } catch (IOException e) {
            LOGGER.warning("Failed to save " + fileName + ": " + e.getMessage());
        }
    }

    public ClientConfig getClientConfig() { return clientConfig; }
    public ServerConfig getServerConfig() { return serverConfig; }

    public void saveClientConfig() {
        saveConfig("client.json", clientConfig);
    }

    public void saveServerConfig() {
        saveConfig("server.json", serverConfig);
    }

    // Pending connection management for auto-rejoin
    public void setPendingConnection(String serverAddress) {
        Path pendingFile = configDir.resolve("pending_connection.txt");
        try {
            Files.writeString(pendingFile, serverAddress);
        } catch (IOException e) {
            LOGGER.warning("Failed to save pending connection: " + e.getMessage());
        }
    }

    public String getPendingConnection() {
        Path pendingFile = configDir.resolve("pending_connection.txt");
        if (!Files.exists(pendingFile)) {
            return null;
        }

        try {
            return Files.readString(pendingFile).trim();
        } catch (IOException e) {
            LOGGER.warning("Failed to read pending connection: " + e.getMessage());
            return null;
        }
    }

    public void clearPendingConnection() {
        Path pendingFile = configDir.resolve("pending_connection.txt");
        try {
            Files.deleteIfExists(pendingFile);
        } catch (IOException e) {
            LOGGER.warning("Failed to clear pending connection: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ConfigSupplier<T> {
        T get();
    }

    /**
     * Client-side configuration
     */
    public static class ClientConfig {
        private boolean autoAcceptDownloads = false;
        private boolean autoRestart = true;
        private boolean autoRejoin = true;
        private DownloadSource defaultDownloadSource = DownloadSource.SERVER;
        private boolean showMismatchPrompts = true;

        public static ClientConfig createDefault() {
            return new ClientConfig();
        }

        // Getters
        public boolean isAutoAcceptDownloads() { return autoAcceptDownloads; }
        public boolean isAutoRestart() { return autoRestart; }
        public boolean isAutoRejoin() { return autoRejoin; }
        public DownloadSource getDefaultDownloadSource() { return defaultDownloadSource; }
        public boolean isShowMismatchPrompts() { return showMismatchPrompts; }

        // Setters
        public void setAutoAcceptDownloads(boolean autoAcceptDownloads) {
            this.autoAcceptDownloads = autoAcceptDownloads;
        }
        public void setAutoRestart(boolean autoRestart) {
            this.autoRestart = autoRestart;
        }
        public void setAutoRejoin(boolean autoRejoin) {
            this.autoRejoin = autoRejoin;
        }
        public void setDefaultDownloadSource(DownloadSource defaultDownloadSource) {
            this.defaultDownloadSource = defaultDownloadSource;
        }
        public void setShowMismatchPrompts(boolean showMismatchPrompts) {
            this.showMismatchPrompts = showMismatchPrompts;
        }

        public enum DownloadSource {
            SERVER, INTERNET
        }
    }

    /**
     * Server-side configuration
     */
    public static class ServerConfig {
        private boolean directDownloadEnabled = true;
        private boolean zipModeEnabled = false;
        private String zipUrl = "";
        private String zipHash = "";
        private int maxDownloadSizeMB = 100;

        public static ServerConfig createDefault() {
            return new ServerConfig();
        }

        // Getters
        public boolean isDirectDownloadEnabled() { return directDownloadEnabled; }
        public boolean isZipModeEnabled() { return zipModeEnabled; }
        public String getZipUrl() { return zipUrl; }
        public String getZipHash() { return zipHash; }
        public int getMaxDownloadSizeMB() { return maxDownloadSizeMB; }

        // Setters
        public void setDirectDownloadEnabled(boolean directDownloadEnabled) {
            this.directDownloadEnabled = directDownloadEnabled;
        }
        public void setZipModeEnabled(boolean zipModeEnabled) {
            this.zipModeEnabled = zipModeEnabled;
        }
        public void setZipUrl(String zipUrl) {
            this.zipUrl = zipUrl;
        }
        public void setZipHash(String zipHash) {
            this.zipHash = zipHash;
        }
        public void setMaxDownloadSizeMB(int maxDownloadSizeMB) {
            this.maxDownloadSizeMB = maxDownloadSizeMB;
        }
    }
}