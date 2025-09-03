package com.rat.modsync.common;

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

    /**
     * Process a server list entry from the multiplayer screen ping response.
     * Extract ModSync handshake/mod list data from MOTD, compare mods, and trigger UI logic if needed.
     */
    public void processServerListEntry(Object entry) {
        try {
            // Use reflection to get MOTD from entry
            String motd = null;
            try {
                motd = (String) entry.getClass().getMethod("getMotd").invoke(entry);
            } catch (Exception ignored) {}
            if (motd == null || motd.isEmpty()) return;

            // Look for ModSync marker in MOTD
            String marker = "§8[ModSync]";
            int idx = motd.indexOf(marker);
            if (idx == -1) return;
            String modSyncJson = motd.substring(idx + marker.length()).trim();
            if (modSyncJson.isEmpty()) return;

            // Parse the server's mod list from the ModSync handshake
            List<ModInfo> serverMods = new com.google.gson.Gson().fromJson(modSyncJson,
                    new com.google.gson.reflect.TypeToken<List<ModInfo>>(){}.getType());
            List<ModInfo> clientMods = getClientMods();
            ModListComparison comparison = compareMods(clientMods, serverMods);

            boolean hasIssues = comparison.hasIssues();
            // Set the modsyncMissingMods flag using reflection
            try {
                entry.getClass().getMethod("modsync$setModsyncMissingMods", boolean.class)
                    .invoke(entry, hasIssues);
            } catch (Exception ignored) {}

            if (hasIssues) {
                // Trigger ModSync UI logic to prompt the user and block connection
                ModSync.getUIManager().showWarning("ModSync: Missing Mods",
                        "This server requires mods you do not have: " + comparison.getSummary());
                // Optionally, block the Join Server button via mixin logic
            }
        } catch (Exception e) {
            // Ignore if ModSync data is not present
        }
    }
}