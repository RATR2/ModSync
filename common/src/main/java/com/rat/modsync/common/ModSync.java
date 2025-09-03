package com.rat.modsync.common;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Main ModSync logic - platform independent
 */
public class ModSync {
    public static final String MOD_ID = "modsync";
    public static final String VERSION = "1.0.0";

    // Network channels
    public static final String PING_CHANNEL = "modsync:ping";
    public static final String HANDSHAKE_CHANNEL = "modsync:handshake";
    public static final String DOWNLOAD_REQUEST_CHANNEL = "modsync:download_request";
    public static final String DOWNLOAD_CHUNK_CHANNEL = "modsync:download_chunk";
    public static final String HANDSHAKE_COMPLETE_CHANNEL = "modsync:handshake_complete";

    private static final Logger LOGGER = Logger.getLogger(ModSync.class.getName());
    private static final Gson GSON = new Gson();

    private static Platform platform;
    private static ModListManager modListManager;
    private static DownloadManager downloadManager;
    private static ConfigManager configManager;
    private static UIManager uiManager;

    // Server compatibility cache: serverAddress -> isCompatible
    private static final ConcurrentHashMap<String, Boolean> serverCompatibility = new ConcurrentHashMap<>();

    // Connection state
    private static String pendingServerAddress = null;
    private static boolean awaitingHandshake = false;

    public static void initialize(Platform platformImpl) {
        platform = platformImpl;
        configManager = new ConfigManager(platform);
        modListManager = new ModListManager(platform);
        downloadManager = new DownloadManager(platform, configManager);
        uiManager = new UIManager(platform, configManager);

        setupNetworking();
        LOGGER.info("ModSync " + VERSION + " initialized for " + platform.getLoaderType());
    }

    private static void setupNetworking() {
        // Ping response handler (server -> client)
        platform.registerPacketHandler(PING_CHANNEL, (sender, data) -> {
            if (platform.isClient()) {
                handlePingResponse(sender, data);
            }
        });

        // Ping request handler (client -> server)
        platform.registerPacketHandler(PING_CHANNEL, (sender, data) -> {
            if (platform.isServer()) {
                handlePingRequest(sender, data);
            }
        });

        // Handshake handler (server -> client)
        platform.registerPacketHandler(HANDSHAKE_CHANNEL, (sender, data) -> {
            if (platform.isClient()) {
                handleServerHandshake(sender, data);
            }
        });

        // Download handlers
        platform.registerPacketHandler(DOWNLOAD_REQUEST_CHANNEL, (sender, data) -> {
            if (platform.isServer()) {
                handleDownloadRequest(sender, data);
            }
        });

        platform.registerPacketHandler(DOWNLOAD_CHUNK_CHANNEL, (sender, data) -> {
            if (platform.isClient()) {
                downloadManager.handleDownloadChunk(data);
            }
        });
    }

    /**
     * Called when client wants to check server compatibility (before connecting)
     */
    public static void pingServer(String serverAddress) {
        if (!platform.isClient()) return;

        try {
            PingMessage ping = new PingMessage(MOD_ID, VERSION);
            byte[] data = GSON.toJson(ping).getBytes();
            platform.sendToServer(PING_CHANNEL, data);
        } catch (Exception e) {
            LOGGER.warning("Failed to ping server " + serverAddress + ": " + e.getMessage());
            serverCompatibility.put(serverAddress, false);
        }
    }

    /**
     * Called when attempting to connect to a server
     */
    public static void attemptConnection(String serverAddress) {
        if (!platform.isClient()) return;

        pendingServerAddress = serverAddress;
        awaitingHandshake = true;

        // Server will send handshake automatically if ModSync is installed
        // If no handshake received within timeout, assume incompatible
        CompletableFuture.delayedExecutor(5, java.util.concurrent.TimeUnit.SECONDS)
                .execute(() -> {
                    if (awaitingHandshake && pendingServerAddress.equals(serverAddress)) {
                        handleIncompatibleServer(serverAddress);
                    }
                });
    }

    private static void handlePingRequest(Object sender, byte[] data) {
        try {
            String json = new String(data);
            PingMessage ping = GSON.fromJson(json, PingMessage.class);

            // Respond with our compatibility info
            PingResponse response = new PingResponse(true, MOD_ID, VERSION,
                    configManager.getServerConfig().isZipModeEnabled());
            byte[] responseData = GSON.toJson(response).getBytes();
            platform.sendToClient(sender, PING_CHANNEL, responseData);

            // Send handshake after ping response
            List<ModInfo> serverMods = modListManager.getServerMods();
            ConfigManager.ServerConfig config = configManager.getServerConfig();
            ServerHandshake handshake = new ServerHandshake(
                serverMods,
                config.isZipModeEnabled(),
                config.getZipUrl(),
                config.getZipHash(),
                VERSION
            );
            byte[] handshakeData = GSON.toJson(handshake).getBytes();
            platform.sendToClient(sender, HANDSHAKE_CHANNEL, handshakeData);

        } catch (Exception e) {
            LOGGER.warning("Failed to handle ping request: " + e.getMessage());
        }
    }

