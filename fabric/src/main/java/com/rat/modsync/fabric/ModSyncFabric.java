package com.rat.modsync.fabric;

import com.rat.modsync.common.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import io.netty.buffer.Unpooled;

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
        setupNetworking();
    }

    @Override
    public void onInitializeClient() {
        ModSync.handleStartup();
    }

    @Override
    public void onInitializeServer() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendServerHandshake(handler.getPlayer());
        });
    }

    private void setupNetworking() {
        // Client-side handler
        if (isClient()) {
            ClientPlayNetworking.registerGlobalReceiver(new ResourceLocation(ModSync.MOD_ID, "generic"), (client, handler, buf, responseSender) -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                GenericPayload payload = new GenericPayload("generic", data);
                PacketHandler handlerImpl = packetHandlers.get(payload.channel());
                if (handlerImpl != null) {
                    handlerImpl.handle(null, payload.data());
                }
            });
        }

        // Server-side handler
        ServerPlayNetworking.registerGlobalReceiver(new ResourceLocation(ModSync.MOD_ID, "generic"), (server, player, handler, buf, responseSender) -> {
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            GenericPayload payload = new GenericPayload("generic", data);
            PacketHandler handlerImpl = packetHandlers.get(payload.channel());
            if (handlerImpl != null) {
                handlerImpl.handle(player, payload.data());
            }
        });
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
        ClientPlayNetworking.send(new ResourceLocation(ModSync.MOD_ID, channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
    }

    @Override
    public void sendToClient(Object player, String channel, byte[] data) {
        if (!(player instanceof ServerPlayer)) return;
        ServerPlayNetworking.send((ServerPlayer) player, new ResourceLocation(ModSync.MOD_ID, channel), new FriendlyByteBuf(Unpooled.wrappedBuffer(data)));
    }

    @Override
    public void registerPacketHandler(String channel, PacketHandler handler) {
        packetHandlers.put(channel, handler);
    }

    @Override
    public void scheduleRestart() {
        if (!isClient()) return;
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }

    @Override
    public void showNotification(String title, String message, NotificationType type) {
        if (!isClient()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[" + title + "] " + message));
        }
    }

    @Override
    public CompletableFuture<DialogResult> showDialog(String title, String message, List<String> options) {
        if (!isClient()) return CompletableFuture.completedFuture(DialogResult.CANCEL);

        CompletableFuture<DialogResult> future = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
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
        Minecraft.getInstance().execute(() ->
                showNotification("ModSync", "Reconnecting to " + serverAddress, NotificationType.INFO));
    }

    /**
     * Simple payload wrapper for Fabric networking
     */
    public static class GenericPayload {
        private final String channel;
        private final byte[] data;

        public GenericPayload(String channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }

        public String channel() { return channel; }
        public byte[] data() { return data; }
    }
}
