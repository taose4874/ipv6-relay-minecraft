import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RelayServerSwing {

    private JFrame frame;
    private JTextArea logArea;
    private JLabel statusLabel;
    private JButton startButton;
    private JButton stopButton;
    private JButton listButton;
    private JTextField portField;
    
    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private Map<Integer, ClientSession> registeredSessions;
    private AtomicBoolean running = new AtomicBoolean(false);
    
    private int tunnelLogCount = 0;
    private int playerLogCount = 0;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
        }
        SwingUtilities.invokeLater(() -> new RelayServerSwing().createAndShowGUI());
    }

    private void createAndShowGUI() {
        frame = new JFrame("🌐 IPv6 中继服务器 v1.0.0");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(750, 550);
        frame.setLocationRelativeTo(null);
        
        JPanel mainPanel = new JPanel(new BorderLayout(15, 15));
        mainPanel.setBorder(new EmptyBorder(20, 20, 20, 20));
        mainPanel.setBackground(new Color(245, 245, 250));
        
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setOpaque(false);
        
        JLabel titleLabel = new JLabel("🌐 IPv6 中继服务器", JLabel.CENTER);
        titleLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 24));
        titleLabel.setForeground(new Color(70, 70, 90));
        topPanel.add(titleLabel, BorderLayout.NORTH);
        
        JPanel configPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        configPanel.setBackground(Color.WHITE);
        configPanel.setBorder(BorderFactory.createLineBorder(new Color(220, 220, 230), 1, true));
        configPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(220, 220, 230), 1, true),
            new EmptyBorder(10, 15, 10, 15)
        ));
        
        configPanel.add(new JLabel("控制端口:"));
        portField = new JTextField("25566", 8);
        portField.setFont(new Font("Consolas", Font.PLAIN, 14));
        configPanel.add(portField);
        topPanel.add(configPanel, BorderLayout.CENTER);
        
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        buttonPanel.setOpaque(false);
        
        startButton = new JButton("🚀 启动服务");
        startButton.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        startButton.setBackground(new Color(76, 175, 80));
        startButton.setForeground(Color.WHITE);
        startButton.setFocusPainted(false);
        startButton.setBorderPainted(false);
        startButton.setOpaque(true);
        startButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        stopButton = new JButton("⏹ 停止服务");
        stopButton.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        stopButton.setBackground(new Color(244, 67, 54));
        stopButton.setForeground(Color.WHITE);
        stopButton.setFocusPainted(false);
        stopButton.setBorderPainted(false);
        stopButton.setOpaque(true);
        stopButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        listButton = new JButton("📋 会话列表");
        listButton.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
        listButton.setBackground(new Color(33, 150, 243));
        listButton.setForeground(Color.WHITE);
        listButton.setFocusPainted(false);
        listButton.setBorderPainted(false);
        listButton.setOpaque(true);
        listButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        buttonPanel.add(startButton);
        buttonPanel.add(stopButton);
        buttonPanel.add(listButton);
        
        stopButton.setEnabled(false);
        listButton.setEnabled(false);
        
        topPanel.add(buttonPanel, BorderLayout.SOUTH);
        
        statusLabel = new JLabel("⚪ 服务未启动", JLabel.CENTER);
        statusLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0));
        
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        logArea.setBackground(new Color(30, 30, 46));
        logArea.setForeground(new Color(205, 214, 244));
        logArea.setBorder(new EmptyBorder(10, 10, 10, 10));
        JScrollPane logScrollPane = new JScrollPane(logArea);
        logScrollPane.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 220), 1, true));
        
        startButton.addActionListener(e -> startServer());
        stopButton.addActionListener(e -> stopServer());
        listButton.addActionListener(e -> showSessions());
        
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(statusLabel, BorderLayout.CENTER);
        mainPanel.add(logScrollPane, BorderLayout.SOUTH);
        
        frame.add(mainPanel);
        frame.setVisible(true);
        
        log("📝 欢迎使用 IPv6 中继服务器 GUI 版！");
        log("👉 请点击 「启动服务」 按钮开始运行。");
    }
    
    private void startServer() {
        try {
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(frame, "端口格式错误，请输入有效的端口号！", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            registeredSessions = new ConcurrentHashMap<>();
            executorService = Executors.newCachedThreadPool();
            
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            
            running.set(true);
            
            statusLabel.setText("🟢 服务运行中...");
            statusLabel.setForeground(new Color(76, 175, 80));
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            listButton.setEnabled(true);
            portField.setEnabled(false);
            
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
            JOptionPane.showMessageDialog(frame, "启动服务失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
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
        statusLabel.setForeground(new Color(244, 67, 54));
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        listButton.setEnabled(false);
        portField.setEnabled(true);
        
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
    
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
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
        private java.util.List<RelayPlayerConnection> playerHandlers = new ArrayList<>();
        
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
}
