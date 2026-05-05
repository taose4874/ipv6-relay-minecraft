package com.example.ipv6relay.networking;

import com.example.ipv6relay.IPv6Relay;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * IPv6 中继客户端 - 与中继服务器通信
 *
 * 工作流程:
 * 1. 连接中继服务器 (控制通道)
 * 2. 发送 REGISTER:localhost:mcPort → 获取分配的公开端口
 * 3. 持续维护隧道池: 向中继发送 TUNNEL:port → 隧道入队等玩家
 * 4. 玩家连入中继的公开端口 → 中继从队列取隧道 → 桥接到本地MC
 */
public class IPv6PacketRelay {
    private Socket controlSocket;
    private BufferedReader reader;
    private PrintWriter writer;
    private ExecutorService executor;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private String relayHost;
    private int relayPort;
    private volatile int assignedPort = -1;
    private volatile int localPort = 25565;
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> tunnelMaintainerFuture;
    private ScheduledExecutorService tunnelScheduler;

    // 隧道池大小 - 更小的池，减少性能消耗
    private static final int MIN_TUNNELS = 1;
    private static final int MAX_TUNNELS = 3;
    private final AtomicInteger activeTunnels = new AtomicInteger(0);

    public IPv6PacketRelay() {
        // 使用固定大小线程池，避免无限制创建线程
        this.executor = Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r, "IPv6Relay-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 连接到中继服务器
     */
    public boolean connectToRelay(String host, int port) {
        this.relayHost = host;
        this.relayPort = port;
        try {
            controlSocket = new Socket();
            controlSocket.setTcpNoDelay(true);
            controlSocket.setKeepAlive(true);
            controlSocket.setSoTimeout(0); // 无超时，长连接

            // 优先选择 IPv6 地址
            InetAddress[] addresses = InetAddress.getAllByName(host);
            InetAddress selectedAddr = null;
            for (InetAddress addr : addresses) {
                if (addr instanceof Inet6Address) {
                    selectedAddr = addr;
                    break;
                }
                if (selectedAddr == null) {
                    selectedAddr = addr;
                }
            }

            controlSocket.connect(new InetSocketAddress(selectedAddr, port), 10000);
            reader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), "UTF-8"));
            writer = new PrintWriter(new OutputStreamWriter(controlSocket.getOutputStream(), "UTF-8"), true);
            connected.set(true);
            IPv6Relay.LOGGER.info("已连接中继服务器 {}:{} (地址: {})", host, port, selectedAddr);
            executor.submit(this::readLoop);

