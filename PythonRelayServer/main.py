import sys
import socket
import threading
import time
from collections import defaultdict
from queue import Queue, Empty
from PyQt6.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, 
                             QHBoxLayout, QPushButton, QLabel, QTextEdit, 
                             QLineEdit, QGroupBox)
from PyQt6.QtCore import Qt, QTimer
from PyQt6.QtGui import QFont, QColor, QTextCharFormat, QTextCursor


class RateLimiter:
    def __init__(self, max_connections=10, time_window=5):
        self.max_connections = max_connections
        self.time_window = time_window
        self.connections = defaultdict(list)
        self.lock = threading.Lock()
    
    def check_and_record(self, ip):
        with self.lock:
            now = time.time()
            # 清理过期记录
            self.connections[ip] = [t for t in self.connections[ip] if now - t < self.time_window]
            # 检查是否超限
            if len(self.connections[ip]) >= self.max_connections:
                return False
            self.connections[ip].append(now)
            return True


class SessionInfo:
    def __init__(self, port, client_addr):
        self.port = port
        self.client_addr = client_addr
        self.control_socket = None
        self.tunnel_queue = Queue()
        self.acceptor = None
        self.unregistered = False  # 标记是否已注销


class TunnelAcceptor(threading.Thread):
    def __init__(self, port, tunnel_queue, log_queue, session_update_callback):
        super().__init__()
        self.port = port
        self.tunnel_queue = tunnel_queue
        self.log_queue = log_queue
        self.session_update_callback = session_update_callback
        self.running = True
        self.server_socket = None
        self.active_connections = 0
        self.max_connections = 50  # 最大同时连接数
        self.rate_limiter = RateLimiter(max_connections=10, time_window=3)

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
                        player_ip = addr[0]
                        
                        # 检查速率限制
                        if not self.rate_limiter.check_and_record(player_ip):
                            self.log_queue.put((f"[RATE_LIMIT] 速率限制触发，拒绝 {player_ip}", "warn"))
                            client_socket.close()
                            continue
                        
                        # 检查最大连接数
                        if self.active_connections >= self.max_connections:
                            self.log_queue.put((f"[MAX_CONN] 连接数超限，拒绝 {player_ip}", "warn"))
                            client_socket.close()
                            continue
                        
                        self.log_queue.put((f"[玩家] 已连接端口 {self.port} 来自 {addr[0]}:{addr[1]}", "info"))
                        
                        # 带超时获取隧道，防止卡死
                        try:
                            tunnel_socket = self.tunnel_queue.get(timeout=5)
                        except Empty:
                            self.log_queue.put((f"[WARN] 等待隧道超时，拒绝 {addr[0]}:{addr[1]}", "warn"))
                            client_socket.close()
                            continue
                        
                        if tunnel_socket and self.running:
                            self.active_connections += 1
                            bridge_thread = threading.Thread(target=self.bridge, args=(client_socket, tunnel_socket))
                            bridge_thread.daemon = True
                            bridge_thread.start()
                            if self.session_update_callback:
                                self.session_update_callback()
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
        def forward(sock_in, sock_out):
            try:
                while True:
                    data = sock_in.recv(8192)
                    if not data:
                        break
                    sock_out.sendall(data)
            except:
                pass
            finally:
                try:
                    sock_in.close()
                except:
                    pass
                try:
                    sock_out.close()
                except:
                    pass
        
        try:
            # 双向转发
            t1 = threading.Thread(target=forward, args=(socket1, socket2))
            t2 = threading.Thread(target=forward, args=(socket2, socket1))
            t1.daemon = True
            t2.daemon = True
            t1.start()
            t2.start()
            t1.join(timeout=30)  # 增加超时，防止卡死
            t2.join(timeout=30)
        finally:
            self.active_connections -= 1  # 减少连接计数
            if self.session_update_callback:
                self.session_update_callback()

    def stop(self):
        self.running = False
        # 向队列发送空值，唤醒阻塞的get()
        try:
            self.tunnel_queue.put(None)
        except:
            pass
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass


class ControlServer(threading.Thread):
    def __init__(self, port, log_queue, session_update_callback):
        super().__init__()
        self.port = port
        self.log_queue = log_queue
        self.session_update_callback = session_update_callback
        self.running = True
        self.server_socket = None
        self.sessions = {}  # port -> SessionInfo
        self.lock = threading.Lock()

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
                        self.handle_new_connection(client_socket, addr)
                    except socket.timeout:
                        continue
                except Exception as e:
                    if self.running:
                        self.log_queue.put((f"[ERROR] 接受连接出错: {e}", "error"))
        except Exception as e:
            self.log_queue.put((f"[ERROR] 启动服务器失败: {e}", "error"))

    def handle_new_connection(self, client_socket, addr):
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
                self.handle_register(client_socket, addr, line)
            elif line.startswith("TUNNEL:"):
                self.handle_tunnel(client_socket, line)
            elif line.startswith("UNREGISTER:"):
                self.handle_unregister(client_socket, line)
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

    def handle_register(self, client_socket, addr, line):
        with self.lock:
            assigned_port = 25567
            while assigned_port in self.sessions:
                assigned_port += 1
            
            session = SessionInfo(assigned_port, addr)
            session.control_socket = client_socket
            self.sessions[assigned_port] = session
            
            self.log_queue.put((f"[REGISTER] 新会话已注册，端口 {assigned_port}，来自 {addr}", "info"))
            
            client_socket.sendall(f"REGISTERED:{assigned_port}\n".encode('utf-8'))
            
            def update_callback():
                if self.session_update_callback:
                    self.session_update_callback()
            
            acceptor = TunnelAcceptor(assigned_port, session.tunnel_queue, self.log_queue, update_callback)
            session.acceptor = acceptor
            acceptor.daemon = True
            acceptor.start()
            
            if self.session_update_callback:
                self.session_update_callback()
            
            # 保持控制连接，处理后续命令
            threading.Thread(target=self.control_loop, args=(client_socket, assigned_port), daemon=True).start()

    def control_loop(self, client_socket, port):
        try:
            reader = client_socket.makefile('r')
            while self.running:
                try:
                    line = reader.readline()
                    if not line:
                        break
                    line = line.strip()
                    if line == "HEARTBEAT":
                        client_socket.sendall(b"HEARTBEAT_ACK\n")
                    elif line.startswith("UNREGISTER:"):
                        unreg_port = int(line.split(":")[1])
                        if unreg_port == port:
                            self.handle_unregister(client_socket, line)
                            break
                except:
                    break
        except Exception as e:
            # 只在不是正常断开时记录警告
            with self.lock:
                if port in self.sessions and not self.sessions[port].unregistered:
                    self.log_queue.put((f"[WARN] 控制连接异常: {e}", "warn"))
        finally:
            # 只有在会话还没被注销时才处理
            with self.lock:
                if port in self.sessions and not self.sessions[port].unregistered:
                    self.handle_unregister(None, f"UNREGISTER:{port}")

    def handle_tunnel(self, client_socket, line):
        port = int(line.split(":")[1])
        with self.lock:
            if port in self.sessions and not self.sessions[port].unregistered:
                self.sessions[port].tunnel_queue.put(client_socket)
                queue_size = self.sessions[port].tunnel_queue.qsize()
                if queue_size == 1:
                    self.log_queue.put((f"[TUNNEL] 就绪，端口 {port}（队列中: {queue_size}）", "info"))
                if self.session_update_callback:
                    self.session_update_callback()
            else:
                client_socket.close()

    def handle_unregister(self, client_socket, line):
        try:
            port = int(line.split(":")[1])
            with self.lock:
                if port in self.sessions and not self.sessions[port].unregistered:
                    session = self.sessions[port]
                    session.unregistered = True  # 标记为已注销
                    if session.acceptor:
                        session.acceptor.stop()
                    if session.control_socket:
                        try:
                            session.control_socket.close()
                        except:
                            pass
                    # 清空隧道队列
                    while not session.tunnel_queue.empty():
                        try:
                            sock = session.tunnel_queue.get_nowait()
                            if sock:
                                sock.close()
                        except:
                            pass
                    del self.sessions[port]
                    self.log_queue.put((f"[UNREGISTER] 端口 {port} 已注销", "info"))
                    if self.session_update_callback:
                        self.session_update_callback()
            if client_socket:
                try:
                    client_socket.sendall(b"UNREGISTERED\n")
                except:
                    pass
        except Exception as e:
            self.log_queue.put((f"[ERROR] 注销失败: {e}", "error"))

    def stop(self):
        self.running = False
        with self.lock:
            for port in list(self.sessions.keys()):
                try:
                    self.handle_unregister(None, f"UNREGISTER:{port}")
                except:
                    pass
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass


class RelayServerWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("🌐 IPv6 中继服务器 v1.2.0 (Python)")
        self.setMinimumSize(900, 600)
        self.server = None
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
        
        main_layout.addLayout(button_layout)
        
        self.status_label = QLabel("⚪ 服务未启动")
        self.status_label.setFont(QFont("Microsoft YaHei", 12, QFont.Weight.Bold))
        self.status_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        main_layout.addWidget(self.status_label)
        
        # 会话统计
        self.session_stats_label = QLabel("已注册会话: 0")
        self.session_stats_label.setFont(QFont("Microsoft YaHei", 10))
        self.session_stats_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        main_layout.addWidget(self.session_stats_label)
        
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

    def update_session_stats(self):
        if self.server:
            count = len(self.server.sessions)
            self.session_stats_label.setText(f"已注册会话: {count}")

    def start_server(self):
        try:
            port = int(self.port_input.text().strip())
            self.server = ControlServer(port, self.log_queue, self.update_session_stats)
            self.server.daemon = True
            self.server.start()
            
            self.start_button.setEnabled(False)
            self.stop_button.setEnabled(True)
            self.port_input.setEnabled(False)
            self.status_label.setText("🟢 服务运行中...")
            self.status_label.setStyleSheet("color: #4CAF50;")
            
        except ValueError:
            self.append_log("端口格式错误，请输入有效的端口号！", "error")

    def stop_server(self):
        if self.server:
            self.server.stop()
            # 等待一小会儿，让线程有时间关闭
            time.sleep(0.5)
            self.server = None
            
        self.start_button.setEnabled(True)
        self.stop_button.setEnabled(False)
        self.port_input.setEnabled(True)
        self.status_label.setText("🔴 服务已停止")
        self.status_label.setStyleSheet("color: #f44336;")
        self.session_stats_label.setText("已注册会话: 0")
        self.append_log("[INFO] 服务器已停止", "info")

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
