import sys
import socket
import threading
from queue import Queue
from PyQt6.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, 
                             QHBoxLayout, QPushButton, QLabel, QTextEdit, 
                             QLineEdit, QGroupBox)
from PyQt6.QtCore import Qt, QTimer
from PyQt6.QtGui import QFont, QColor, QTextCharFormat, QTextCursor

class TunnelAcceptor(threading.Thread):
    def __init__(self, port, tunnel_queue, log_queue):
        super().__init__()
        self.port = port
        self.tunnel_queue = tunnel_queue
        self.log_queue = log_queue
        self.running = True
        self.server_socket = None

    def run(self):
        try:
            self.server_socket = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind(('::', self.port))
            self.server_socket.listen(10)
            self.log_queue.put((f"[LISTEN] 玩家连接监听已启动，端口 {self.port}（支持 IPv4/IPv6）", "info"))
            
            while self.running:
                try:
                    self.server_socket.settimeout(1.0)
                    try:
                        client_socket, addr = self.server_socket.accept()
                        self.log_queue.put((f"[玩家] 已连接端口 {self.port} 来自 {addr[0]}:{addr[1]}", "info"))
                        
                        tunnel_socket = self.tunnel_queue.get()
                        
                        if tunnel_socket:
                            bridge1 = threading.Thread(target=self.bridge, args=(client_socket, tunnel_socket))
                            bridge2 = threading.Thread(target=self.bridge, args=(tunnel_socket, client_socket))
                            bridge1.daemon = True
                            bridge2.daemon = True
                            bridge1.start()
                            bridge2.start()
                        else:
                            self.log_queue.put((f"[WARN] 没有可用隧道，拒绝 {addr[0]}:{addr[1]}", "warn"))
                            client_socket.close()
                            
                    except socket.timeout:
                        continue
                except Exception as e:
                    if self.running:
                        self.log_queue.put((f"[ERROR] 接受玩家连接出错: {e}", "error"))
        except Exception as e:
            self.log_queue.put((f"[ERROR] 启动玩家监听失败: {e}", "error"))

    def bridge(self, socket1, socket2):
        try:
            while True:
                data = socket1.recv(8192)
                if not data:
                    break
                socket2.sendall(data)
        except:
            pass
        finally:
            try:
                socket1.close()
            except:
                pass
            try:
                socket2.close()
            except:
                pass

    def stop(self):
        self.running = False
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass

