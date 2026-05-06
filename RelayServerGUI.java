import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;
import javafx.scene.image.Image;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RelayServerGUI extends Application {

    private TextArea logArea;
    private Label statusLabel;
    private Button startButton;
    private Button stopButton;
    private Button listButton;
    private TextField portField;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private Map<Integer, ClientSession> registeredSessions;
    private AtomicBoolean running = new AtomicBoolean(false);
    
    private int tunnelLogCount = 0;
    private int playerLogCount = 0;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("🌐 IPv6 中继服务器 v1.0.0");
        primaryStage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/icon.png"))));
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setBackground(new Background(new BackgroundFill(Color.rgb(245, 245, 250), CornerRadii.EMPTY, Insets.EMPTY)));
        
        Label titleLabel = new Label("🌐 IPv6 中继服务器");
        titleLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 24));
        titleLabel.setTextFill(Color.rgb(70, 70, 90));
        
        HBox configBox = new HBox(10);
        configBox.setPadding(new Insets(10));
        configBox.setBackground(new Background(new BackgroundFill(Color.WHITE, new CornerRadii(10), Insets.EMPTY)));
        
        configBox.getChildren().addAll(
            new Label("控制端口:"),
            portField = new TextField("25566")
        );
        portField.setPrefWidth(100);
        
        HBox buttonBox = new HBox(10);
        startButton = new Button("🚀 启动服务");
        startButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 20; -fx-cursor: hand; -fx-background-radius: 5;");
        stopButton = new Button("⏹ 停止服务");
        stopButton.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 20; -fx-cursor: hand; -fx-background-radius: 5;");
        listButton = new Button("📋 会话列表");
        listButton.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 10 20; -fx-cursor: hand; -fx-background-radius: 5;");
        
        buttonBox.getChildren().addAll(startButton, stopButton, listButton);
        stopButton.setDisable(true);
        listButton.setDisable(true);
        
        statusLabel = new Label("⚪ 服务未启动");
        statusLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setPrefRowCount(15);
        logArea.setStyle("-fx-font-family: Consolas; -fx-font-size: 12px; -fx-background-color: #1e1e2e; -fx-text-fill: #cdd6f4; -fx-control-inner-background: #1e1e2e;");
        logArea.setText("📝 日志区域 - 等待启动服务...\n");
        
        startButton.setOnAction(e -> startServer());
        stopButton.setOnAction(e -> stopServer());
        listButton.setOnAction(e -> showSessions());
        
        root.getChildren().addAll(titleLabel, configBox, buttonBox, statusLabel, logArea);
        
        Scene scene = new Scene(root, 700, 500);
        primaryStage.setScene(scene);
        primaryStage.show();
        
        log("📝 欢迎使用 IPv6 中继服务器 GUI 版！");
        log("👉 请点击 「启动服务」 按钮开始运行。");
    }
    
    private void startServer() {
        try {
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException e) {
                showError("端口格式错误，请输入有效的端口号！");
                return;
            }
            
            registeredSessions = new ConcurrentHashMap<>();
            executorService = Executors.newCachedThreadPool();
            
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            
            running.set(true);
            
            statusLabel.setText("🟢 服务运行中...");
            statusLabel.setTextFill(Color.rgb(76, 175, 80));
            startButton.setDisable(true);
            stopButton.setDisable(false);
            listButton.setDisable(false);
            portField.setDisable(true);
            
            log("========================================");
            log("     IPv6 中继服务器 v1.0.0");
            log("========================================");
            log("服务器已启动，端口 " + port);
            log("正在监听所有网络接口（IPv4/IPv6）...");
            log("----------------------------------------");
            
            executorService.submit(() -> {
                try {
                    while (running.get()) {
                        Socket clientSocket = serverSocket.accept();
                        handleNewConnection(clientSocket);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                    }
                }
            });
            
        } catch (IOException e) {
            showError("启动服务失败: " + e.getMessage());
            log("❌ 启动服务失败: " + e.getMessage());
        }
    }
    
    private void stopServer() {
        running.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        
        statusLabel.setText("🔴 服务已停止");
        statusLabel.setTextFill(Color.rgb(244, 67, 54));
        startButton.setDisable(false);
        stopButton.setDisable(true);
        listButton.setDisable(true);
        portField.setDisable(false);
        
        log("[INFO] Server stopped");
        log("✅ 服务已停止！");
    }
    
    private void showSessions() {
        StringBuilder sb = new StringBuilder("\n==== 已注册会话列表 ====\n");
        if (registeredSessions.isEmpty()) {
            sb.append("没有已注册的会话\n");
        } else {
            for (Map.Entry<Integer, ClientSession> entry : registeredSessions.entrySet()) {
                sb.append("- 端口 ").append(entry.getKey())
                  .append(" | 隧道数: ").append(entry.getValue().getTunnelQueueSize())
                  .append("\n");
            }
        }
        sb.append("==========================");
        log(sb.toString());
    }
    
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void log(String message) {
        Platform.runLater(() -> {
            logArea.appendText(message + "\n");
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }
    
    private void handleNewConnection(Socket socket) {
        executorService.submit(() -> {
            try {
                InputStream in = socket.getInputStream();
                ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
                int b;
                while ((b = in.read()) != -1 && b != '\n') {
                    if (b != '\r') {
                        lineBuffer.write(b);
                    }
                }
                String line = lineBuffer.toString("UTF-8");
                
                if (line.startsWith("REGISTER:")) {
                    String[] parts = line.split(":");
                    int localPort = Integer.parseInt(parts[2]);
                    
                    int assignedPort = 25567;
                    while (registeredSessions.containsKey(assignedPort)) {
                        assignedPort++;
                    }
                    
                    ClientSession session = new ClientSession(assignedPort, socket);
                    registeredSessions.put(assignedPort, session);
                    
                    log("[REGISTER] 新会话已注册，端口 " + assignedPort);
                    
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                    writer.println("REGISTERED:" + assignedPort);
                    
                    session.startPlayerListener();
                    
                } else if (line.startsWith("TUNNEL:")) {
                    int port = Integer.parseInt(line.substring(7));
                    ClientSession session = registeredSessions.get(port);
                    if (session != null) {
                        session.addTunnel(socket);
                        
                        tunnelLogCount++;
                        if (session.getTunnelQueueSize() == 1 || tunnelLogCount % 10 == 0) {
                            log("[TUNNEL] 就绪，端口 " + port + " (队列中: " + session.getTunnelQueueSize() + ")");
                        }
                    } else {
                        socket.close();
                    }
                } else if (line.startsWith("UNREGISTER:")) {
                    int port = Integer.parseInt(line.substring(11));
                    registeredSessions.remove(port);
                    log("[UNREGISTER] 端口 " + port + " 已注销");
                    socket.close();
                } else if (line.equals("HEARTBEAT")) {
                    PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
                    writer.println("HEARTBEAT_ACK");
                    socket.close();
                } else {
                    socket.close();
                }
            } catch (Exception e) {
                try { socket.close(); } catch (Exception ex) {}
            }
        });
    }
    
    private class ClientSession {
        private int port;
        private Socket controllerSocket;
        private Queue<Socket> tunnelQueue = new ConcurrentLinkedQueue<>();
        private ServerSocket playerServerSocket;
        private List<RelayPlayerConnection> playerHandlers = new ArrayList<>();
        
        public ClientSession(int port, Socket controllerSocket) {
            this.port = port;
            this.controllerSocket = controllerSocket;
        }
        
        public int getTunnelQueueSize() {
            return tunnelQueue.size();
        }
        
        public void addTunnel(Socket tunnelSocket) {
            tunnelQueue.offer(tunnelSocket);
        }
        
        public void startPlayerListener() {
            executorService.submit(() -> {
                try {
                    playerServerSocket = new ServerSocket();
                    playerServerSocket.setReuseAddress(true);
                    playerServerSocket.bind(new InetSocketAddress(port));
                    
                    log("[LISTEN] 玩家连接监听已启动，端口 " + port + " (支持 IPv4/IPv6)");
                    
                    while (running.get() && !playerServerSocket.isClosed()) {
                        try {
                            Socket playerSocket = playerServerSocket.accept();
                            InetAddress playerAddr = playerSocket.getInetAddress();
                            String playerStr = playerAddr.getHostAddress() + ":" + playerSocket.getPort();
                            
                            Socket tunnelSocket = tunnelQueue.poll();
                            
                            if (tunnelSocket != null) {
                                playerLogCount++;
                                log("[玩家] 已连接端口 " + port + " 来自 " + playerStr);
                                
                                RelayPlayerConnection handler = new RelayPlayerConnection(playerSocket, tunnelSocket, this, playerStr);
                                playerHandlers.add(handler);
                                executorService.submit(handler);
                            } else {
                                log("[WARN] 没有可用隧道，拒绝 " + playerStr);
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
                log("[玩家] 会话结束 (共 " + playerLogCount + " 次连接)");
            }
        }
    }
    
    private class RelayPlayerConnection implements Runnable {
        private Socket playerSocket;
        private Socket tunnelSocket;
        private ClientSession session;
        private String playerId;
        
        public RelayPlayerConnection(Socket playerSocket, Socket tunnelSocket, ClientSession session, String playerId) {
            this.playerSocket = playerSocket;
            this.tunnelSocket = tunnelSocket;
            this.session = session;
            this.playerId = playerId;
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
    
    @Override
    public void stop() {
        stopServer();
    }
}
