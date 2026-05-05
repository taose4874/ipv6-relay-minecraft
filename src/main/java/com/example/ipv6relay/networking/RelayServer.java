package com.example.ipv6relay.networking;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 中继服务器 (内嵌版)
 *
 * 端口分配策略：优先复用已释放的端口，无可用时递增
 */
public class RelayServer {
    private ServerSocket controlServerSocket;
    private ExecutorService workerPool;
    private volatile boolean running;
    private int listenPort;

    private final ConcurrentHashMap<Integer, PrintWriter> controlWriters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> registeredTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ServerSocket> playerListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, BlockingQueue<Socket>> tunnelQueues = new ConcurrentHashMap<>();

    // 端口分配：递增基准 + 空闲端口池
    private final AtomicInteger nextPort = new AtomicInteger();
    private final ConcurrentLinkedQueue<Integer> freePorts = new ConcurrentLinkedQueue<>();
    private final Set<Integer> usedPorts = ConcurrentHashMap.newKeySet();

    public RelayServer() { this(25566); }
    public RelayServer(int listenPort) {
        this.listenPort = listenPort;
        this.workerPool = Executors.newCachedThreadPool();
        this.nextPort.set(listenPort + 1);
    }

    private synchronized int allocatePort() {
        Integer port = freePorts.poll();
        if (port != null && !usedPorts.contains(port)) {
            usedPorts.add(port);
            return port;
        }
        int newPort;
        do {
            newPort = nextPort.getAndIncrement();
        } while (usedPorts.contains(newPort));
        usedPorts.add(newPort);
        return newPort;
    }

    private synchronized void releasePort(int port) {
        usedPorts.remove(port);
        freePorts.offer(port);
        System.out.println("[端口] 已释放端口 " + port + "，可复用");
    }

