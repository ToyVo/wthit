package mcp.mobius.waila.paper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.reflect.TypeToken;
import mcp.mobius.waila.mcless.config.ConfigIo;
import mcp.mobius.waila.plugin.PluginInfo;
import mcp.mobius.waila.plugin.PluginLoader;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import static mcp.mobius.waila.mcless.network.NetworkConstants.CONFIG_BOOL;
import static mcp.mobius.waila.mcless.network.NetworkConstants.CONFIG_DOUBLE;
import static mcp.mobius.waila.mcless.network.NetworkConstants.CONFIG_STRING;
import static mcp.mobius.waila.mcless.network.NetworkConstants.NETWORK_VERSION;

public class PaperWaila extends JavaPlugin implements Listener, PluginMessageListener {

    static final String CHANNEL_VERSION = "waila:version";
    static final String CHANNEL_CONFIG = "waila:config";
    static final String CHANNEL_BLACKLIST = "waila:blacklist";

    static final String CHANNEL_BLOCK = "waila:block";
    static final String CHANNEL_ENTITY = "waila:entity";

    static final String CHANNEL_DATA_TYPED = "waila:data_typed";
    // Matches RawDataResponsePlayS2CPacket.TYPE.id() → Waila.id("data")
    static final String CHANNEL_DATA_RAW = "waila:data";

    // BadPackets channel sync protocol - required for Fabric clients using BadPackets
    static final String CHANNEL_BP_SYNC = "badpackets:channel_sync";
    static final byte BP_SYNC_INITIAL = 0x01;

    private final Consumer<String> warnLogger = msg -> getLogger().log(Level.WARNING, msg);
    private final BiConsumer<String, Throwable> errorLogger = (msg, t) -> getLogger().log(Level.SEVERE, msg, t);

    private final ConfigIo<Map<String, Map<String, JsonPrimitive>>> pluginConfigIo = new ConfigIo<>(
        warnLogger, errorLogger,
        false, () -> path -> null,
        new GsonBuilder().setPrettyPrinting().create(),
        new TypeToken<Map<String, Map<String, JsonPrimitive>>>() {
        }.getType(),
        LinkedHashMap::new);

    private final ConfigIo<BlacklistConfig> blacklistConfigIo = new ConfigIo<>(
        warnLogger, errorLogger,
        false, () -> path -> null,
        new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapter(NamespacedKey.class, new NamespacedKeySerializer())
            .create(),
        BlacklistConfig.class,
        BlacklistConfig::new);

    private Map<String, Map<String, JsonPrimitive>> pluginConfig;
    private BlacklistConfig blacklistConfig;
    private PaperDataHandler dataHandler;

    @Override
    public void onLoad() {
        pluginConfig = pluginConfigIo.read(getDataFolder().toPath().resolve("waila_plugins.json"));
        blacklistConfig = blacklistConfigIo.read(getDataFolder().toPath().resolve("blacklist.json"));
    }

    @Override
    public void onEnable() {
        // ServiceLoader.load() uses the thread context classloader, which on Paper's plugin system
        // is not the plugin classloader. Swap it for the entire onEnable so that ServiceLoader can
        // find our services (ICommonService, PluginLoader, etc.) in the plugin JAR.
        var previousClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            dataHandler = new PaperDataHandler(this);

            Bukkit.getPluginManager().registerEvents(this, this);

            // BadPackets channel sync - register incoming to receive the client's response.
            // Outgoing sync is sent via NMS (bypasses Bukkit's channel check).
            Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL_BP_SYNC, this);

