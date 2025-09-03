package com.yourname.modsync.common;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages mod lists and comparison logic
 */
public class ModListManager {
    private final Platform platform;

    public ModListManager(Platform platform) {
        this.platform = platform;
    }

    /**
     * Get all mods currently loaded on the client
     */
    public List<ModInfo> getClientMods() {
        return platform.getLoadedMods();
    }

    /**
     * Get all mods required by the server (for server-side)
     */
    public List<ModInfo> getServerMods() {
        if (!platform.isServer()) {
            return Collections.emptyList();
        }
        return platform.getLoadedMods();
    }

    /**
     * Compare client mods with server requirements
     */
    public ModListComparison compareMods(List<ModInfo> clientMods, List<ModInfo> serverMods) {
        Map<String, ModInfo> clientModMap = clientMods.stream()
                .collect(Collectors.toMap(ModInfo::getModId, mod -> mod));

        List<ModInfo> missingMods = new ArrayList<>();
        List<ModMismatch> mismatchedMods = new ArrayList<>();

        for (ModInfo serverMod : serverMods) {
            ModInfo clientMod = clientModMap.get(serverMod.getModId());

            if (clientMod == null) {
                // Mod is missing entirely
                missingMods.add(serverMod);
            } else if (!clientMod.matches(serverMod)) {
                // Mod exists but version mismatch
                mismatchedMods.add(new ModMismatch(clientMod, serverMod));
            }
        }

        return new ModListComparison(missingMods, mismatchedMods);
    }

    /**
     * Result of mod list comparison
     */
    public static class ModListComparison {
        private final List<ModInfo> missingMods;
        private final List<ModMismatch> mismatchedMods;

        public ModListComparison(List<ModInfo> missingMods, List<ModMismatch> mismatchedMods) {
            this.missingMods = missingMods;
            this.mismatchedMods = mismatchedMods;
        }

        public List<ModInfo> getMissingMods() { return missingMods; }
        public List<ModMismatch> getMismatchedMods() { return mismatchedMods; }

        public boolean isCompatible() {
            return missingMods.isEmpty() && mismatchedMods.isEmpty();
        }

        public boolean hasIssues() {
            return !isCompatible();
        }

        public int getTotalIssues() {
            return missingMods.size() + mismatchedMods.size();
        }

        public String getSummary() {
            if (isCompatible()) {
                return "All mods match server requirements";
            }

            StringBuilder sb = new StringBuilder();
            if (!missingMods.isEmpty()) {
                sb.append(missingMods.size()).append(" missing mod(s)");
            }
            if (!mismatchedMods.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(mismatchedMods.size()).append(" version mismatch(es)");
            }
            return sb.toString();
        }
    }

    /**
     * Represents a version mismatch between client and server
     */
    public static class ModMismatch {
        private final ModInfo clientMod;
        private final ModInfo serverMod;

        public ModMismatch(ModInfo clientMod, ModInfo serverMod) {
            this.clientMod = clientMod;
            this.serverMod = serverMod;
        }

        public ModInfo getClientMod() { return clientMod; }
        public ModInfo getServerMod() { return serverMod; }

        public String getModId() { return serverMod.getModId(); }
        public String getModName() { return serverMod.getName(); }
        public String getClientVersion() { return clientMod.getVersion(); }
        public String getServerVersion() { return serverMod.getVersion(); }

        @Override
        public String toString() {
            return String.format("%s: %s -> %s",
                    getModName(), getClientVersion(), getServerVersion());
        }
    }
}