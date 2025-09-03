package com.yourname.modsync.fabric;

import com.yourname.modsync.common.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ModSyncFabric implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer, Platform {
    private static final Logger LOGGER = Logger.getLogger(ModSyncFabric.class.getName());
    private static ModSyncFabric INSTANCE;

    private final Map<String, PacketHandler> packetHandlers = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        INSTANCE = this;
        ModSync.initialize(this);

        // Register networking
        setupNetworking();
    }

    @Override
    public void onInitializeClient() {
        // Client-specific initialization
        ModSync.handleStartup();
    }

    @Override
    public void onInitializeServer() {
        // Server-specific initialization
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Send handshake to joining player
            ServerPlayer player = handler.getPlayer();
            sendServerHandshake(player);
        });
    }

    private void setupNetworking() {
        // Register payload types
        PayloadTypeRegistry.playS2C().register(GenericPayload.TYPE, GenericPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GenericPayload.TYPE, GenericPayload.CODEC);

        // Register handlers
        if (FabricLoader.getInstance().getEnvironmentType().name().equals("CLIENT")) {
            ClientPlayNetworking.registerGlobalReceiver(GenericPayload.TYPE, this::handleClientPacket);
        }

        ServerPlayNetworking.registerGlobalReceiver(GenericPayload.TYPE, this::handleServerPacket);
    }

    private void handleClientPacket(GenericPayload payload, ClientPlayNetworking.Context context) {
        PacketHandler handler = packetHandlers.get(payload.channel());
        if (handler != null) {
            handler.handle(null, payload.data()); // Client doesn't have sender
        }
    }

    private void handleServerPacket(GenericPayload payload, ServerPlayNetworking.Context context) {
        PacketHandler handler = packetHandlers.get(payload.channel());
        if (handler != null) {
            handler.handle(context.player(), payload.data());
        }
    }

    private void sendServerHandshake(ServerPlayer player) {
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

    @Override
    public LoaderType getLoaderType() {
        return LoaderType.FABRIC;
    }

    @Override
    public boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType().name().equals("CLIENT");
    }

    @Override
    public boolean isServer() {
        return !isClient();
    }

    @Override
    public Path getModsDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    @Override
    public List<ModInfo> getLoadedMods() {
        return FabricLoader.getInstance().getAllMods().stream()
                .filter(mod -> !mod.getMetadata().getId().equals("minecraft") &&
                        !mod.getMetadata().getId().equals("fabricloader") &&
                        !mod.getMetadata().getId().equals("fabric-api"))
                .map(this::convertToModInfo)
                .collect(Collectors.toList());
    }

    private ModInfo convertToModInfo(ModContainer container) {
        String modId = container.getMetadata().getId();
        String version = container.getMetadata().getVersion().getFriendlyString();
        String name = container.getMetadata().getName();
        String fileName = container.getOrigin().getPaths().get(0).getFileName().toString();

        return new ModInfo(modId, version, name, fileName);
    }

    @Override
    public void sendToServer(String channel, byte[] data) {
        if (!isClient()) return;

        GenericPayload payload = new GenericPayload(channel, data);
        ClientPlayNetworking.send(payload);
    }

    @Override
    public void sendToClient(Object player, String channel, byte[] data) {
        if (!(player instanceof ServerPlayer)) return;

        GenericPayload payload = new GenericPayload(channel, data);
        ServerPlayNetworking.send((ServerPlayer) player, payload);
    }

    @Override
    public void registerPacketHandler(String channel, PacketHandler handler) {
        packetHandlers.put(channel, handler);
    }

    @Override
    public void scheduleRestart() {
        if (!isClient()) return;

        Minecraft.getInstance().execute(() -> {
            Minecraft.getInstance().stop();
        });
    }

    @Override
    public void showNotification(String title, String message, NotificationType type) {
        if (!isClient()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // Show as chat message (simple implementation)
            mc.player.sendSystemMessage(Component.literal("[" + title + "] " + message));
        }
    }

    @Override
    public CompletableFuture<DialogResult> showDialog(String title, String message, List<String> options) {
        if (!isClient()) return CompletableFuture.completedFuture(DialogResult.CANCEL);

        CompletableFuture<DialogResult> future = new CompletableFuture<>();

        Minecraft.getInstance().execute(() -> {
            // Show notification and auto-accept for now
            showNotification(title, message, NotificationType.INFO);
            future.complete(DialogResult.ACCEPT);
        });

        return future;
    }

    @Override
    public String getGameVersion() {
        return FabricLoader.getInstance().getModContainer("minecraft")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public ModInfo getModInfo(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(this::convertToModInfo)
                .orElse(null);
    }

    @Override
    public void connectToServer(String serverAddress) {
        if (!isClient()) return;

        Minecraft.getInstance().execute(() -> {
            // Implementation would connect to server programmatically
            // This is a simplified version
            showNotification("ModSync", "Reconnecting to " + serverAddress, NotificationType.INFO);
        });
    }

    /**
     * Generic payload for all ModSync network communication
     */
    public record GenericPayload(String channel, byte[] data) implements CustomPacketPayload {
        public static final Type<GenericPayload> TYPE = new Type<>(new ResourceLocation(ModSync.MOD_ID, "generic"));

        public static final StreamCodec<FriendlyByteBuf, GenericPayload> CODEC = StreamCodec.composite(
                StreamCodec.of(
                        (buf, channel) -> buf.writeUtf(channel),
                        buf -> buf.readUtf()
                ), GenericPayload::channel,
                StreamCodec.of(
                        (buf, data) -> buf.writeByteArray(data),
                        buf -> buf.readByteArray()
                ), GenericPayload::data,
                GenericPayload::new
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}