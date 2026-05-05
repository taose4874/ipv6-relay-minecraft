package com.example.ipv6relay.events;

import com.example.ipv6relay.IPv6Relay;
import com.example.ipv6relay.gui.PauseMenuIntegration;
import com.example.ipv6relay.gui.RelayButton;
import com.example.ipv6relay.networking.IPv6PacketRelay;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientEvents {
    private static boolean wasInLevel = false;
    private static int lastKnownLanPort = -1;
    private static int lastValidPort = -1; // 记录最后一个有效的端口
    private static int failedScanCount = 0; // 连续失败的扫描次数
    private static final int MAX_FAILED_SCANS = 5; // 允许连续失败 5 次才重置
    
    // 限制扫描频率的变量
    private static int tickCount = 0;
    private static final int SCAN_INTERVAL_TICKS = 40; // 大约每 2 秒扫描一次
    private static final AtomicBoolean isScanning = new AtomicBoolean(false);
    private static ExecutorService scanExecutor;

    public static void onClientSetup(FMLClientSetupEvent event) {
        RelayButton.init();
        NeoForge.EVENT_BUS.register(new PauseMenuIntegration());
        NeoForge.EVENT_BUS.register(new ClientEventHandler());
        scanExecutor = Executors.newSingleThreadExecutor();
    }

    public static class ClientEventHandler {
        /**
         * 使用 PlayerTickEvent 检测是否在世界中
         */
        @SubscribeEvent
        public void onPlayerTick(PlayerTickEvent.Post event) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            boolean inLevel = mc.level != null;

            // 检测局域网服务器端口（限制频率）
            if (inLevel) {
                detectLanServerPortAsync(mc);
            }

            // 检查是否退出世界
            if (wasInLevel && !inLevel) {
                disconnectRelay();
                // 重置端口检测，但保留 lastValidPort
                lastKnownLanPort = -1;
                failedScanCount = 0;
                IPv6Relay.setDetectedLanPort(-1);
            }
            wasInLevel = inLevel;
        }
    }

    /**
     * 异步检测局域网端口，避免在主线程阻塞
     */
    private static void detectLanServerPortAsync(net.minecraft.client.Minecraft mc) {
        // 限制扫描频率
        tickCount++;
        if (tickCount < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCount = 0;
        
        // 如果已经在扫描中，跳过
        if (!isScanning.compareAndSet(false, true)) {
            return;
        }
        
        // 在异步线程中扫描
        scanExecutor.submit(() -> {
            try {
                int detectedPort = -1;
                
                // 检查是否有 IntegratedServer 正在运行
                if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null) {
                    var server = mc.getSingleplayerServer();
                    
                    // 方式1: 尝试使用反射获取 IntegratedServer 的端口字段
                    detectedPort = tryGetPortByReflection(server);
                    
                    // 如果还是没获取到，尝试一些常见的端口范围
                    if (detectedPort <= 0) {
                        detectedPort = scanCommonPorts();
                    }
                }
                
                // 只在主线程更新 Minecraft 相关的东西
                final int finalDetectedPort = detectedPort;
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    // 更新检测到的端口
                    if (finalDetectedPort > 0) {
                        failedScanCount = 0; // 重置失败计数
                        lastValidPort = finalDetectedPort; // 记录最后一个有效端口
                        if (finalDetectedPort != lastKnownLanPort) {
                            lastKnownLanPort = finalDetectedPort;
                            IPv6Relay.setDetectedLanPort(finalDetectedPort);
                        }
                    } else {
                        // 没有检测到端口，增加失败计数
                        failedScanCount++;
                        // 只有连续失败超过最大次数才重置
                        if (failedScanCount >= MAX_FAILED_SCANS && lastKnownLanPort != -1) {
                            lastKnownLanPort = -1;
                            IPv6Relay.setDetectedLanPort(-1);
                        } else if (lastKnownLanPort == -1 && lastValidPort > 0) {
                            // 如果当前没有端口，但之前有过有效端口，继续使用它
                            lastKnownLanPort = lastValidPort;
                            IPv6Relay.setDetectedLanPort(lastValidPort);
                        }
                    }
                });
            } finally {
                isScanning.set(false);
            }
        });
    }

    /**
     * 尝试获取 IntegratedServer 的端口
     */
    private static int tryGetPortByReflection(Object server) {
        try {
            var serverClass = server.getClass();
            
            for (var field : serverClass.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    var name = field.getName().toLowerCase();
                    if (name.contains("port") || name.contains("listen") || name.contains("bind")) {
                        var type = field.getType();
                        if (type == int.class || type == Integer.class) {
                            int val = field.getInt(server);
                            if (val > 0 && val <= 65535) {
                                return val;
                            }
                        }
                    }
                } catch (Exception e) {
                    // 跳过这个字段，继续尝试下一个
                }
            }
            
            for (var field : serverClass.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    var type = field.getType().getName().toLowerCase();
                    if (type.contains("socket") || type.contains("address")) {
                        var val = field.get(server);
                        if (val != null) {
                            // 检查是否有 getPort 方法
                            try {
                                var getPortMethod = val.getClass().getMethod("getPort");
                                int port = (int) getPortMethod.invoke(val);
                                if (port > 0 && port <= 65535) {
                                    return port;
                                }
                            } catch (Exception e) {
                                // 继续尝试其他字段
                            }
                        }
                    }
                } catch (Exception e) {
                    // 跳过这个字段
                }
            }
        } catch (Exception e) {
            // 反射失败
        }
        return -1;
    }

    /**
     * 扫描一些常见的端口范围
     */
    private static int scanCommonPorts() {
        // 先检查默认端口 25565
        if (isPortListening(25565)) {
            return 25565;
        }
        
        // 快速扫描几个临时端口
        int[] portsToScan = { 49152, 49153, 49154, 49155, 49156, 49157, 49158, 49159, 49160 };
        for (int port : portsToScan) {
            if (isPortListening(port)) {
                return port;
            }
        }
        
        return -1;
    }

    /**
     * 检查一个端口是否正在监听
     */
    private static boolean isPortListening(int port) {
        try {
            var socket = new java.net.Socket();
            // 超时时间更短
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 30);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void disconnectRelay() {
        IPv6PacketRelay relay = IPv6Relay.getPacketRelay();
        if (relay != null) {
            if (relay.isConnected()) {
                relay.disconnect();
            }
            IPv6Relay.setPacketRelay(null);
            IPv6Relay.LOGGER.info("已断开与中继服务器的连接");
        }
    }
}
