package com.minecraftly.core.bungee;

import com.google.gson.Gson;
import com.ikeirnez.pluginmessageframework.bungeecord.BungeeGatewayProvider;
import com.ikeirnez.pluginmessageframework.gateway.ProxyGateway;
import com.ikeirnez.pluginmessageframework.gateway.ProxySide;
import com.imaginarycode.minecraft.redisbungee.RedisBungee;
import com.imaginarycode.minecraft.redisbungee.RedisBungeeAPI;
import com.minecraftly.core.MinecraftlyCommon;
import com.minecraftly.core.bungee.handlers.MOTDHandler;
import com.minecraftly.core.bungee.handlers.RedisMessagingHandler;
import com.minecraftly.core.bungee.handlers.SlaveHandler;
import com.minecraftly.core.bungee.handlers.job.JobManager;
import com.minecraftly.core.bungee.handlers.job.handlers.ConnectHandler;
import com.minecraftly.core.bungee.handlers.job.handlers.HumanCheckHandler;
import com.minecraftly.core.bungee.handlers.job.queue.ConnectJobQueue;
import com.minecraftly.core.bungee.handlers.job.queue.HumanCheckJobQueue;
import com.minecraftly.core.bungee.handlers.module.PlayerWorldsHandler;
import com.minecraftly.core.bungee.handlers.module.TpaHandler;
import com.minecraftly.core.redis.RedisHelper;
import com.minecraftly.core.redis.message.ServerInstanceData;
import com.minecraftly.core.redis.message.gson.GsonHelper;
import com.minecraftly.core.utilities.GComputeUtilities;
import com.minecraftly.core.utilities.Utilities;
import lc.vq.exhaust.bungee.command.CommandManager;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.api.scheduler.TaskScheduler;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Created by Keir on 24/03/2015.
 */
public class MclyCoreBungeePlugin extends Plugin implements MinecraftlyBungeeCore {

    private static MclyCoreBungeePlugin instance;

    public static MclyCoreBungeePlugin getInstance() {
        return instance;
    }

    public static final BaseComponent[] MESSAGE_NOT_HUMAN = new ComponentBuilder("You must first confirm you are human.").color(ChatColor.RED).create();

    private long computeUniqueId;
    private File configurationFile;
    private ConfigurationProvider configurationProvider;
    private Configuration configuration;

    private CommandManager commandManager;
    private ProxyGateway<ProxiedPlayer, Server, ServerInfo> gateway;
    private RedisBungeeAPI redisBungeeAPI;
    private Gson gson = GsonHelper.getGsonWithAdapters();

    private SlaveHandler slaveHandler;
    private final JobManager jobManager = new JobManager();
    private final HumanCheckManager humanCheckManager = new HumanCheckManager();

    @Override
    public void onLoad() {
        instance = this;
    }

