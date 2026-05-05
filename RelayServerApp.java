import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class RelayServerApp {
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private Map<Integer, ClientSession> registeredSessions;
    private boolean running;
    private static final int PORT = 25566;
    
    private int tunnelLogCount = 0;
    private int playerLogCount = 0;

    public RelayServerApp() {
        this.registeredSessions = new ConcurrentHashMap<>();
        this.executorService = Executors.newCachedThreadPool();
    }

    public void start() {
        running = true;
        executorService.submit(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(PORT));
                
                System.out.println("========================================");
                System.out.println("     IPv6 中继服务器 v1.0.0");
                System.out.println("========================================");
                System.out.println("服务器已启动，端口 " + PORT);
                System.out.println("正在监听所有网络接口（IPv4/IPv6）...");
                System.out.println("----------------------------------------");
                
                while (running) {
                    Socket clientSocket = serverSocket.accept();
                    // 先读取第一行，判断是什么连接
                    handleNewConnection(clientSocket);
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("[ERROR] Server error: " + e.getMessage());
                }
            }
        });
    }

    private void handleNewConnection(Socket socket) {
        executorService.submit(() -> {
            try {
                // 读取第一行，不使用 BufferedReader（避免缓冲问题）
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1 && b != '\n') {
                    if (b != '\r') {
                        lineBuffer.write(b);
                    }
                }
                String line = lineBuffer.toString("UTF-8");
                
                if (line.startsWith("REGISTER:") || line.startsWith("UNREGISTER:") || line.equals("HEARTBEAT")) {
                    // 这是控制连接
                    RelayClient client = new RelayClient(socket, this, line);
                    executorService.submit(client);
                } else if (line.startsWith("TUNNEL:")) {
                    // 这是隧道连接
                    int port = Integer.parseInt(line.substring(7));
                    ClientSession session = registeredSessions.get(port);
                    if (session != null) {
                        session.addTunnel(socket);
                        tunnelLogCount++;
                        if (session.getTunnelQueueSize() == 1 || tunnelLogCount % 10 == 0) {
                            System.out.println("[TUNNEL] 就绪，端口 " + port + " (队列中: " + session.getTunnelQueueSize() + ")");
                        }
                    } else {
                        socket.close();
                    }
                } else {
                    socket.close();
                }
            } catch (Exception e) {
                try { socket.close(); } catch (Exception ex) {}
            }
        });
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("[ERROR] Error stopping server: " + e.getMessage());
        }
        executorService.shutdown();
        System.out.println("[INFO] Server stopped");
    }

    public void registerSession(int port, ClientSession session) {
        registeredSessions.put(port, session);
        System.out.println("[REGISTER] New session registered on port " + port);
    }

    public void unregisterSession(int port) {
        registeredSessions.remove(port);
        System.out.println("[UNREGISTER] Session on port " + port + " unregistered");
    }

    public ClientSession getSession(int port) {
        return registeredSessions.get(port);
    }

    private class RelayClient implements Runnable {
        private Socket socket;
        private RelayServerApp server;
        private BufferedReader reader;
        private PrintWriter writer;
        private String clientId;
        private String firstLine;

        public RelayClient(Socket socket, RelayServerApp server, String firstLine) {
            this.socket = socket;
            this.server = server;
            this.firstLine = firstLine;
            InetAddress clientAddr = socket.getInetAddress();
            this.clientId = clientAddr.getHostAddress() + ":" + socket.getPort();
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                
                // 处理已经读取的第一行
                handleLine(firstLine);
                
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line);
                }
            } catch (IOException e) {
            } finally {
                close();
            }
        }

        private void handleLine(String line) {
            if (line.startsWith("REGISTER:")) {
                String[] parts = line.split(":");
                int localPort = Integer.parseInt(parts[2]);
                
                // 不管本地端口是什么，都从 25567 开始分配公开端口
                int assignedPort = 25567;
                while (server.getSession(assignedPort) != null) {
                    assignedPort++;
                }
                
                ClientSession session = new ClientSession(assignedPort, this);
                server.registerSession(assignedPort, session);
                
                sendMessage("REGISTERED:" + assignedPort);
                
            } else if (line.startsWith("UNREGISTER:")) {
                int port = Integer.parseInt(line.substring(11));
                server.unregisterSession(port);
                
            } else if (line.equals("HEARTBEAT")) {
                sendMessage("HEARTBEAT_ACK");
            }
        }

        public void sendMessage(String message) {
            try {
                if (writer != null) {
                    writer.write(message + "\n");
                    writer.flush();
                }
            } catch (Exception e) {
            }
        }

        public String getClientId() {
            return clientId;
        }

        private void close() {
            try {
                if (reader != null) reader.close();
                if (writer != null) writer.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
            }
        }
    }

    private class ClientSession {
        private int port;
        private RelayClient controller;
        private Queue<Socket> tunnelQueue;
        private ServerSocket playerServerSocket;
        private List<RelayPlayerConnection> playerHandlers;

        public ClientSession(int port, RelayClient controller) {
            this.port = port;
            this.controller = controller;
            this.tunnelQueue = new ConcurrentLinkedQueue<>();
            this.playerHandlers = new ArrayList<>();
            startPlayerListener();
        }

        public int getTunnelQueueSize() {
            return tunnelQueue.size();
        }

        public void addTunnel(Socket tunnelSocket) {
            tunnelQueue.offer(tunnelSocket);
        }

        private void startPlayerListener() {
            executorService.submit(() -> {
                try {
                    playerServerSocket = new ServerSocket();
                    playerServerSocket.setReuseAddress(true);
                    playerServerSocket.bind(new InetSocketAddress(port));
                    
                    System.out.println("[LISTEN] 玩家连接监听已启动，端口 " + port + " (支持 IPv4/IPv6)");
                    
                    while (running && !playerServerSocket.isClosed()) {
                        try {
                            Socket playerSocket = playerServerSocket.accept();
                            InetAddress playerAddr = playerSocket.getInetAddress();
                            String playerStr = playerAddr.getHostAddress() + ":" + playerSocket.getPort();
                            
                            Socket tunnelSocket = tunnelQueue.poll();
                            
                            if (tunnelSocket != null) {
                                playerLogCount++;
                                System.out.println("[玩家] 已连接端口 " + port + " 来自 " + playerStr);
                                
                                RelayPlayerConnection handler = new RelayPlayerConnection(playerSocket, tunnelSocket, this, playerStr);
                                playerHandlers.add(handler);
                                executorService.submit(handler);
                            } else {
                                System.out.println("[WARN] 没有可用隧道，拒绝 " + playerStr);
                                playerSocket.close();
                            }
                        } catch (SocketException e) {
                        }
                    }
                } catch (IOException e) {
                }
            });
        }

        public void removePlayerHandler(RelayPlayerConnection handler) {
            playerHandlers.remove(handler);
            playerLogCount++;
            if (playerLogCount % 10 == 0) {
                System.out.println("[玩家] 会话结束 (共 " + playerLogCount + " 次连接)");
            }
        }

        public void close() {
            try {
                if (playerServerSocket != null) {
                    playerServerSocket.close();
                }
            } catch (IOException e) {
            }
        }
    }

    private class RelayPlayerConnection implements Runnable {
        private Socket playerSocket;
        private Socket tunnelSocket;
        private ClientSession session;
        private String playerId;
        private long connectTime;

        public RelayPlayerConnection(Socket playerSocket, Socket tunnelSocket, ClientSession session, String playerId) {
            this.playerSocket = playerSocket;
            this.tunnelSocket = tunnelSocket;
            this.session = session;
            this.playerId = playerId;
            this.connectTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                InputStream playerIn = playerSocket.getInputStream();
                OutputStream playerOut = playerSocket.getOutputStream();
                InputStream tunnelIn = tunnelSocket.getInputStream();
                OutputStream tunnelOut = tunnelSocket.getOutputStream();

                Thread t1 = new Thread(() -> pipe(playerIn, tunnelOut));
                Thread t2 = new Thread(() -> pipe(tunnelIn, playerOut));
                t1.start();
                t2.start();

                t1.join();
                t2.interrupt();
            } catch (Exception e) {
            } finally {
                close();
                session.removePlayerHandler(this);
            }
        }

        private void pipe(InputStream in, OutputStream out) {
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
            }
        }

        private void close() {
            try { if (playerSocket != null) playerSocket.close(); } catch (Exception e) {}
            try { if (tunnelSocket != null) tunnelSocket.close(); } catch (Exception e) {}
        }
    }

    public static void main(String[] args) {
        RelayServerApp server = new RelayServerApp();
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
        }));

        Scanner scanner = new Scanner(System.in);
        System.out.println("\nType 'quit' to stop the server");
        System.out.println("Type 'list' to see registered sessions");
        
        while (true) {
            String input = scanner.nextLine();
            if (input.equalsIgnoreCase("quit")) {
                server.stop();
                break;
            } else if (input.equalsIgnoreCase("list")) {
                System.out.println("\n==== Registered Sessions ====");
                if (server.registeredSessions.isEmpty()) {
                    System.out.println("No sessions registered");
                } else {
                    for (Map.Entry<Integer, ClientSession> entry : server.registeredSessions.entrySet()) {
                        System.out.println("- Port " + entry.getKey() + 
                            ", Tunnels: " + entry.getValue().getTunnelQueueSize());
                    }
                }
                System.out.println("============================");
            } else {
                System.out.println("[INFO] Unknown command. Available: quit, list");
            }
        }
        scanner.close();
        System.exit(0);
    }
}