class ControlServer(threading.Thread):
    def __init__(self, port, log_queue):
        super().__init__()
        self.port = port
        self.log_queue = log_queue
        self.running = True
        self.server_socket = None
        self.tunnel_queues = {}
        self.acceptors = {}

    def run(self):
        try:
            self.server_socket = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.bind(('::', self.port))
            self.server_socket.listen(10)
            self.log_queue.put((f"服务器已启动，端口 {self.port}", "info"))
            self.log_queue.put(("正在监听所有网络接口（IPv4/IPv6）...", "info"))
            
            while self.running:
                try:
                    self.server_socket.settimeout(1.0)
                    try:
                        client_socket, addr = self.server_socket.accept()
                        self.handle_new_connection(client_socket)
                    except socket.timeout:
                        continue
                except Exception as e:
                    if self.running:
                        self.log_queue.put((f"[ERROR] 接受连接出错: {e}", "error"))
        except Exception as e:
            self.log_queue.put((f"[ERROR] 启动服务器失败: {e}", "error"))

    def handle_new_connection(self, client_socket):
        try:
            line_buffer = bytearray()
            while True:
                b = client_socket.recv(1)
                if not b:
                    client_socket.close()
                    return
                if b == b'\n':
                    break
                if b != b'\r':
                    line_buffer.extend(b)
            line = line_buffer.decode('utf-8')
            
            if line.startswith("REGISTER:"):
                parts = line.split(":")
                if len(parts) >= 3:
                    assigned_port = 25567
                    while assigned_port in self.tunnel_queues:
                        assigned_port += 1
                    
                    self.tunnel_queues[assigned_port] = Queue()
                    self.log_queue.put((f"[REGISTER] 新会话已注册，端口 {assigned_port}", "info"))
                    
                    client_socket.sendall(f"REGISTERED:{assigned_port}\n".encode('utf-8'))
                    
                    acceptor = TunnelAcceptor(assigned_port, self.tunnel_queues[assigned_port], self.log_queue)
                    self.acceptors[assigned_port] = acceptor
                    acceptor.daemon = True
                    acceptor.start()
                    
            elif line.startswith("TUNNEL:"):
                port = int(line.split(":")[1])
                if port in self.tunnel_queues:
                    self.tunnel_queues[port].put(client_socket)
                    queue_size = self.tunnel_queues[port].qsize()
                    if queue_size == 1:
                        self.log_queue.put((f"[TUNNEL] 就绪，端口 {port}（队列中: {queue_size}）", "info"))
                else:
                    client_socket.close()
                    
            elif line.startswith("UNREGISTER:"):
                port = int(line.split(":")[1])
                if port in self.acceptors:
                    self.acceptors[port].stop()
                    del self.acceptors[port]
                if port in self.tunnel_queues:
                    del self.tunnel_queues[port]
                self.log_queue.put((f"[UNREGISTER] 端口 {port} 已注销", "info"))
                client_socket.close()
                
            elif line == "HEARTBEAT":
                client_socket.sendall(b"HEARTBEAT_ACK\n")
                client_socket.close()
                
            else:
                client_socket.close()
                
        except Exception as e:
            try:
                client_socket.close()
            except:
                pass

    def stop(self):
        self.running = False
        for port in list(self.acceptors.keys()):
            self.acceptors[port].stop()
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass

class RelayServerWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("🌐 IPv6 中继服务器 v1.0.0 (Python)")
        self.setMinimumSize(800, 600)
        self.server_thread = None
        self.log_queue = Queue()
        self.init_ui()
        self.timer = QTimer()
        self.timer.timeout.connect(self.process_log_queue)
        self.timer.start(100)

    def init_ui(self):
        central_widget = QWidget()
        self.setCentralWidget(central_widget)
        
        main_layout = QVBoxLayout(central_widget)
        main_layout.setContentsMargins(20, 20, 20, 20)
        main_layout.setSpacing(15)
        
        title_label = QLabel("🌐 IPv6 中继服务器")
        title_label.setFont(QFont("Microsoft YaHei", 20, QFont.Weight.Bold))
        title_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        main_layout.addWidget(title_label)
        
        config_group = QGroupBox("配置")
        config_layout = QHBoxLayout()
        
        config_layout.addWidget(QLabel("控制端口:"))
        self.port_input = QLineEdit("25566")
        self.port_input.setFont(QFont("Consolas", 12))
        self.port_input.setMaximumWidth(120)
        config_layout.addWidget(self.port_input)
        
        config_group.setLayout(config_layout)
        main_layout.addWidget(config_group)
        
        button_layout = QHBoxLayout()
        
        self.start_button = QPushButton("🚀 启动服务")
        self.start_button.setStyleSheet("""
            QPushButton {
                background-color: #4CAF50;
                color: white;
                font-size: 14px;
                padding: 10px 30px;
                border-radius: 5px;
            }
            QPushButton:hover {
                background-color: #45a049;
            }
            QPushButton:pressed {
                background-color: #3d8b40;
            }
            QPushButton:disabled {
                background-color: #cccccc;
            }
        """)
        self.start_button.clicked.connect(self.start_server)
        button_layout.addWidget(self.start_button)
        
        self.stop_button = QPushButton("⏹ 停止服务")
        self.stop_button.setStyleSheet("""
            QPushButton {
                background-color: #f44336;
                color: white;
                font-size: 14px;
                padding: 10px 30px;
                border-radius: 5px;
            }
            QPushButton:hover {
                background-color: #da190b;
            }
            QPushButton:pressed {
                background-color: #b71c1c;
            }
            QPushButton:disabled {
                background-color: #cccccc;
            }
        """)
        self.stop_button.clicked.connect(self.stop_server)
        self.stop_button.setEnabled(False)
        button_layout.addWidget(self.stop_button)
        
        self.list_button = QPushButton("📋 会话列表")
        self.list_button.setStyleSheet("""
            QPushButton {
                background-color: #2196F3;
                color: white;
                font-size: 14px;
                padding: 10px 30px;
                border-radius: 5px;
            }
            QPushButton:hover {
                background-color: #0b7dda;
            }
            QPushButton:pressed {
                background-color: #0a6dc0;
            }
            QPushButton:disabled {
                background-color: #cccccc;
            }
        """)
        self.list_button.clicked.connect(self.show_sessions)
        self.list_button.setEnabled(False)
        button_layout.addWidget(self.list_button)
        
        main_layout.addLayout(button_layout)
        
        self.status_label = QLabel("⚪ 服务未启动")
        self.status_label.setFont(QFont("Microsoft YaHei", 12, QFont.Weight.Bold))
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        main_layout.addWidget(self.status_label)
        
        log_group = QGroupBox("日志")
        log_layout = QVBoxLayout()
        
        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setFont(QFont("Consolas", 11))
        self.log_text.setStyleSheet("""
            QTextEdit {
                background-color: #1e1e2e;
                color: #cdd6f4;
                border: 1px solid #45475a;
                border-radius: 5px;
                padding: 10px;
            }
        """)
        log_layout.addWidget(self.log_text)
        
        log_group.setLayout(log_layout)
        main_layout.addWidget(log_group)

    def process_log_queue(self):
        while not self.log_queue.empty():
            message, level = self.log_queue.get()
            self.append_log(message, level)

    def start_server(self):
        try:
            port = int(self.port_input.text().strip())
            self.server_thread = ControlServer(port, self.log_queue)
            self.server_thread.daemon = True
            self.server_thread.start()
            
            self.start_button.setEnabled(False)
            self.stop_button.setEnabled(True)
            self.list_button.setEnabled(True)
            self.port_input.setEnabled(False)
            self.status_label.setText("🟢 服务运行中...")
            self.status_label.setStyleSheet("color: #4CAF50;")
            
        except ValueError:
            self.append_log("端口格式错误，请输入有效的端口号！", "error")

    def stop_server(self):
        if self.server_thread:
            self.server_thread.stop()
            self.server_thread = None
            
        self.start_button.setEnabled(True)
        self.stop_button.setEnabled(False)
        self.list_button.setEnabled(False)
        self.port_input.setEnabled(True)
        self.status_label.setText("🔴 服务已停止")
        self.status_label.setStyleSheet("color: #f44336;")
        self.append_log("[INFO] 服务器已停止", "info")

    def show_sessions(self):
        if self.server_thread:
            queues = self.server_thread.tunnel_queues
            if not queues:
                self.append_log("\n==== 已注册会话列表 ====\n没有已注册的会话\n", "info")
            else:
                msg = "\n==== 已注册会话列表 ====\n"
                for port in sorted(queues.keys()):
                    msg += f"- 端口 {port} | 隧道数: {queues[port].qsize()}\n"
                msg += "========================\n"
                self.append_log(msg, "info")

    def append_log(self, message, level="info"):
        cursor = self.log_text.textCursor()
        cursor.movePosition(QTextCursor.MoveOperation.End)
        
        format = QTextCharFormat()
        if level == "error":
            format.setForeground(QColor(240, 100, 100))
        elif level == "warn":
            format.setForeground(QColor(250, 180, 70))
        else:
            format.setForeground(QColor(205, 214, 244))
        
        cursor.setCharFormat(format)
        cursor.insertText(message + "\n")
        
        self.log_text.setTextCursor(cursor)
        self.log_text.ensureCursorVisible()

def main():
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    
    window = RelayServerWindow()
    window.show()
    
    sys.exit(app.exec())

if __name__ == "__main__":
    main()
