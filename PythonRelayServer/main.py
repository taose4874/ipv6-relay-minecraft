import sys
import socket
import threading
from queue import Queue
from PyQt6.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, 
                             QHBoxLayout, QPushButton, QLabel, QTextEdit, 
                             QLineEdit, QGroupBox, QTableWidget, QTableWidgetItem, 
                             QHeaderView, QDialog, QMessageBox)
from PyQt6.QtCore import Qt, QTimer
from PyQt6.QtGui import QFont, QColor, QTextCharFormat, QTextCursor


class SessionInfo:
    def __init__(self, port, client_addr):
        self.port = port
        self.client_addr = client_addr
        self.control_socket = None
        self.tunnel_queue = Queue()
        self.acceptor = None
        self.active_bridges = []
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
        self.bridges = []

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
                        
                        if tunnel_socket and self.running:
                            bridge1 = threading.Thread(target=self.bridge, args=(client_socket, tunnel_socket, addr))
                            bridge1.daemon = True
                            bridge1.start()
                            self.bridges.append((bridge1, client_socket, tunnel_socket, addr))
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

    def bridge(self, socket1, socket2, player_addr):
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
            try:
                self.bridges.remove((threading.current_thread(), socket1, socket2, player_addr))
            except:
                pass
            if self.session_update_callback:
                self.session_update_callback()

    def get_active_players(self):
        return [addr for (_, _, _, addr) in self.bridges if threading.current_thread().is_alive() or len(self.bridges) > 0]

    def stop(self):
        self.running = False
        for (_, s1, s2, _) in self.bridges:
            try:
                s1.close()
            except:
                pass
            try:
                s2.close()
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

    def kick_session(self, port):
        with self.lock:
            if port in self.sessions and not self.sessions[port].unregistered:
                self.log_queue.put((f"[KICK] 正在踢出端口 {port} 的会话", "info"))
                self.handle_unregister(None, f"UNREGISTER:{port}")
                return True
            return False

    def stop(self):
        self.running = False
        with self.lock:
            for port in list(self.sessions.keys()):
                self.handle_unregister(None, f"UNREGISTER:{port}")
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass


class SessionListDialog(QDialog):
    def __init__(self, server, log_queue, parent=None):
        super().__init__(parent)
        self.server = server
        self.log_queue = log_queue
        self.setWindowTitle("会话列表")
        self.setMinimumSize(800, 500)
        self.init_ui()

    def init_ui(self):
        layout = QVBoxLayout()
        
        # 表格
        self.table = QTableWidget()
        self.table.setColumnCount(4)
        self.table.setHorizontalHeaderLabels(["端口", "客户端地址", "隧道数量", "操作"])
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        layout.addWidget(self.table)
        
        # 按钮
        button_layout = QHBoxLayout()
        refresh_btn = QPushButton("刷新")
        refresh_btn.clicked.connect(self.refresh)
        button_layout.addWidget(refresh_btn)
        
        close_btn = QPushButton("关闭")
        close_btn.clicked.connect(self.accept)
        button_layout.addWidget(close_btn)
        
        layout.addLayout(button_layout)
        self.setLayout(layout)
        
        self.refresh()

    def refresh(self):
        self.table.setRowCount(0)
        if not self.server or not self.server.sessions:
            return
        
        for port, session in self.server.sessions.items():
            row = self.table.rowCount()
            self.table.insertRow(row)
            
            # 端口
            self.table.setItem(row, 0, QTableWidgetItem(str(port)))
            
            # 客户端地址
            addr_str = f"{session.client_addr[0]}:{session.client_addr[1]}" if session.client_addr else "未知"
            self.table.setItem(row, 1, QTableWidgetItem(addr_str))
            
            # 隧道数量
            tunnel_count = session.tunnel_queue.qsize()
            self.table.setItem(row, 2, QTableWidgetItem(str(tunnel_count)))
            
            # 操作按钮
            kick_btn = QPushButton("踢出")
            kick_btn.clicked.connect(lambda _, p=port: self.kick_session(p))
            self.table.setCellWidget(row, 3, kick_btn)

    def kick_session(self, port):
        try:
            reply = QMessageBox.question(self, "确认踢出", f"确定要踢出端口 {port} 的会话吗？", 
                                       QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
            if reply == QMessageBox.StandardButton.Yes:
                if self.server.kick_session(port):
                    self.log_queue.put((f"[KICK] 已踢出端口 {port} 的会话", "info"))
                    QMessageBox.information(self, "成功", f"已踢出端口 {port} 的会话")
                    self.refresh()
                else:
                    QMessageBox.warning(self, "失败", "踢出失败，会话可能已不存在")
        except Exception as e:
            QMessageBox.critical(self, "错误", f"踢出会话时出错: {e}")


class RelayServerWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("🌐 IPv6 中继服务器 v1.1.1 (Python)")
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
            self.list_button.setEnabled(True)
            self.port_input.setEnabled(False)
            self.status_label.setText("🟢 服务运行中...")
            self.status_label.setStyleSheet("color: #4CAF50;")
            
        except ValueError:
            self.append_log("端口格式错误，请输入有效的端口号！", "error")

    def stop_server(self):
        if self.server:
            self.server.stop()
            self.server = None
            
        self.start_button.setEnabled(True)
        self.stop_button.setEnabled(False)
        self.list_button.setEnabled(False)
        self.port_input.setEnabled(True)
        self.status_label.setText("🔴 服务已停止")
        self.status_label.setStyleSheet("color: #f44336;")
        self.session_stats_label.setText("已注册会话: 0")
        self.append_log("[INFO] 服务器已停止", "info")

    def show_sessions(self):
        if self.server:
            dialog = SessionListDialog(self.server, self.log_queue, self)
            dialog.exec()

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