    private static void handlePingResponse(Object sender, byte[] data) {
        try {
            String json = new String(data);
            PingResponse response = GSON.fromJson(json, PingResponse.class);

            // Cache server compatibility
            if (pendingServerAddress != null) {
                serverCompatibility.put(pendingServerAddress, response.isCompatible());
            }

        } catch (Exception e) {
            LOGGER.warning("Failed to handle ping response: " + e.getMessage());
        }
    }

    private static void handleServerHandshake(Object sender, byte[] data) {
        if (!awaitingHandshake) return;

        try {
            String json = new String(data);
            ServerHandshake handshake = GSON.fromJson(json, ServerHandshake.class);
            awaitingHandshake = false;

            // Version check
            if (!VERSION.equals(handshake.getModSyncVersion())) {
                String warnMsg = "ModSync version mismatch! Server: " + handshake.getModSyncVersion() + ", Client: " + VERSION;
                LOGGER.warning(warnMsg);
                uiManager.showWarning("ModSync Version Mismatch", warnMsg);
            }

            // Compare mod lists
            List<ModInfo> serverMods = handshake.getRequiredMods();
            List<ModInfo> clientMods = modListManager.getClientMods();

            ModListManager.ModListComparison comparison = modListManager.compareMods(clientMods, serverMods);

            if (comparison.isCompatible()) {
                // All good, continue connection
                LOGGER.info("Mod compatibility check passed for server " + pendingServerAddress);
                uiManager.showNotification("ModSync", "ModSync is active on both server and client!", Platform.NotificationType.SUCCESS);
                completeHandshake();
            } else {
                // Handle missing/mismatched mods
                uiManager.showNotification("ModSync", "ModSync detected, but mod lists do not match!", Platform.NotificationType.WARNING);
                handleModMismatch(handshake, comparison);
            }

        } catch (Exception e) {
            LOGGER.severe("Failed to handle server handshake: " + e.getMessage());
            uiManager.showError("ModSync Error", "Failed to process server mod list");
        }
    }

    private static void handleModMismatch(ServerHandshake handshake, ModListManager.ModListComparison comparison) {
        if (configManager.getClientConfig().isAutoAcceptDownloads()) {
            // Auto-download without prompting
            startModDownload(handshake, comparison);
        } else {
            // Show prompt to user
            uiManager.showModMismatchDialog(comparison)
                    .thenAccept(result -> {
                        switch (result) {
                            case ACCEPT:
                                startModDownload(handshake, comparison);
                                break;
                            case DECLINE:
                                uiManager.showInfo("Connection Cancelled",
                                        "Connection to server cancelled due to mod mismatch");
                                break;
                            default:
                                // User cancelled
                                break;
                        }
                    });
        }
    }

    private static void startModDownload(ServerHandshake handshake, ModListManager.ModListComparison comparison) {
        try {
            if (handshake.isZipMode()) {
                // Download entire modpack as zip
                downloadManager.downloadModpack(handshake.getZipUrl(), handshake.getZipHash())
                        .thenRun(() -> completeModSync())
                        .exceptionally(throwable -> {
                            uiManager.showError("Download Error",
                                    "Failed to download modpack: " + throwable.getMessage());
                            return null;
                        });
            } else {
                // Download individual mods
                downloadManager.downloadMods(comparison.getMissingMods(), comparison.getMismatchedMods())
                        .thenRun(() -> completeModSync())
                        .exceptionally(throwable -> {
                            uiManager.showError("Download Error",
                                    "Failed to download mods: " + throwable.getMessage());
                            return null;
                        });
            }
        } catch (Exception e) {
            LOGGER.severe("Failed to start mod download: " + e.getMessage());
            uiManager.showError("ModSync Error", "Failed to start downloading mods");
        }
    }

    private static void completeModSync() {
        if (configManager.getClientConfig().isAutoRestart()) {
            uiManager.showInfo("ModSync", "Restarting Minecraft to load new mods...");

            // Save pending connection for auto-rejoin
            if (configManager.getClientConfig().isAutoRejoin() && pendingServerAddress != null) {
                configManager.setPendingConnection(pendingServerAddress);
            }

            platform.scheduleRestart();
        } else {
            uiManager.showInfo("ModSync Complete",
                    "Mods downloaded successfully. Please restart Minecraft manually to continue.");

        }
    }

