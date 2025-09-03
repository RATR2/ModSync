package com.rat.modsync.fabric;

import com.rat.modsync.common.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.server.network.ServerPlayerEntity;
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

    public static final Identifier GENERIC_PACKET_ID = Identifier.of(ModSync.MOD_ID, "generic");

    @Override
    public void onInitialize() {
        INSTANCE = this;
        ModSync.initialize(this);
        setupNetworking();
    }

    @Override
    public void onInitializeClient() {
        System.out.println("[ModSync] onInitializeClient called");
        LOGGER.info("ModSyncFabric: Client initialization started");
        ModSync.initialize(this);
        ModSync.handleStartup();
    }

    @Override
    public void onInitializeServer() {
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            sendServerHandshake(handler.getPlayer());
        });
    }

    private void setupNetworking() {
        // Register the payload type
        PayloadTypeRegistry.playS2C().register(GenericPayload.ID, GenericPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GenericPayload.ID, GenericPayload.CODEC);

        // Client-side handler
        if (isClient()) {
            ClientPlayNetworking.registerGlobalReceiver(GenericPayload.ID, (payload, context) -> {
                PacketHandler handlerImpl = packetHandlers.get(payload.channel());
                if (handlerImpl != null) {
                    handlerImpl.handle(null, payload.data());
                }
            });
        }

        // Server-side handler
        ServerPlayNetworking.registerGlobalReceiver(GenericPayload.ID, (payload, context) -> {
            PacketHandler handlerImpl = packetHandlers.get(payload.channel());
            if (handlerImpl != null) {
                handlerImpl.handle(context.player(), payload.data());
            }
        });
    }

    private void sendServerHandshake(ServerPlayerEntity player) {
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
        String fileName;
        try {
            // Some mod origins (NESTED, BUILTIN, etc.) do not support getPaths()
            if (container.getOrigin().getKind().name().equalsIgnoreCase("PATH")) {
                fileName = container.getOrigin().getPaths().get(0).getFileName().toString();
            } else {
                fileName = modId + ".jar"; // Fallback for NESTED or BUILTIN mods
            }
        } catch (Exception e) {
            fileName = modId + ".jar"; // Fallback on error
        }
        return new ModInfo(modId, version, name, fileName);
    }

    @Override
    public void sendToServer(String channel, byte[] data) {
        if (!isClient()) return;
        LOGGER.info("Client > Sending packet to server on channel: " + channel);
        ClientPlayNetworking.send(new GenericPayload(channel, data));
    }

    @Override
    public void sendToClient(Object player, String channel, byte[] data) {
        if (!(player instanceof ServerPlayerEntity)) return;
        LOGGER.info("Server > Sending packet to client on channel: " + channel);
        ServerPlayNetworking.send((ServerPlayerEntity) player, new GenericPayload(channel, data));
    }

    @Override
    public void registerPacketHandler(String channel, PacketHandler handler) {
        packetHandlers.put(channel, handler);
    }

    @Override
    public void scheduleRestart() {
        if (!isClient()) return;
        MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().stop());
    }

    @Override
    public void showNotification(String title, String message, NotificationType type) {
        if (!isClient()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[" + title + "] " + message), false);
        }
    }

    @Override
    public CompletableFuture<DialogResult> showDialog(String title, String message, List<String> options) {
        if (!isClient()) return CompletableFuture.completedFuture(DialogResult.CANCEL);

        CompletableFuture<DialogResult> future = new CompletableFuture<>();
        MinecraftClient.getInstance().execute(() -> {
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
        MinecraftClient.getInstance().execute(() ->
                showNotification("ModSync", "Reconnecting to " + serverAddress, NotificationType.INFO));
    }

    /**
     * Custom payload for Fabric networking
     */
    public static class GenericPayload implements CustomPayload {
        public static final CustomPayload.Id<GenericPayload> ID = new CustomPayload.Id<>(GENERIC_PACKET_ID);
        public static final PacketCodec<PacketByteBuf, GenericPayload> CODEC = PacketCodec.of(
                (value, buf) -> {
                    buf.writeString(value.channel);
                    buf.writeByteArray(value.data);
                },
                (buf) -> new GenericPayload(buf.readString(), buf.readByteArray())
        );

        private final String channel;
        private final byte[] data;

        public GenericPayload(String channel, byte[] data) {
            this.channel = channel;
            this.data = data;
        }

        public String channel() {
            return channel;
        }

        public byte[] data() {
            return data;
        }

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}