package com.rat.modsync.forge;

import com.rat.modsync.common.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Mod(ModSync.MOD_ID)
public class ModSyncForge implements Platform {
    private static final Logger LOGGER = Logger.getLogger(ModSyncForge.class.getName());
    private static final String PROTOCOL_VERSION = "1";

    private static SimpleChannel NETWORK;
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
        // Set up networking
        NETWORK = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(ModSync.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        // Register packet handlers
        NETWORK.registerMessage(0, GenericPacket.class,
                GenericPacket::encode,
                GenericPacket::decode,
                this::handlePacket);
    }

    @OnlyIn(Dist.CLIENT)
    private void clientSetup(FMLClientSetupEvent event) {
        // Handle startup auto-rejoin
        ModSync.handleStartup();
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

    private ModInfo convertToModInfo(ModContainer container) {
        String modId = container.getModId();
        String version = container.getModInfo().getVersion().toString();
        String name = container.getModInfo().getDisplayName();
        String fileName = container.getModInfo().getOwningFile().getFile().getFileName().toString();

        return new ModInfo(modId, version, name, fileName);
    }

    @Override
    public void sendToServer(String channel, byte[] data) {
        if (!isClient()) return;

        GenericPacket packet = new GenericPacket(channel, data);
        NETWORK.sendToServer(packet);
    }

    @Override
    public void sendToClient(Object player, String channel, byte[] data) {
        if (!(player instanceof ServerPlayer)) return;

        GenericPacket packet = new GenericPacket(channel, data);
        NETWORK.send(PacketDistributor.PLAYER.with(() -> (ServerPlayer) player), packet);
    }

    @Override
    public void registerPacketHandler(String channel, PacketHandler handler) {
        // Handlers are managed through the GenericPacket system
        PacketHandlerRegistry.register(channel, handler);
    }

    @Override
    public void scheduleRestart() {
        if (!isClient()) return;

        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().stop();
            // Forge handles restart automatically
        });
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public void showNotification(String title, String message, NotificationType type) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Component titleComponent = Component.literal(title);
            Component messageComponent = Component.literal(message);

            // Show as chat message (simple implementation)
            mc.player.sendSystemMessage(Component.literal("[" + title + "] " + message));
        }
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public CompletableFuture<DialogResult> showDialog(String title, String message, List<String> options) {
        // For now, use a simple approach - could be enhanced with custom GUI
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
        return "1.21.1"; // Could be dynamic
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public ModInfo getModInfo(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(this::convertToModInfo)
                .orElse(null);
    }

    @Override
    public void connectToServer(String serverAddress) {
        if (!isClient()) return;

        Minecraft.getInstance().execute(() -> {
            // Parse server address
            String[] parts = serverAddress.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 25565;

            ServerData serverData = new ServerData("ModSync Auto-Connect", serverAddress, false);
            ConnectScreen.startConnecting(Minecraft.getInstance().screen, Minecraft.getInstance(),
                    serverData, null);
        });
    }

    private void handlePacket(GenericPacket packet, NetworkEvent.Context context) {
        context.enqueueWork(() -> {
            PacketHandler handler = PacketHandlerRegistry.get(packet.channel);
            if (handler != null) {
                Object sender = context.getSender(); // ServerPlayer or null for client
                handler.handle(sender, packet.data);
            }
        });
        context.setPacketHandled(true);
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

        // Build server mod manifest
        List<ModInfo> serverMods = getLoadedMods();
        ConfigManager.ServerConfig config = ModSync.getConfigManager().getServerConfig();

        ModSync.ServerHandshake handshake = new ModSync.ServerHandshake(
                serverMods,
                config.isZipModeEnabled(),
                config.getZipUrl(),
                config.getZipHash()
        );

        try {
            String json = new com.google.gson.Gson().toJson(handshake);
            sendToClient(player, ModSync.HANDSHAKE_CHANNEL, json.getBytes());
        } catch (Exception e) {
            LOGGER.warning("Failed to send handshake to player: " + e.getMessage());
        }
    }

    /**
     * Generic packet for all ModSync network communication
     */
    public static class GenericPacket {
        public final String channel;
        public final byte[] data;

        public GenericPacket(String channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }

        public static void encode(GenericPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.channel);
            buffer.writeByteArray(packet.data);
        }

        public static GenericPacket decode(FriendlyByteBuf buffer) {
            String channel = buffer.readUtf();
            byte[] data = buffer.readByteArray();
            return new GenericPacket(channel, data);
        }
    }

    /**
     * Simple registry for packet handlers
     */
    private static class PacketHandlerRegistry {
        private static final java.util.Map<String, PacketHandler> handlers = new java.util.concurrent.ConcurrentHashMap<>();

        public static void register(String channel, PacketHandler handler) {
            handlers.put(channel, handler);
        }

        public static PacketHandler get(String channel) {
            return handlers.get(channel);
        }
    }
}