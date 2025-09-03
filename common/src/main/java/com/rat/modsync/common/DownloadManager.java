package com.rat.modsync.common;

import com.google.gson.Gson;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Manages downloading mods and modpacks with integrity verification
 */
public class DownloadManager {
    private static final Logger LOGGER = Logger.getLogger(DownloadManager.class.getName());
    private static final Gson GSON = new Gson();
    private static final int CHUNK_SIZE = 8192;
    private static final int MAX_DOWNLOAD_SIZE = 500 * 1024 * 1024; // 500MB limit

    private final Platform platform;
    private final ConfigManager configManager;
    private final ExecutorService downloadExecutor;

    public DownloadManager(Platform platform, ConfigManager configManager) {
        this.platform = platform;
        this.configManager = configManager;
        this.downloadExecutor = Executors.newFixedThreadPool(3);
    }

    /**
     * Download individual mods
     */
    public CompletableFuture<Void> downloadMods(List<ModInfo> missingMods,
                                                List<ModListManager.ModMismatch> mismatchedMods) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Download missing mods
                for (ModInfo mod : missingMods) {
                    downloadMod(mod);
                }

                // Download replacement mods for mismatches
                for (ModListManager.ModMismatch mismatch : mismatchedMods) {
                    // Remove old version first
                    removeOldMod(mismatch.getClientMod());
                    downloadMod(mismatch.getServerMod());
                }

