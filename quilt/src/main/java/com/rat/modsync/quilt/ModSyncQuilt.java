package com.yourname.modsync.quilt;

import com.yourname.modsync.common.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.qsl.base.api.entrypoint.client.ClientModInitializer;
import org.quiltmc.qsl.base.api.entrypoint.server.DedicatedServerModInitializer;
import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;
import org.quiltmc.qsl.networking.api.PacketByteBufs;
import org.quiltmc.qsl.networking.api.PayloadTypeRegistry;
import org.quiltmc.qsl.networking.api.ServerPlayNetworking;
import org.quiltmc.qsl.networking.api.client.ClientPlayNetworking;
import org.quiltmc.qsl.networking.api.ServerPlayConnectionEvents;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ModSyncQuilt implements ModInitializer, ClientModInitializer, DedicatedServerModInitializer, Platform {
    private static final Logger LOGGER = Logger.getLogger(ModSyncQuilt.class.getName());
    private static ModSyncQuilt INSTANCE;

    private final Map<String, PacketHandler> packetHandlers = new ConcurrentHashMap<>();

    @Override
    public void onInitialize(ModContainer mod) {
        INSTANCE = this;
        ModSync.initialize(this);

        setupNetworking();
    }

    @Override
    public void onInitializeClient(ModContainer mod) {
        // Client-specific initialization
        ModSync.handleStartup();
    }

    @Override
    public void onInitializeServer(ModContainer mod) {
        // Server-specific initialization
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            sendServerHandshake(player);
        });
    }

    private void setupNetworking() {
        // Register payload type
        PayloadTypeRegistry.playS2C().register(GenericPayload.TYPE, GenericPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(GenericPayload.TYPE, GenericPayload.CODEC);

        // Register handlers
        if (QuiltLoader.getEnvironmentType().name().equals("CLIENT")) {
            ClientPlayNetworking.registerGlobalReceiver(GenericPayload.TYPE, this::handleClientPacket);
        }

        ServerPlayNetworking.registerGlobalReceiver(GenericPayload.TYPE, this::handleServerPacket);
    }

    private void handleClientPacket(GenericPayload payload, ClientPlayNetworking.Context context) {
        PacketHandler handler = packetHandlers.get(payload.channel());
        if (handler != null) {
            handler.handle(null, payload.data());
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
        return LoaderType.QUILT;
    }

    @Override
    public boolean isClient() {
        return QuiltLoader.getEnvironmentType().name().equals("CLIENT");
    }

    @Override
    public boolean isServer() {
        return !isClient();
    }

    @Override
    public Path getModsDirectory() {
        return QuiltLoader.getGameDir().resolve("mods");
    }

    @Override
    public List<ModInfo> getLoadedMods() {
        return QuiltLoader.getAllMods().stream()
                .filter(mod -> !mod.metadata().id().equals("minecraft") &&
                        !mod.metadata().id().equals("quilt_loader") &&
                        !mod.metadata().id().equals("quilted_fabric_api"))
                .map(this::convertToModInfo)
                .collect(Collectors.toList());
    }

    private ModInfo convertToModInfo(ModContainer container) {
        String modId = container.metadata().id();
        String version = container.metadata().version().raw();
        String name = container.metadata().name();
        String fileName = container.getSourcePaths().get(0).getFileName().toString();

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
        return QuiltLoader.getModContainer("minecraft")
                .map(mod -> mod.metadata().version().raw())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return QuiltLoader.isModLoaded(modId);
    }

    @Override
    public ModInfo getModInfo(String modId) {
        return QuiltLoader.getModContainer(modId)
                .map(this::convertToModInfo)
                .orElse(null);
    }

    @Override
    public void connectToServer(String serverAddress) {
        if (!isClient()) return;

        Minecraft.getInstance().execute(() -> {
            // Implementation would connect to server programmatically
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