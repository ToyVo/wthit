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
import mcp.mobius.waila.plugin.PluginLoader;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
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
        dataHandler = new PaperDataHandler(this);

        Bukkit.getPluginManager().registerEvents(this, this);

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
    }

    @EventHandler
    @SuppressWarnings("UnstableApiUsage")
    public void onPlayerRegisterChannelEvent(PlayerRegisterChannelEvent event) {
        Player player = event.getPlayer();

        if (event.getChannel().equals(CHANNEL_VERSION)) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            writeVarInt(out, NETWORK_VERSION);
            player.sendPluginMessage(this, CHANNEL_VERSION, out.toByteArray());
        }

        if (event.getChannel().equals(CHANNEL_BLACKLIST)) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            writeNamespacedKeys(out, blacklistConfig.blocks);
            writeNamespacedKeys(out, blacklistConfig.blockEntityTypes);
            writeNamespacedKeys(out, blacklistConfig.entityTypes);
            player.sendPluginMessage(this, CHANNEL_BLACKLIST, out.toByteArray());
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
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        switch (channel) {
            case CHANNEL_BLOCK -> dataHandler.handleBlockRequest(player, message);
            case CHANNEL_ENTITY -> dataHandler.handleEntityRequest(player, message);
        }
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
