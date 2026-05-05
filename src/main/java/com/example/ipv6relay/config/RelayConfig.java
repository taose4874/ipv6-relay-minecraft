package com.example.ipv6relay.config;

import java.io.*;
import java.util.Properties;

/**
 * 简单的配置管理 - 不依赖 NeoForge 配置系统，避免兼容性问题
 */
public class RelayConfig {
    private static final String CONFIG_FILE = "config/ipv6relay.properties";
    
    public static String relayHost = "";
    public static int relayPort = 25566;
    public static boolean autoConnect = false;
    public static String targetServer = "";
    public static int targetPort = 25565;
    public static boolean enableServer = false;
    
    static {
        load();
    }
    
    public static void register() {
        // 什么也不做，兼容以前的接口
    }
    
    public static void save() {
        Properties props = new Properties();
        props.setProperty("relayHost", relayHost);
        props.setProperty("relayPort", String.valueOf(relayPort));
        props.setProperty("autoConnect", String.valueOf(autoConnect));
        props.setProperty("targetServer", targetServer);
        props.setProperty("targetPort", String.valueOf(targetPort));
        props.setProperty("enableServer", String.valueOf(enableServer));
        
        try {
            // 确保目录存在
            File configDir = new File("config");
            if (!configDir.exists()) {
                configDir.mkdirs();
            }
            
            try (OutputStream os = new FileOutputStream(CONFIG_FILE)) {
                props.store(os, "IPv6 Relay Configuration");
            }
        } catch (Exception e) {
            // 静默失败，不影响游戏
        }
    }
    
    private static void load() {
        File configFile = new File(CONFIG_FILE);
        if (!configFile.exists()) {
            return;
        }
        
        Properties props = new Properties();
        try (InputStream is = new FileInputStream(configFile)) {
            props.load(is);
            
            relayHost = props.getProperty("relayHost", "");
            try {
                relayPort = Integer.parseInt(props.getProperty("relayPort", "25566"));
            } catch (NumberFormatException e) {
                relayPort = 25566;
            }
            
            autoConnect = Boolean.parseBoolean(props.getProperty("autoConnect", "false"));
            targetServer = props.getProperty("targetServer", "");
            
            try {
                targetPort = Integer.parseInt(props.getProperty("targetPort", "25565"));
            } catch (NumberFormatException e) {
                targetPort = 25565;
            }
            
            enableServer = Boolean.parseBoolean(props.getProperty("enableServer", "false"));
        } catch (Exception e) {
            // 静默失败
        }
    }
}