    @Override
    public void onEnable() {
        Utilities.createDirectory(getDataFolder());
        configurationFile = new File(getDataFolder(), "config.yml");
        configurationProvider = ConfigurationProvider.getProvider(YamlConfiguration.class);

        try {
            configurationFile.createNewFile();
            configuration = configurationProvider.load(configurationFile);
            copyDefaults();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error loading configuration.", e);
            return;
        }

        redisBungeeAPI = RedisBungee.getApi();

        if (redisBungeeAPI == null) {
            getLogger().severe("RedisBungeeAPI is not available.");
            return;
        }

        try {
            long configUniqueId = configuration.getLong("debug.uniqueId");
            computeUniqueId = configUniqueId == -1 ? GComputeUtilities.queryUniqueId() : configUniqueId;
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error querying Compute API.", e);
            return;
        }

        try {
            forceSetDefaultServer(String.valueOf(computeUniqueId));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            getLogger().log(Level.SEVERE, "Error whilst applying reflection for default server.", e);
            return;
        }

        PluginManager pluginManager = getProxy().getPluginManager();
        TaskScheduler taskScheduler = getProxy().getScheduler();
        gateway = BungeeGatewayProvider.getGateway(MinecraftlyCommon.GATEWAY_CHANNEL, ProxySide.SERVER, this);

        slaveHandler = new SlaveHandler(gson, ((RedisBungee) pluginManager.getPlugin("RedisBungee")).getPool(), getLogger(), String.valueOf(computeUniqueId));
        pluginManager.registerListener(this, slaveHandler);
        taskScheduler.schedule(this, slaveHandler, RedisHelper.HEARTBEAT_INTERVAL, RedisHelper.HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
        slaveHandler.initialize();

        redisBungeeAPI.registerPubSubChannels(RedisMessagingHandler.MESSAGE_PLAYER_CHANNEL, ServerInstanceData.CHANNEL, RedisHelper.CHANNEL_SERVER_GOING_DOWN);
        pluginManager.registerListener(this, new RedisMessagingHandler());

        HumanCheckJobQueue humanCheckJobQueue = new HumanCheckJobQueue(humanCheckManager);
        ConnectJobQueue connectJobQueue = new ConnectJobQueue();
        HumanCheckHandler humanCheckHandler = new HumanCheckHandler(jobManager, humanCheckManager);
        jobManager.addJobQueue(humanCheckJobQueue);
        jobManager.addJobQueue(connectJobQueue);
        gateway.registerListener(humanCheckHandler);
        pluginManager.registerListener(this, humanCheckHandler);
        pluginManager.registerListener(this, new ConnectHandler(connectJobQueue, getLogger()));

        PlayerWorldsHandler playerWorldsHandler = new PlayerWorldsHandler(gateway, jobManager, humanCheckManager, redisBungeeAPI);
        TpaHandler tpaHandler = new TpaHandler(this);
        PreSwitchHandler preSwitchHandler = new PreSwitchHandler(gateway, getLogger());

        gateway.registerListener(playerWorldsHandler);
        gateway.registerListener(preSwitchHandler);

        commandManager = new CommandManager(this);
        commandManager.builder()
                .registerMethods(playerWorldsHandler)
                .registerMethods(tpaHandler);
        commandManager.build();

        pluginManager.registerListener(this, playerWorldsHandler);
        pluginManager.registerListener(this, tpaHandler);
        pluginManager.registerListener(this, preSwitchHandler);
        pluginManager.registerListener(this, new MOTDHandler(jobManager, new File(getDataFolder(), "motd.json"), getLogger()));

        taskScheduler.schedule(this, tpaHandler, 5, TimeUnit.MINUTES);
    }

    @Override
    public void onDisable() {
        instance = null;
    }

    private void copyDefaults() {
        Configuration defaultConfiguration;

        try (InputStream inputStream = getResourceAsStream("config.yml")) {
            defaultConfiguration = configurationProvider.load(inputStream);
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Error copying defaults to config.", e);
            return;
        }

        boolean updated = false;

        for (String key : defaultConfiguration.getKeys()) {
            if (configuration.get(key) == null) {
                configuration.set(key, defaultConfiguration.get(key));
                updated = true;
            }
        }

        if (updated) {
            saveConfig();
        }
    }

    private void saveConfig() {
        try {
            configurationProvider.save(configuration, configurationFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Error saving configuration.", e);
        }
    }

    private void forceSetDefaultServer(String server) throws NoSuchFieldException, IllegalAccessException {
        for (ListenerInfo listenerInfo : getProxy().getConfig().getListeners()) {
            Field defaultServerField = ListenerInfo.class.getDeclaredField("defaultServer");
            defaultServerField.setAccessible(true);
            Utilities.removeFinal(defaultServerField);
            defaultServerField.set(listenerInfo, server);
        }
    }

    @Override
    public long getComputeUniqueId() {
        return computeUniqueId;
    }

    @Override
    public ProxyGateway<ProxiedPlayer, Server, ServerInfo> getGateway() {
        return gateway;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public RedisBungeeAPI getRedisBungeeAPI() {
        return redisBungeeAPI;
    }

    @Override
    public Gson getGson() {
        return gson;
    }

    @Override
    public JobManager getJobManager() {
        return jobManager;
    }

    @Override
    public HumanCheckManager getHumanCheckManager() {
        return humanCheckManager;
    }
}