    public void start() {
        if (running) return;
        try {
            controlServerSocket = new ServerSocket();
            controlServerSocket.setReuseAddress(true);
            controlServerSocket.bind(new InetSocketAddress("::", listenPort));
            running = true;
            System.out.println("[中继] 服务器已启动，端口 " + listenPort + " (IPv4+IPv6，端口复用)");
            workerPool.submit(this::acceptLoop);
        } catch (IOException e) {
            System.err.println("[中继] 启动失败: " + e.getMessage());
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = controlServerSocket.accept();
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                System.out.println("[中继] 新连接来自 " + client.getRemoteSocketAddress());
                workerPool.submit(() -> routeConnection(client));
            } catch (IOException e) {
                if (running) System.err.println("[中继] 接受连接错误: " + e.getMessage());
            }
        }
    }

    private void routeConnection(Socket client) {
        try {
            InputStream rawIn = client.getInputStream();
            StringBuilder sb = new StringBuilder();
            int b;
            while ((b = rawIn.read()) != -1) {
                if (b == '\n') break;
                if (b != '\r') sb.append((char) b);
            }
            String firstLine = sb.toString().trim();
            System.out.println("[中继] 首行: " + firstLine);

            if (firstLine.startsWith("TUNNEL:")) {
                onTunnel(client, firstLine);
            } else if (firstLine.startsWith("REGISTER:")) {
                onRegister(client, rawIn, firstLine);
            } else {
                System.err.println("[中继] 未知命令: " + firstLine);
                client.close();
            }
        } catch (IOException e) {
            System.err.println("[中继] 读取首行失败: " + e.getMessage());
            try { client.close(); } catch (Exception ex) {}
        }
    }

    private void onTunnel(Socket tunnel, String firstLine) {
        try {
            int port = Integer.parseInt(firstLine.substring(7));
            BlockingQueue<Socket> queue = tunnelQueues.get(port);
            if (queue == null) {
                System.err.println("[中继] 隧道端口 " + port + " 未注册，关闭");
                try { tunnel.close(); } catch (Exception ex) {}
                return;
            }
            queue.put(tunnel);
            System.out.println("[中继] 隧道已就绪，端口 " + port + " (队列中: " + queue.size() + ")");
        } catch (NumberFormatException e) {
            System.err.println("[中继] 无效隧道: " + firstLine);
            try { tunnel.close(); } catch (Exception ex) {}
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onRegister(Socket client, InputStream rawIn, String firstLine) {
        BufferedReader reader = null;
        PrintWriter writer = null;
        int assignedPort = -1;

        try {
            reader = new BufferedReader(new InputStreamReader(rawIn, "UTF-8"));
            writer = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), "UTF-8"), true);
            String clientAddr = client.getRemoteSocketAddress().toString();

            String target = firstLine.substring(9);
            assignedPort = allocatePort();
            registeredTargets.put(assignedPort, target);
            controlWriters.put(assignedPort, writer);

            try {
                ServerSocket playerSS = new ServerSocket();
                playerSS.setReuseAddress(true);
                playerSS.bind(new InetSocketAddress("::", assignedPort));
                playerListeners.put(assignedPort, playerSS);
                tunnelQueues.put(assignedPort, new LinkedBlockingQueue<>());

                final int fPort = assignedPort;
                workerPool.submit(() -> acceptPlayers(fPort, playerSS));

                writer.println("REGISTERED:" + assignedPort);
                System.out.println("[中继] 已注册 " + target + " → 公开端口 " + assignedPort);
            } catch (IOException e) {
                writer.println("ERROR:绑定端口失败: " + e.getMessage());
                registeredTargets.remove(assignedPort);
                controlWriters.remove(assignedPort);
                releasePort(assignedPort);
                assignedPort = -1;
            }

            while (running) {
                String line = reader.readLine();
                if (line == null) {
                    System.out.println("[中继] 控制连接断开: " + clientAddr);
                    break;
                }
                line = line.trim();
                if (line.equals("HEARTBEAT")) {
                    writer.println("HEARTBEAT_ACK");
                } else if (line.startsWith("UNREGISTER:")) {
                    int unregPort = Integer.parseInt(line.substring(11));
                    if (unregPort == assignedPort) {
                        System.out.println("[中继] 收到 UNREGISTER, 释放端口 " + unregPort);
                        cleanup(unregPort);
                        releasePort(unregPort);
                        assignedPort = -1;
                        writer.println("UNREGISTERED");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("[中继] 控制通道错误: " + e.getMessage());
        } finally {
            if (assignedPort > 0) {
                cleanup(assignedPort);
                releasePort(assignedPort);
            }
            try { if (reader != null) reader.close(); } catch (Exception e) {}
            try { if (writer != null) writer.close(); } catch (Exception e) {}
            try { client.close(); } catch (Exception e) {}
        }
    }

    private void acceptPlayers(int assignedPort, ServerSocket playerSS) {
        while (running && !playerSS.isClosed()) {
            try {
                Socket player = playerSS.accept();
                player.setTcpNoDelay(true);
                player.setKeepAlive(true);
                System.out.println("[中继] 玩家连入端口 " + assignedPort + " 来自 " + player.getRemoteSocketAddress());

                BlockingQueue<Socket> queue = tunnelQueues.get(assignedPort);
                if (queue == null) { player.close(); continue; }

                Socket tunnel = queue.poll(30, TimeUnit.SECONDS);
                if (tunnel != null && !tunnel.isClosed()) {
                    bridgeAndForget(player, tunnel);
                } else {
                    System.err.println("[中继] 无可用隧道，关闭玩家连接");
                    player.close();
                }
            } catch (IOException e) {
                if (running && !playerSS.isClosed()) {
                    System.err.println("[中继] 玩家接受错误: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void bridgeAndForget(Socket player, Socket tunnel) {
        System.out.println("[中继] 桥接: 玩家 " + player.getRemoteSocketAddress() + " ↔ 隧道");
        workerPool.submit(() -> {
            try {
                InputStream pIn = player.getInputStream();
                OutputStream pOut = player.getOutputStream();
                InputStream tIn = tunnel.getInputStream();
                OutputStream tOut = tunnel.getOutputStream();
                Thread p2t = new Thread(() -> pipe(pIn, tOut), "P→T");
                Thread t2p = new Thread(() -> pipe(tIn, pOut), "T→P");
                p2t.start(); t2p.start();
                p2t.join();
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                t2p.interrupt();
                System.out.println("[中继] 桥接结束: " + player.getRemoteSocketAddress());
            } catch (IOException e) {
                System.err.println("[中继] 桥接IO错误: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try { player.close(); } catch (Exception e) {}
                try { tunnel.close(); } catch (Exception e) {}
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

    private void cleanup(int assignedPort) {
        System.out.println("[中继] 清理端口 " + assignedPort);
        controlWriters.remove(assignedPort);
        registeredTargets.remove(assignedPort);
        BlockingQueue<Socket> queue = tunnelQueues.remove(assignedPort);
        if (queue != null) {
            Socket s;
            while ((s = queue.poll()) != null) { try { s.close(); } catch (Exception e) {} }
        }
        ServerSocket ss = playerListeners.remove(assignedPort);
        if (ss != null) { try { ss.close(); } catch (Exception e) {} }
    }

    public void stop() {
        running = false;
        try { if (controlServerSocket != null) controlServerSocket.close(); } catch (Exception e) {}
        for (int port : new ArrayList<>(playerListeners.keySet())) cleanup(port);
        workerPool.shutdown();
        System.out.println("[中继] 服务器已停止");
    }

    public boolean isRunning() { return running; }

    public static void main(String[] args) {
        int port = 25566;
        if (args.length > 0) {
            try { port = Integer.parseInt(args[0]); } catch (NumberFormatException e) {
                System.err.println("用法: java -jar RelayServer.jar [端口]");
                return;
            }
        }
        RelayServer server = new RelayServer(port);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        try { Thread.currentThread().join(); } catch (InterruptedException e) {}
    }
}