            // 心跳
            heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "IPv6Relay-Heartbeat");
                t.setDaemon(true);
                return t;
            });
            heartbeatFuture = heartbeatScheduler.scheduleAtFixedRate(() -> {
                if (connected.get() && writer != null) {
                    try {
                        writer.println("HEARTBEAT");
                    } catch (Exception e) {
                        IPv6Relay.LOGGER.warn("心跳发送失败: {}", e.getMessage());
                    }
                }
            }, 30, 30, TimeUnit.SECONDS);

            return true;
        } catch (IOException e) {
            IPv6Relay.LOGGER.error("连接中继失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 向中继注册本地MC服务器，获取公开端口
     */
    public void registerServer(int mcPort) {
        this.localPort = mcPort;
        if (connected.get() && writer != null) {
            String cmd = "REGISTER:localhost:" + mcPort;
            writer.println(cmd);
            writer.flush();
            IPv6Relay.LOGGER.info("发送注册: {}", cmd);
        }
    }

    /**
     * 注册成功后启动隧道维护
     */
    private void startTunnelMaintenance() {
        if (tunnelScheduler != null) return;
        tunnelScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IPv6Relay-TunnelMaintainer");
            t.setDaemon(true);
            return t;
        });
        // 每5秒检查一次，保持 MIN_TUNNELS 条隧道
        tunnelMaintainerFuture = tunnelScheduler.scheduleAtFixedRate(() -> {
            if (!connected.get() || assignedPort <= 0) return;
            int current = activeTunnels.get();
            if (current < MIN_TUNNELS) {
                int toCreate = Math.min(MIN_TUNNELS - current, MAX_TUNNELS - current);
                for (int i = 0; i < toCreate; i++) {
                    createTunnel();
                }
            }
        }, 1, 5, TimeUnit.SECONDS);
        // 立即创建初始隧道
        for (int i = 0; i < MIN_TUNNELS; i++) {
            createTunnel();
        }
    }

    /**
     * 创建一条到中继的隧道连接
     * 隧道建立后，中继服务器会将其放入队列等待玩家
     */
    private void createTunnel() {
        if (!connected.get() || assignedPort <= 0) return;
        activeTunnels.incrementAndGet();
        executor.submit(() -> {
            Socket tunnelSocket = null;
            Socket localSocket = null;
            try {
                tunnelSocket = new Socket();
                tunnelSocket.setTcpNoDelay(true);
                tunnelSocket.setKeepAlive(true);

                // 优先 IPv6
                InetAddress[] addresses = InetAddress.getAllByName(relayHost);
                InetAddress selectedAddr = null;
                for (InetAddress addr : addresses) {
                    if (addr instanceof Inet6Address) {
                        selectedAddr = addr;
                        break;
                    }
                    if (selectedAddr == null) {
                        selectedAddr = addr;
                    }
                }

                tunnelSocket.connect(new InetSocketAddress(selectedAddr, relayPort), 10000);

                // 发送 TUNNEL 命令（首行）
                OutputStream out = tunnelSocket.getOutputStream();
                String cmd = "TUNNEL:" + assignedPort + "\n";
                out.write(cmd.getBytes("UTF-8"));
                out.flush();

                IPv6Relay.LOGGER.info("隧道已建立，等待玩家连接，端口 {} (活跃: {})", assignedPort, activeTunnels.get());

                // 隧道已建立，中继会等待玩家连接。
                // 当玩家连接后，中继会把隧道给玩家，此时我们需要把隧道连接到本地MC。
                // 等等，不对！这个隧道实际上是由我们（客户端）保持的，
                // 当玩家通过中继连接后，中继会把玩家的连接桥接到这条隧道，
                // 所以我们需要把这条隧道连接到本地 Minecraft 服务器！
                // 但我们不知道什么时候玩家连接了...
                // 等等，我们的逻辑有误！
                // 让我重新理解：
                // 1. 我们（托管服务器的人）连接中继并注册本地端口 25565
                // 2. 我们预先建立几条隧道到中继，中继把隧道放队列
                // 3. 玩家连接到中继的公开端口，中继从队列取一条隧道
                // 4. 中继把玩家连接和隧道桥接起来

                // 哦，原来的逻辑搞错了！
                // 我们（托管方）的隧道应该是：
                // 隧道一端是中继，另一端是我们本地的 Minecraft 服务器！
                // 这样当中继把玩家连接和隧道桥接后，
                // 数据就会：玩家 -> 中继 -> 我们的隧道 -> 本地MC
                
                // 让我们立即连接本地 MC 服务器并桥接隧道和本地MC
                localSocket = new Socket();
                localSocket.setTcpNoDelay(true);
                localSocket.setKeepAlive(true);
                localSocket.connect(new InetSocketAddress("localhost", localPort), 5000);
                IPv6Relay.LOGGER.info("隧道已连接到本地 Minecraft 服务器，端口 {}", localPort);

                // 现在双向转发：
                // 隧道 -> 本地MC
                // 本地MC -> 隧道
                InputStream tunnelIn = tunnelSocket.getInputStream();
                OutputStream tunnelOut = tunnelSocket.getOutputStream();
                InputStream localIn = localSocket.getInputStream();
                OutputStream localOut = localSocket.getOutputStream();

                Thread t2l = new Thread(() -> pipe(tunnelIn, localOut), "T→L");
                Thread l2t = new Thread(() -> pipe(localIn, tunnelOut), "L→T");
                t2l.start();
                l2t.start();
                IPv6Relay.LOGGER.info("数据桥接已启动");

                t2l.join();
                l2t.interrupt();
            } catch (IOException e) {
                if (connected.get()) {
                    IPv6Relay.LOGGER.debug("隧道创建/维护失败: {}", e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeTunnels.decrementAndGet();
                try {
                    if (tunnelSocket != null) tunnelSocket.close();
                } catch (Exception e) {}
                try {
                    if (localSocket != null) localSocket.close();
                } catch (Exception e) {}
            }
        });
    }

    private void pipe(InputStream in, OutputStream out) {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {}
    }

    /**
     * 控制通道读取循环
     */
    private void readLoop() {
        try {
            String line;
            while (connected.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                IPv6Relay.LOGGER.info("[控制] 收到: {}", line);
                if (line.startsWith("REGISTERED:")) {
                    try {
                        int newPort = Integer.parseInt(line.substring(11));
                        assignedPort = newPort;
                        IPv6Relay.LOGGER.info("注册成功! 公开端口: {}", newPort);
                        // 注册成功后启动隧道维护
                        startTunnelMaintenance();
                    } catch (NumberFormatException e) {
                        IPv6Relay.LOGGER.error("无效的注册响应: {}", line);
                    }
                } else if (line.equals("HEARTBEAT_ACK")) {
                    // 心跳正常
                } else if (line.startsWith("ERROR:")) {
                    IPv6Relay.LOGGER.error("中继错误: {}", line.substring(6));
                }
            }
            connected.set(false);
        } catch (IOException e) {
            if (connected.get()) {
                IPv6Relay.LOGGER.warn("控制连接断开: {}", e.getMessage());
                connected.set(false);
            }
        }
    }

    /**
     * 断开中继连接
     */
    public void disconnect() {
        if (connected.get() && writer != null && assignedPort > 0) {
            try {
                writer.println("UNREGISTER:" + assignedPort);
                writer.flush();
                IPv6Relay.LOGGER.info("发送 UNREGISTER:{}", assignedPort);
            } catch (Exception e) {
                IPv6Relay.LOGGER.warn("发送 UNREGISTER 失败: {}", e.getMessage());
            }
        }
        
        // 先设置为断开状态，避免新操作
        connected.set(false);

        // 停止所有定时任务
        try {
            if (tunnelMaintainerFuture != null) tunnelMaintainerFuture.cancel(true);
            if (tunnelScheduler != null) tunnelScheduler.shutdownNow();
            if (heartbeatFuture != null) heartbeatFuture.cancel(true);
            if (heartbeatScheduler != null) heartbeatScheduler.shutdownNow();
        } catch (Exception e) {
            IPv6Relay.LOGGER.warn("停止定时任务时出错: {}", e.getMessage());
        }

        // 关闭线程池
        try {
            if (executor != null) executor.shutdownNow();
        } catch (Exception e) {
            IPv6Relay.LOGGER.warn("关闭线程池时出错: {}", e.getMessage());
        }

        // 关闭所有连接
        try { if (writer != null) writer.close(); } catch (Exception e) {}
        try { if (reader != null) reader.close(); } catch (Exception e) {}
        try { if (controlSocket != null) controlSocket.close(); } catch (Exception e) {}

        // 重置状态
        assignedPort = -1;
        activeTunnels.set(0);
        relayHost = null;
        
        IPv6Relay.LOGGER.info("已断开与中继服务器的连接");
    }

    public boolean isConnected() { return connected.get(); }
    public int getAssignedPort() { return assignedPort; }
    public String getRelayHost() { return relayHost; }
    public int getActiveTunnels() { return activeTunnels.get(); }
}
