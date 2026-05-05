package com.example.ipv6relay;

import com.example.ipv6relay.config.RelayConfig;
import com.example.ipv6relay.events.ClientEvents;
import com.example.ipv6relay.events.CommonEvents;
import com.example.ipv6relay.events.ServerEvents;
import com.example.ipv6relay.networking.RelayServer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(IPv6Relay.MOD_ID)
public class IPv6Relay {
    public static final String MOD_ID = "ipv6relay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static RelayServer relayServer;
    private static com.example.ipv6relay.networking.IPv6PacketRelay packetRelay;
    
    // 自动检测到的局域网端口
    private static int detectedLanPort = -1;

    public IPv6Relay() {
        // 注册配置
        RelayConfig.register();

        // 注册 mod 生命周期
        var modBus = net.neoforged.fml.ModLoadingContext.get().getActiveContainer().getEventBus();
        modBus.addListener(this::clientSetup);
        modBus.addListener(this::serverSetup);
        modBus.addListener(this::commonSetup);

        LOGGER.info("IPv6 Relay Mod initialized");
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LOGGER.info("IPv6 Relay Client Setup");
        ClientEvents.onClientSetup(event);
    }

    private void serverSetup(final FMLDedicatedServerSetupEvent event) {
        LOGGER.info("IPv6 Relay Server Setup");
        ServerEvents.onServerSetup(event);

        // 如果配置启用了中继服务器，在服务端启动
        if (RelayConfig.enableServer) {
            if (relayServer == null) {
                relayServer = new RelayServer();
                relayServer.start();
                LOGGER.info("内嵌中继服务器已启动");
            }
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        CommonEvents.onCommonSetup(event);
    }

    public static RelayServer getRelayServer() {
        return relayServer;
    }

    public static com.example.ipv6relay.networking.IPv6PacketRelay getPacketRelay() {
        return packetRelay;
    }

    public static void setPacketRelay(com.example.ipv6relay.networking.IPv6PacketRelay relay) {
        packetRelay = relay;
    }
    
    public static int getDetectedLanPort() { return detectedLanPort; }
    public static void setDetectedLanPort(int port) { 
        detectedLanPort = port; 
        if (port > 0) {
            LOGGER.info("检测到局域网端口: {}", port);
        }
    }
}
