package com.rat.modsync.forge;

import com.rat.modsync.common.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Mod(ModSync.MOD_ID)
public class ModSyncForge implements Platform {
    private static final Logger LOGGER = Logger.getLogger(ModSyncForge.class.getName());
    private static ModSyncForge INSTANCE;

    public ModSyncForge() {
        INSTANCE = this;

        // Register event bus
        MinecraftForge.EVENT_BUS.register(this);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::commonSetup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);

        // Initialize ModSync with this platform
        ModSync.initialize(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("ModSync Forge common setup complete");
    }

    @OnlyIn(Dist.CLIENT)
    private void clientSetup(FMLClientSetupEvent event) {
        // Handle startup auto-rejoin
        ModSync.handleStartup();
        LOGGER.info("ModSync Forge client setup complete");
    }

    public static ModSyncForge getInstance() {
        return INSTANCE;
    }

    @Override
    public LoaderType getLoaderType() {
        return LoaderType.FORGE;
    }

    @Override
    public boolean isClient() {
        return Minecraft.getInstance() != null;
    }

    @Override
    public boolean isServer() {
        return !isClient();
    }

    @Override
    public Path getModsDirectory() {
        return Paths.get("mods");
    }

    @Override
    public List<ModInfo> getLoadedMods() {
        return ModList.get().getMods().stream()
                .filter(mod -> !mod.getModId().equals("minecraft") && !mod.getModId().equals("forge"))
                .map(this::convertToModInfo)
                .collect(Collectors.toList());
    }

    private ModInfo convertToModInfo(IModInfo modInfo) {
        String modId = modInfo.getModId();
        String version = modInfo.getVersion().toString();
        String name = modInfo.getDisplayName();
        String fileName = modInfo.getOwningFile().getFile().getFileName().toString();
        return new ModInfo(modId, version, name, fileName);
    }

    @Override
    public void sendToServer(String channel, byte[] data) {
        if (!isClient()) return;
        LOGGER.info("Client > Sending packet to server on channel: " + channel);
        // TODO: Implement networking - Forge's networking API has changed significantly
        // This will need to use the new packet system
        LOGGER.info("Sending packet to server on channel: " + channel);
    }

    @Override
    public void sendToClient(Object player, String channel, byte[] data) {
        if (!(player instanceof ServerPlayer)) return;
        LOGGER.info("Server > Sending packet to client on channel: " + channel);
        // TODO: Implement networking - Forge's networking API has changed significantly
        LOGGER.info("Sending packet to client on channel: " + channel);
    }

    @Override
    public void registerPacketHandler(String channel, PacketHandler handler) {
        // Store handlers for when networking is implemented
        PacketHandlerRegistry.register(channel, handler);
    }

    @Override
    public void scheduleRestart() {
        if (!isClient()) return;

        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().stop();
            // Forge handles restart automatically in development
        });
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void showNotification(String title, String message, NotificationType type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // Show as chat message (simple implementation)
            String prefix = getNotificationPrefix(type);
            mc.player.sendSystemMessage(Component.literal(prefix + "[" + title + "] " + message));
        }
    }

    private String getNotificationPrefix(NotificationType type) {
        switch (type) {
            case ERROR: return "§c";
            case WARNING: return "§e";
            case SUCCESS: return "§a";
            case INFO:
            default: return "§b";
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public CompletableFuture<DialogResult> showDialog(String title, String message, List<String> options) {
        CompletableFuture<DialogResult> future = new CompletableFuture<>();

        Minecraft.getInstance().execute(() -> {
            // Show notification and auto-accept for now
            // In a full implementation, this would show a proper dialog
            showNotification(title, message, NotificationType.INFO);
            future.complete(DialogResult.ACCEPT);
        });

        return future;
    }

    @Override
    public String getGameVersion() {
        return "1.21.1"; // This should be dynamic in a real implementation
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public ModInfo getModInfo(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> convertToModInfo(container.getModInfo()))
                .orElse(null);
    }

    @Override
    public void connectToServer(String serverAddress) {
        if (!isClient()) return;
        Minecraft.getInstance().execute(() -> {
            try {
                ServerData serverData = new ServerData("ModSync Auto-Connect", serverAddress, ServerData.Type.OTHER);
                // Fallback: open multiplayer screen and let user select server
                Minecraft.getInstance().setScreen(new JoinMultiplayerScreen(Minecraft.getInstance().screen));
                showNotification("ModSync", "Please select the server: " + serverAddress, NotificationType.INFO);
            } catch (Exception e) {
                LOGGER.warning("Failed to connect to server: " + e.getMessage());
            }
        });
    }

    @SubscribeEvent
    @OnlyIn(Dist.CLIENT)
    public void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();

        if (screen instanceof JoinMultiplayerScreen) {
            // TODO: Add ModSync compatibility indicators to server list
            // This would require rendering overlay icons next to server entries
        }
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!isServer()) return;

        // Send handshake to joining player
        ServerPlayer player = (ServerPlayer) event.getEntity();
        LOGGER.info("Player joined: " + player.getName().getString());
        sendServerHandshake(player);
    }

    private void sendServerHandshake(ServerPlayer player) {
        LOGGER.info("Server > Checking client for ModSync");
        List<ModInfo> serverMods = getLoadedMods();
        ConfigManager.ServerConfig config = ModSync.getConfigManager().getServerConfig();
        ModSync.ServerHandshake handshake = new ModSync.ServerHandshake(
                serverMods,
                config.isZipModeEnabled(),
                config.getZipUrl(),
                config.getZipHash(),
                ModSync.VERSION
        );
        try {
            String json = new com.google.gson.Gson().toJson(handshake);
            sendToClient(player, ModSync.HANDSHAKE_CHANNEL, json.getBytes());
            LOGGER.info("Server > Sent handshake to client");
        } catch (Exception e) {
            LOGGER.warning("Failed to send handshake to player: " + e.getMessage());
        }
    }

    /**
     * Simple registry for packet handlers until networking is fully implemented
     */
    private static class PacketHandlerRegistry {
        private static final ConcurrentHashMap<String, PacketHandler> handlers = new ConcurrentHashMap<>();

        public static void register(String channel, PacketHandler handler) {
            handlers.put(channel, handler);
        }

        public static PacketHandler get(String channel) {
            return handlers.get(channel);
        }

        public static void clear() {
            handlers.clear();
        }
    }
}