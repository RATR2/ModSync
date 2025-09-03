package com.rat.modsync.common;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Platform abstraction interface for ModSync.
 * Each loader (Forge, Fabric, Quilt) implements this interface
 * to provide platform-specific functionality.
 */
public interface Platform {

    /**
     * Get the current loader type
     */
    LoaderType getLoaderType();

    /**
     * Check if we're running on the client side
     */
    boolean isClient();

    /**
     * Check if we're running on the server side
     */
    boolean isServer();

    /**
     * Get the mods directory path
     */
    Path getModsDirectory();

    /**
     * Get list of currently loaded mods with their metadata
     */
    List<ModInfo> getLoadedMods();

    /**
     * Send a packet to the server (client-side)
     */
    void sendToServer(String channel, byte[] data);

    /**
     * Send a packet to a specific client (server-side)
     */
    void sendToClient(Object player, String channel, byte[] data);

    /**
     * Register a packet handler for a specific channel
     */
    void registerPacketHandler(String channel, PacketHandler handler);

    /**
     * Schedule a client restart
     */
    void scheduleRestart();

    /**
     * Show a notification to the user
     */
    void showNotification(String title, String message, NotificationType type);

    /**
     * Show a dialog with options and return the user's choice
     */
    CompletableFuture<DialogResult> showDialog(String title, String message, List<String> options);

    /**
     * Get the game version
     */
    String getGameVersion();

    /**
     * Check if a mod with the given ID is loaded
     */
    boolean isModLoaded(String modId);

    /**
     * Get mod info by ID
     */
    ModInfo getModInfo(String modId);

    /**
     * Connect the client to a server (client-side)
     */
    void connectToServer(String serverAddress);

    // -------------------------
    // Nested enums
    // -------------------------
    enum LoaderType {
        FORGE, FABRIC
    }

    enum NotificationType {
        INFO, WARNING, ERROR, SUCCESS
    }

    enum DialogResult {
        ACCEPT, DECLINE, CANCEL
    }

    // -------------------------
    // Packet handler functional interface
    // -------------------------
    @FunctionalInterface
    interface PacketHandler {
        void handle(Object sender, byte[] data);
    }
}
