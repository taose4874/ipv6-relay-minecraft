# IPv6 中继联机 Mod - Minecraft 1.21.1

## 简介

通过 IPv6 中继服务器实现 Minecraft 联机，让没有公网 IPv4 的玩家也能轻松开服联机。

## 功能特性

- **IPv6 中继**：通过中继服务器桥接连接，无需公网 IPv4
- **自动检测局域网端口**：开局域网后自动检测端口
- **图形界面**：游戏内配置中继地址，一键连接/断开
- **配置持久化**：中继地址自动保存，重启不丢失
- **跨平台**：支持 Windows、macOS、Linux

## 环境要求

- Minecraft 1.21.1
- NeoForge 21.1.x
- Java 21 或更高版本

## 安装方法

1. 下载 Mod 的 jar 文件（`IPv6Relay-1.0.0.jar`）
2. 放入 Minecraft 的 `mods` 文件夹
3. 使用 NeoForge 启动 Minecraft

## 使用方法

### 启动中继服务器

1. 进入 `IPv6中继服务器` 目录
2. 双击 `启动服务器.bat`（Windows）或运行 `java -jar RelayServer.jar`
3. 服务器将在 25566 端口监听 IPv6 连接

### 游戏内配置

1. 开放局域网（对局域网开放）
2. 按 `R` 键打开中继配置界面
3. 输入中继服务器地址和端口
4. 点击「连接」建立中继
5. 将分配到的地址（如 `mcyfwq.cn:25567`）分享给朋友即可联机

## 项目结构

```
├── src/main/java/com/example/ipv6relay/
│   ├── IPv6Relay.java              # Mod 主类
│   ├── config/
│   │   └── RelayConfig.java        # 配置管理
│   ├── events/
│   │   ├── ClientEvents.java       # 客户端事件（端口检测）
│   │   ├── ServerEvents.java       # 服务端事件
│   │   └── CommonEvents.java       # 通用事件
│   ├── gui/
│   │   ├── RelayGui.java           # 中继配置界面
│   │   ├── RelayButton.java        # 按钮组件
│   │   └── PauseMenuIntegration.java # 暂停菜单集成
│   └── networking/
│       ├── IPv6PacketRelay.java    # 中继客户端（隧道管理）
│       └── RelayServer.java        # 内置中继服务
├── RelayServerApp.java             # 独立中继服务器
├── IPv6中继服务器/
│   ├── RelayServer.jar             # 编译好的中继服务器
│   ├── IPv6Relay-1.0.0.jar         # 编译好的 Mod
│   ├── 启动服务器.bat               # 一键启动脚本
│   └── 使用说明.md                  # 使用说明
├── build.gradle                    # Gradle 构建配置
└── settings.gradle                 # Gradle 设置
```

## 构建

```bash
./gradlew build
```

构建产物在 `build/libs/` 目录下。

## 工作原理

```
玩家 → 中继服务器(公网IPv6) → 隧道 → 本地Minecraft服务器
```

1. 开服方连接中继服务器并注册本地端口
2. 中继服务器分配一个公开端口
3. 开服方预先建立隧道到中继
4. 玩家连接中继的公开端口
5. 中继将玩家连接与隧道桥接
6. 数据双向转发，实现联机

## 许可证

MIT License