    private static void completeHandshake() {
        try {
            HandshakeComplete complete = new HandshakeComplete(true);
            byte[] data = GSON.toJson(complete).getBytes();
            platform.sendToServer(HANDSHAKE_COMPLETE_CHANNEL, data);
        } catch (Exception e) {
            LOGGER.warning("Failed to send handshake complete: " + e.getMessage());
        }
    }

    private static void handleIncompatibleServer(String serverAddress) {
        awaitingHandshake = false;
        serverCompatibility.put(serverAddress, false);
        uiManager.showWarning("Server Incompatible",
                "This server does not have ModSync installed or is not compatible");
    }

    private static void handleDownloadRequest(Object sender, byte[] data) {
        if (!configManager.getServerConfig().isDirectDownloadEnabled()) {
            return; // Server doesn't allow direct downloads
        }

        try {
            String json = new String(data);
            DownloadRequest request = GSON.fromJson(json, DownloadRequest.class);
            downloadManager.handleServerDownloadRequest(sender, request);
        } catch (Exception e) {
            LOGGER.warning("Failed to handle download request: " + e.getMessage());
        }
    }

    /**
     * Check if a server is compatible with ModSync
     */
    public static boolean isServerCompatible(String serverAddress) {
        return serverCompatibility.getOrDefault(serverAddress, false);
    }

    /**
     * Called on client startup to handle auto-rejoin
     */
    public static void handleStartup() {
        if (!platform.isClient()) return;

        String pendingConnection = configManager.getPendingConnection();
        if (pendingConnection != null) {
            configManager.clearPendingConnection();

            if (configManager.getClientConfig().isAutoRejoin()) {
                // Auto-rejoin the server after restart
                CompletableFuture.delayedExecutor(2, java.util.concurrent.TimeUnit.SECONDS)
                        .execute(() -> {
                            uiManager.showInfo("ModSync", "Reconnecting to server...");
                            // Platform-specific code will handle the actual connection
                            platform.connectToServer(pendingConnection);
                        });
            }
        }
    }

    // Getters for managers (for platform-specific modules)
    public static Platform getPlatform() { return platform; }
    public static ModListManager getModListManager() { return modListManager; }
    public static DownloadManager getDownloadManager() { return downloadManager; }
    public static ConfigManager getConfigManager() { return configManager; }
    public static UIManager getUIManager() { return uiManager; }

    // Network message classes
    public static class PingMessage {
        private final String modId;
        private final String version;

        public PingMessage(String modId, String version) {
            this.modId = modId;
            this.version = version;
        }

        public String getModId() { return modId; }
        public String getVersion() { return version; }
    }

    public static class PingResponse {
        private final boolean compatible;
        private final String modId;
        private final String version;
        private final boolean zipModeEnabled;

        public PingResponse(boolean compatible, String modId, String version, boolean zipModeEnabled) {
            this.compatible = compatible;
            this.modId = modId;
            this.version = version;
            this.zipModeEnabled = zipModeEnabled;
        }

        public boolean isCompatible() { return compatible; }
        public String getModId() { return modId; }
        public String getVersion() { return version; }
        public boolean isZipModeEnabled() { return zipModeEnabled; }
    }

    public static class ServerHandshake {
        private final List<ModInfo> requiredMods;
        private final boolean zipMode;
        private final String zipUrl;
        private final String zipHash;
        private final String modSyncVersion;

        public ServerHandshake(List<ModInfo> requiredMods, boolean zipMode, String zipUrl, String zipHash, String modSyncVersion) {
            this.requiredMods = requiredMods;
            this.zipMode = zipMode;
            this.zipUrl = zipUrl;
            this.zipHash = zipHash;
            this.modSyncVersion = modSyncVersion;
        }

        public List<ModInfo> getRequiredMods() { return requiredMods; }
        public boolean isZipMode() { return zipMode; }
        public String getZipUrl() { return zipUrl; }
        public String getZipHash() { return zipHash; }
        public String getModSyncVersion() { return modSyncVersion; }
    }

    public static class HandshakeComplete {
        private final boolean success;

        public HandshakeComplete(boolean success) {
            this.success = success;
        }

        public boolean isSuccess() { return success; }
    }

    public static class DownloadRequest {
        private final String modId;
        private final String fileName;

        public DownloadRequest(String modId, String fileName) {
            this.modId = modId;
            this.fileName = fileName;
        }

        public String getModId() { return modId; }
        public String getFileName() { return fileName; }
    }
}