            // Outgoing channels
            Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_VERSION);
            Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_CONFIG);
            Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_BLACKLIST);
            Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_DATA_TYPED);
            Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_DATA_RAW);

            // Incoming channels
            Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL_BLOCK, this);
            Bukkit.getMessenger().registerIncomingPluginChannel(this, CHANNEL_ENTITY, this);

            // Initialize the WTHIT plugin system - loads data providers
            PluginLoader.INSTANCE.loadPlugins();

            var plugins = PluginInfo.getAll();
            getLogger().info("[WTHIT] Loaded " + plugins.size() + " plugins:");
            for (var info : plugins) {
                getLogger().info("[WTHIT]   " + info.getPluginId() + " (enabled=" + info.isEnabled() + ")");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previousClassLoader);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Send BadPackets channel sync to the Fabric client so it registers waila:* channels.
        // BadPackets on the client only sends minecraft:register for its managed channels AFTER
        // receiving a badpackets:channel_sync INITIAL packet from the server.
        // Use a 1-tick delay to ensure the player's network handler is fully ready.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) {
                sendBadPacketsChannelSync(event.getPlayer());
            }
        }, 1L);
    }

    @EventHandler
    @SuppressWarnings("UnstableApiUsage")
    public void onPlayerRegisterChannelEvent(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();
        getLogger().info("[WTHIT] Channel registered: " + event.getChannel() + " by " + player.getName());

        if (event.getChannel().equals(CHANNEL_VERSION)) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            writeVarInt(out, NETWORK_VERSION);
            player.sendPluginMessage(this, CHANNEL_VERSION, out.toByteArray());
            getLogger().info("[WTHIT] Sent version packet (v" + NETWORK_VERSION + ") to " + player.getName());
        }

        if (event.getChannel().equals(CHANNEL_BLACKLIST)) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            writeNamespacedKeys(out, blacklistConfig.blocks);
            writeNamespacedKeys(out, blacklistConfig.blockEntityTypes);
            writeNamespacedKeys(out, blacklistConfig.entityTypes);
            player.sendPluginMessage(this, CHANNEL_BLACKLIST, out.toByteArray());
            getLogger().info("[WTHIT] Sent blacklist packet to " + player.getName());
        }

        if (event.getChannel().equals(CHANNEL_CONFIG)) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            writeVarInt(out, pluginConfig.size());
            pluginConfig.forEach((namespace, map) -> {
                writeUtf(out, namespace);
                writeVarInt(out, map.size());
                map.forEach((key, value) -> {
                    writeUtf(out, key);
                    if (value.isBoolean()) {
                        out.writeByte(CONFIG_BOOL);
                        out.writeBoolean(value.getAsBoolean());
                    } else if (value.isNumber()) {
                        out.writeByte(CONFIG_DOUBLE);
                        out.writeDouble(value.getAsDouble());
                    } else {
                        out.writeByte(CONFIG_STRING);
                        writeUtf(out, value.getAsString());
                    }
                });
            });
            player.sendPluginMessage(this, CHANNEL_CONFIG, out.toByteArray());
            getLogger().info("[WTHIT] Sent config packet (" + pluginConfig.size() + " namespaces) to " + player.getName());
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        getLogger().info("[WTHIT] Received plugin message on " + channel + " from " + player.getName() + " (" + message.length + " bytes)");
        switch (channel) {
            case CHANNEL_BP_SYNC -> getLogger().info("[WTHIT] Received BadPackets channel sync from " + player.getName());
            case CHANNEL_BLOCK -> dataHandler.handleBlockRequest(player, message);
            case CHANNEL_ENTITY -> dataHandler.handleEntityRequest(player, message);
        }
    }

    /**
     * Sends a BadPackets channel_sync INITIAL packet to the client via NMS, advertising all waila:* channels.
     * This is required because BadPackets on the Fabric client only registers channels via
     * minecraft:register AFTER it receives this sync from the server.
     * <p>
     * Must bypass Bukkit's {@code sendPluginMessage} because Bukkit silently drops messages
     * to channels the client hasn't registered yet — but the whole point of this packet is to
     * trigger that registration.
     * <p>
     * Wire format: [byte: 0x01] [varint: namespace_count] [for each: utf namespace, varint path_count, [utf path...]]
     */
    private void sendBadPacketsChannelSync(Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeByte(BP_SYNC_INITIAL);

        // All channels are in the "waila" namespace
        writeVarInt(out, 1); // 1 namespace group
        writeUtf(out, "waila");
        writeVarInt(out, 7); // 7 channel paths
        writeUtf(out, "version");
        writeUtf(out, "config");
        writeUtf(out, "blacklist");
        writeUtf(out, "block");
        writeUtf(out, "entity");
        writeUtf(out, "data_typed");
        writeUtf(out, "data");

        // Send directly via NMS to bypass Bukkit's channel registration check
        var nmsPlayer = ((CraftPlayer) player).getHandle();
        var packet = new ClientboundCustomPayloadPacket(
            new DiscardedPayload(Identifier.parse(CHANNEL_BP_SYNC), out.toByteArray())
        );
        nmsPlayer.connection.send(packet);
        getLogger().info("[WTHIT] Sent BadPackets channel sync to " + player.getName());
    }

    static void writeVarInt(ByteArrayDataOutput out, int i) {
        while ((i & -128) != 0) {
            out.writeByte(i & 127 | 128);
            i >>>= 7;
        }
        out.writeByte(i);
    }

    static void writeUtf(ByteArrayDataOutput out, String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32767) {
            throw new RuntimeException("String too big (was " + bytes.length + " bytes encoded, max " + 32767 + ")");
        } else {
            writeVarInt(out, bytes.length);
            out.write(bytes);
        }
    }

    private static void writeNamespacedKeys(ByteArrayDataOutput out, Set<NamespacedKey> keySet) {
        Map<String, List<NamespacedKey>> groups = keySet.stream().collect(Collectors.groupingBy(NamespacedKey::getNamespace));
        writeVarInt(out, groups.size());
        groups.forEach((namespace, keys) -> {
            writeUtf(out, namespace);
            writeVarInt(out, keys.size());
            keys.forEach(key -> writeUtf(out, key.getKey()));
        });
    }

}
