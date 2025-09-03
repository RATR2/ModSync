package com.rat.modsync.common;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages user interface elements and notifications for ModSync
 */
public class UIManager {
    private final Platform platform;
    private final ConfigManager configManager;

    public UIManager(Platform platform, ConfigManager configManager) {
        this.platform = platform;
        this.configManager = configManager;
    }

    /**
     * Show a notification to the user
     */
    public void showNotification(String title, String message, Platform.NotificationType type) {
        platform.showNotification(title, message, type);
    }

    /**
     * Convenience methods for different notification types
     */
    public void showInfo(String title, String message) {
        showNotification(title, message, Platform.NotificationType.INFO);
    }

    public void showWarning(String title, String message) {
        showNotification(title, message, Platform.NotificationType.WARNING);
    }

    public void showError(String title, String message) {
        showNotification(title, message, Platform.NotificationType.ERROR);
    }

    public void showSuccess(String title, String message) {
        showNotification(title, message, Platform.NotificationType.SUCCESS);
    }

    /**
     * Show dialog for mod mismatch with options
     */
    public CompletableFuture<Platform.DialogResult> showModMismatchDialog(ModListManager.ModListComparison comparison) {
        if (!configManager.getClientConfig().isShowMismatchPrompts()) {
            // Skip prompt, auto-accept
            return CompletableFuture.completedFuture(Platform.DialogResult.ACCEPT);
        }

        String title = "Mod Synchronization Required";
        String message = buildMismatchMessage(comparison);
        List<String> options = List.of("Download and Install", "Cancel Connection");

        return platform.showDialog(title, message, options)
                .thenApply(result -> {
                    switch (result) {
                        case ACCEPT: return Platform.DialogResult.ACCEPT;
                        default: return Platform.DialogResult.DECLINE;
                    }
                });
    }

    private String buildMismatchMessage(ModListManager.ModListComparison comparison) {
        StringBuilder message = new StringBuilder();
        message.append("This server requires different mods than you have installed.\n\n");

        if (!comparison.getMissingMods().isEmpty()) {
            message.append("Missing mods (").append(comparison.getMissingMods().size()).append("):\n");
            for (ModInfo mod : comparison.getMissingMods()) {
                message.append("  • ").append(mod.getName()).append(" v").append(mod.getVersion()).append("\n");
            }
            message.append("\n");
        }

        if (!comparison.getMismatchedMods().isEmpty()) {
            message.append("Version mismatches (").append(comparison.getMismatchedMods().size()).append("):\n");
            for (ModListManager.ModMismatch mismatch : comparison.getMismatchedMods()) {
                message.append("  • ").append(mismatch.getModName())
                        .append(": ").append(mismatch.getClientVersion())
                        .append(" → ").append(mismatch.getServerVersion()).append("\n");
            }
            message.append("\n");
        }

        message.append("ModSync can automatically download and install these mods for you.\n");
        message.append("Would you like to proceed?");

        return message.toString();
    }

    /**
     * Show dialog for server compatibility warning
     */
    public CompletableFuture<Platform.DialogResult> showIncompatibilityWarning(String serverAddress) {
        String title = "Server Incompatible";
        String message = "The server \"" + serverAddress + "\" does not have ModSync installed.\n\n" +
                "You may experience mod-related connection issues.\n\n" +
                "Continue anyway?";
        List<String> options = List.of("Connect Anyway", "Cancel");

        return platform.showDialog(title, message, options);
    }

    /**
     * Show download progress notification
     */
    public void showDownloadProgress(String modName, int progress) {
        String message = "Downloading " + modName + "... " + progress + "%";
        showNotification("ModSync Download", message, Platform.NotificationType.INFO);
    }

    /**
     * Show modpack extraction progress
     */
    public void showExtractionProgress(int filesExtracted, int totalFiles) {
        String message = "Extracting modpack... " + filesExtracted + "/" + totalFiles + " files";
        showNotification("ModSync", message, Platform.NotificationType.INFO);
    }

    /**
     * Show configuration dialog (could be implemented later)
     */
    public CompletableFuture<Boolean> showConfigDialog() {
        // Placeholder for configuration UI
        String title = "ModSync Configuration";
        String message = "Configuration options:\n\n" +
                "Auto-accept downloads: " + configManager.getClientConfig().isAutoAcceptDownloads() + "\n" +
                "Auto-restart: " + configManager.getClientConfig().isAutoRestart() + "\n" +
                "Auto-rejoin: " + configManager.getClientConfig().isAutoRejoin() + "\n" +
                "Default source: " + configManager.getClientConfig().getDefaultDownloadSource() + "\n\n" +
                "Open config file to modify settings.";
        List<String> options = List.of("OK");

        return platform.showDialog(title, message, options)
                .thenApply(result -> true);
    }

    /**
     * Format file size for display
     */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}