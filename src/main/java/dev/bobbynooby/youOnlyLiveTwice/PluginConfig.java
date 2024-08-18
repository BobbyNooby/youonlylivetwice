package dev.bobbynooby.youOnlyLiveTwice;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public class PluginConfig {
    private final static PluginConfig instance = new PluginConfig();

    private File file;
    private YamlConfiguration config;

    private boolean spoofServer;
    private int spoofedPlayerCount;

    private boolean spoofTabList;

    private boolean hidePlayerJoin;
    private boolean hidePlayerLeave;
    private boolean hidePlayerDeath;
    private boolean hidePlayerRevive;
    private boolean hideNameTags;

    private boolean allowRevive;

    private boolean allowChatWhispers;
    private boolean allowGlobalChat;
    private int chatRadius;

    private PluginConfig() {
    }

    public void load() {
        file = new File(YouOnlyLiveTwice.getInstance().getDataFolder(), "config.yml");

        if (!file.exists()) {
            YouOnlyLiveTwice.getInstance().saveResource("config.yml", false);
        }

        config = new YamlConfiguration();
        config.options().parseComments(true);

        try {
            config.load(file);
        } catch (Exception e) {
            e.printStackTrace();
        }

        spoofServer = config.getBoolean("server-list.spoof", true);
        spoofedPlayerCount = config.getInt("server-list.spoofed-player-count", 1);

        spoofTabList = config.getBoolean("spoof-tab-list", true);

        hidePlayerJoin = config.getBoolean("players.hide-join", true);
        hidePlayerLeave = config.getBoolean("players.hide-leave", true);
        hidePlayerDeath = config.getBoolean("players.hide-death", true);
        hidePlayerRevive = config.getBoolean("players.hide-revive", true);
        hideNameTags = config.getBoolean("players.hide-name-tags", true);

        allowRevive = config.getBoolean("players.allow-revive", true);

        allowChatWhispers = config.getBoolean("chat.allow-whispers", false);
        allowGlobalChat = config.getBoolean("chat.allow-global-chat", false);
        chatRadius = config.getInt("chat.chat-radius", 32);
    }


    public void save() {
        try {
            config.save(file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void set(String path, Object value) {
        config.set(path, value);

        save();
    }

    public boolean getSpoofServer() {
        return spoofServer;
    }

    public void setSpoofServer(boolean spoofServer) {
        this.spoofServer = spoofServer;
        set("server-list.spoof", spoofServer);
    }


    public int getSpoofedPlayerCount() {
        return spoofedPlayerCount;
    }

    public void setSpoofedPlayerCount(int spoofedPlayerCount) {
        this.spoofedPlayerCount = spoofedPlayerCount;
        set("server-list.spoofed-player-count", spoofedPlayerCount);
    }


    public boolean getSpoofTabList() {
        return spoofTabList;
    }

    public void setSpoofTabList(boolean spoofTabList) {
        this.spoofTabList = spoofTabList;
        set("spoof-tab-list", spoofTabList);
    }


    public boolean getHidePlayerJoin() {
        return hidePlayerJoin;
    }

    public void setHidePlayerJoin(boolean hidePlayerJoin) {
        this.hidePlayerJoin = hidePlayerJoin;
        set("players.hide-join", hidePlayerJoin);
    }


    public boolean getHidePlayerLeave() {
        return hidePlayerLeave;
    }

    public void setHidePlayerLeave(boolean hidePlayerLeave) {
        this.hidePlayerLeave = hidePlayerLeave;
        set("players.hide-leave", hidePlayerLeave);
    }


    public boolean getHidePlayerDeath() {
        return hidePlayerDeath;
    }

    public void setHidePlayerDeath(boolean hidePlayerDeath) {
        this.hidePlayerDeath = hidePlayerDeath;
        set("players.hide-death", hidePlayerDeath);
    }


    public boolean getHidePlayerRevive() {
        return hidePlayerRevive;
    }

    public void setHidePlayerRevive(boolean hidePlayerRevive) {
        this.hidePlayerRevive = hidePlayerRevive;
        set("players.hide-revive", hidePlayerRevive);
    }


    public boolean getHideNameTags() {
        return hideNameTags;
    }

    public void setHideNameTags(boolean hideNameTags) {
        this.hideNameTags = hideNameTags;
        set("players.hide-name-tags", hideNameTags);
    }


    public boolean getAllowRevive() {
        return allowRevive;
    }

    public void setAllowRevive(boolean allowRevive) {
        this.allowRevive = allowRevive;
        set("players.allow-revive", allowRevive);
    }


    public boolean getAllowChatWhispers() {
        return allowChatWhispers;
    }

    public void setAllowChatWhispers(boolean allowChatWhispers) {
        this.allowChatWhispers = allowChatWhispers;
        set("chat.allow-whispers", allowChatWhispers);
    }


    public boolean getAllowGlobalChat() {
        return allowGlobalChat;
    }

    public void setAllowGlobalChat(boolean allowGlobalChat) {
        this.allowGlobalChat = allowGlobalChat;
        set("chat.allow-global-chat", allowGlobalChat);
    }


    public int getChatRadius() {
        return chatRadius;
    }

    public void setChatRadius(int chatRadius) {
        this.chatRadius = chatRadius;
        set("chat.chat-radius", chatRadius);
    }

    public static PluginConfig getInstance() {
        return instance;
    }
}
