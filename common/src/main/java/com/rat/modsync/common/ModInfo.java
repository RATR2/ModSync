package com.yourname.modsync.common;

import java.util.Objects;

/**
 * Container for mod metadata used in ModSync operations
 */
public class ModInfo {
    private final String modId;
    private final String version;
    private final String name;
    private final String fileName;
    private final String sha256Hash;
    private final long fileSize;
    private final String downloadUrl;

    public ModInfo(String modId, String version, String name, String fileName,
                   String sha256Hash, long fileSize, String downloadUrl) {
        this.modId = modId;
        this.version = version;
        this.name = name;
        this.fileName = fileName;
        this.sha256Hash = sha256Hash;
        this.fileSize = fileSize;
        this.downloadUrl = downloadUrl;
    }

    public ModInfo(String modId, String version, String name, String fileName) {
        this(modId, version, name, fileName, null, 0, null);
    }

    public String getModId() { return modId; }
    public String getVersion() { return version; }
    public String getName() { return name; }
    public String getFileName() { return fileName; }
    public String getSha256Hash() { return sha256Hash; }
    public long getFileSize() { return fileSize; }
    public String getDownloadUrl() { return downloadUrl; }

    /**
     * Create a copy with download information
     */
    public ModInfo withDownloadInfo(String sha256Hash, long fileSize, String downloadUrl) {
        return new ModInfo(modId, version, name, fileName, sha256Hash, fileSize, downloadUrl);
    }

    /**
     * Check if this mod matches another (same ID and version)
     */
    public boolean matches(ModInfo other) {
        return Objects.equals(this.modId, other.modId) &&
                Objects.equals(this.version, other.version);
    }

    /**
     * Check if this mod has the same ID but different version
     */
    public boolean isVersionMismatch(ModInfo other) {
        return Objects.equals(this.modId, other.modId) &&
                !Objects.equals(this.version, other.version);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ModInfo)) return false;
        ModInfo other = (ModInfo) obj;
        return Objects.equals(modId, other.modId) &&
                Objects.equals(version, other.version) &&
                Objects.equals(name, other.name) &&
                Objects.equals(fileName, other.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modId, version, name, fileName);
    }

    @Override
    public String toString() {
        return String.format("ModInfo{id='%s', version='%s', name='%s', file='%s'}",
                modId, version, name, fileName);
    }
}