                LOGGER.info("Successfully downloaded all required mods");

            } catch (Exception e) {
                LOGGER.severe("Failed to download mods: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }, downloadExecutor);
    }

    /**
     * Download a modpack zip file
     */
    public CompletableFuture<Void> downloadModpack(String zipUrl, String expectedHash) {
        return CompletableFuture.runAsync(() -> {
            try {
                LOGGER.info("Downloading modpack from: " + zipUrl);

                // Download zip to temp file
                Path tempZip = Files.createTempFile("modsync_modpack", ".zip");
                downloadFile(zipUrl, tempZip, expectedHash);

                // Extract zip to mods directory
                extractModpack(tempZip);

                // Clean up temp file
                Files.deleteIfExists(tempZip);

                LOGGER.info("Successfully downloaded and extracted modpack");

            } catch (Exception e) {
                LOGGER.severe("Failed to download modpack: " + e.getMessage());
                throw new RuntimeException(e);
            }
        }, downloadExecutor);
    }

    private void downloadMod(ModInfo mod) throws IOException {
        Path modsDir = platform.getModsDirectory();
        Path targetFile = modsDir.resolve(mod.getFileName());

        // Check if file already exists and matches hash
        if (Files.exists(targetFile) && mod.getSha256Hash() != null) {
            String existingHash = calculateSHA256(targetFile);
            if (existingHash.equals(mod.getSha256Hash())) {
                LOGGER.info("Mod " + mod.getModId() + " already exists with correct hash, skipping download");
                return;
            }
        }

        String downloadUrl = determineDownloadUrl(mod);
        LOGGER.info("Downloading mod: " + mod.getName() + " from " + downloadUrl);

        downloadFile(downloadUrl, targetFile, mod.getSha256Hash());
    }

    private String determineDownloadUrl(ModInfo mod) {
        ConfigManager.ClientConfig.DownloadSource source = configManager.getClientConfig().getDefaultDownloadSource();

        if (source == ConfigManager.ClientConfig.DownloadSource.SERVER || mod.getDownloadUrl() == null) {
            // Request from server
            requestModFromServer(mod);
            return null; // Will be handled by chunk system
        } else {
            // Use provided URL
            return mod.getDownloadUrl();
        }
    }

    private void requestModFromServer(ModInfo mod) {
        try {
            ModSync.DownloadRequest request = new ModSync.DownloadRequest(mod.getModId(), mod.getFileName());
            byte[] data = GSON.toJson(request).getBytes();
            platform.sendToServer(ModSync.DOWNLOAD_REQUEST_CHANNEL, data);
        } catch (Exception e) {
            LOGGER.warning("Failed to request mod from server: " + e.getMessage());
            throw new RuntimeException("Server download not available");
        }
    }

    private void downloadFile(String url, Path targetPath, String expectedHash) throws IOException {
        if (url == null) {
            throw new IOException("No download URL provided");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", "ModSync/" + ModSync.VERSION);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);

        int responseCode = connection.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("HTTP " + responseCode + " when downloading " + url);
        }

        long contentLength = connection.getContentLengthLong();
        if (contentLength > MAX_DOWNLOAD_SIZE) {
            throw new IOException("File too large: " + contentLength + " bytes");
        }

        // Download to temp file first
        Path tempFile = Files.createTempFile("modsync_download", ".tmp");

        try (InputStream in = connection.getInputStream();
             OutputStream out = Files.newOutputStream(tempFile)) {

            byte[] buffer = new byte[CHUNK_SIZE];
            long downloaded = 0;
            int bytesRead;

            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloaded += bytesRead;

                // Update progress (could be used for UI)
                if (contentLength > 0) {
                    int progress = (int) ((downloaded * 100) / contentLength);
                    // TODO: Update UI progress bar
                }
            }
        }

        // Verify hash if provided
        if (expectedHash != null && !expectedHash.isEmpty()) {
            String actualHash = calculateSHA256(tempFile);
            if (!actualHash.equals(expectedHash)) {
                Files.deleteIfExists(tempFile);
                throw new IOException("Hash mismatch! Expected: " + expectedHash + ", Got: " + actualHash);
            }
        }

        // Move to final location
        Files.createDirectories(targetPath.getParent());
        Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void extractModpack(Path zipFile) throws IOException {
        Path modsDir = platform.getModsDirectory();

        // Clear existing mods directory (backup first)
        Path backupDir = modsDir.getParent().resolve("mods_backup_" + System.currentTimeMillis());
        if (Files.exists(modsDir)) {
            Files.move(modsDir, backupDir);
            LOGGER.info("Backed up existing mods to: " + backupDir);
        }

        Files.createDirectories(modsDir);

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                String fileName = entry.getName();

                // Security check: ensure we're not extracting outside mods directory
                if (fileName.contains("..") || fileName.startsWith("/")) {
                    LOGGER.warning("Skipping potentially unsafe zip entry: " + fileName);
                    continue;
                }

                // Only extract .jar files to mods directory
                if (!fileName.toLowerCase().endsWith(".jar")) {
                    continue;
                }

                Path targetFile = modsDir.resolve(fileName);
                Files.createDirectories(targetFile.getParent());

                try (OutputStream out = Files.newOutputStream(targetFile)) {
                    byte[] buffer = new byte[CHUNK_SIZE];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }

                LOGGER.info("Extracted mod: " + fileName);
            }
        }
    }

    private void removeOldMod(ModInfo mod) {
        try {
            Path modFile = platform.getModsDirectory().resolve(mod.getFileName());
            if (Files.exists(modFile)) {
                Files.delete(modFile);
                LOGGER.info("Removed old mod: " + mod.getFileName());
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to remove old mod " + mod.getFileName() + ": " + e.getMessage());
        }
    }

    private String calculateSHA256(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[CHUNK_SIZE];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }

            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (Exception e) {
            throw new IOException("Failed to calculate SHA-256: " + e.getMessage(), e);
        }
    }

    /**
     * Handle server-side download request from client
     */
    public void handleServerDownloadRequest(Object player, ModSync.DownloadRequest request) {
        if (!configManager.getServerConfig().isDirectDownloadEnabled()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                Path modsDir = platform.getModsDirectory();
                Path modFile = modsDir.resolve(request.getFileName());

                if (!Files.exists(modFile)) {
                    LOGGER.warning("Requested mod file not found: " + request.getFileName());
                    return;
                }

                // Stream file to client in chunks
                try (InputStream is = Files.newInputStream(modFile)) {
                    byte[] buffer = new byte[CHUNK_SIZE];
                    int bytesRead;
                    int chunkIndex = 0;

                    while ((bytesRead = is.read(buffer)) != -1) {
                        byte[] chunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, chunk, 0, bytesRead);

                        DownloadChunk chunkMessage = new DownloadChunk(
                                request.getModId(), chunkIndex++, chunk, false);

                        byte[] chunkData = GSON.toJson(chunkMessage).getBytes();
                        platform.sendToClient(player, ModSync.DOWNLOAD_CHUNK_CHANNEL, chunkData);
                    }

                    // Send final chunk to indicate completion
                    DownloadChunk finalChunk = new DownloadChunk(request.getModId(), chunkIndex, new byte[0], true);
                    byte[] finalData = GSON.toJson(finalChunk).getBytes();
                    platform.sendToClient(player, ModSync.DOWNLOAD_CHUNK_CHANNEL, finalData);

                    LOGGER.info("Sent mod " + request.getFileName() + " to client in " + chunkIndex + " chunks");
                }

            } catch (IOException e) {
                LOGGER.severe("Failed to send mod to client: " + e.getMessage());
            }
        }, downloadExecutor);
    }

    /**
     * Handle incoming download chunk from server
     */
    public void handleDownloadChunk(byte[] data) {
        try {
            String json = new String(data);
            DownloadChunk chunk = GSON.fromJson(json, DownloadChunk.class);

            // TODO: Implement chunk assembly and file writing
            // This would maintain a map of modId -> ChunkedDownload
            // and assemble chunks into complete files

        } catch (Exception e) {
            LOGGER.warning("Failed to handle download chunk: " + e.getMessage());
        }
    }

    /**
     * Represents a chunk of downloaded data
     */
    public static class DownloadChunk {
        private final String modId;
        private final int chunkIndex;
        private final byte[] data;
        private final boolean isLast;

        public DownloadChunk(String modId, int chunkIndex, byte[] data, boolean isLast) {
            this.modId = modId;
            this.chunkIndex = chunkIndex;
            this.data = data;
            this.isLast = isLast;
        }

        public String getModId() { return modId; }
        public int getChunkIndex() { return chunkIndex; }
        public byte[] getData() { return data; }
        public boolean isLast() { return isLast; }
    }
}