# IPv6 Relay Mod for Minecraft 1.21.1

## Overview

This mod enables IPv6 connectivity for Minecraft servers, allowing players to connect to IPv6-only servers through a relay system.

## Features

- **IPv6 Support**: Connect to IPv6 servers that cannot be directly accessed
- **Relay System**: Use a relay server to bridge connections between IPv6 devices
- **GUI Interface**: Configure relay settings in-game with a simple GUI
- **Cross-Platform**: Works on Windows, macOS, and Linux

## Requirements

- Minecraft 1.21.1
- Forge 47.0.1 or later
- Java 21 or later

## Installation

1. Download the mod JAR file
2. Place it in your Minecraft `mods` folder
3. Run Minecraft with Forge

## Usage

### Starting the Relay Server

1. Compile the standalone relay server:
   ```bash
   javac RelayServerApp.java
   ```

2. Run the relay server:
   ```bash
   java RelayServerApp
   ```

3. The server will listen on port 25566 for IPv6 connections.

### In-Game Configuration

1. Press `R` key to open the relay configuration GUI
2. Enter the relay server IPv6 address and port
3. Enter the target server address (the IPv6 server you want to connect to)
4. Click "Connect" to establish the relay connection

## Project Structure

```
ipv6-relay-mod/
├── src/main/java/com/example/ipv6relay/
│   ├── IPv6Relay.java          # Main mod class
│   ├── config/
│   │   └── RelayConfig.java    # Configuration handling
│   ├── gui/
│   │   ├── RelayGui.java       # Configuration GUI
│   │   └── RelayButton.java    # Key binding handler
│   ├── networking/
│   │   ├── RelayServer.java    # In-game relay server
│   │   ├── IPv6PacketRelay.java # Packet relay system
│   │   ├── IPv6Networking.java # IPv6 connection handling
│   │   ├── PacketHandler.java  # Network packet registration
│   │   └── RelayPacket.java    # Custom packet class
│   └── events/
│       ├── ClientEvents.java   # Client-side events
│       ├── ServerEvents.java   # Server-side events
│       └── CommonEvents.java   # Common events
├── src/main/resources/
│   ├── META-INF/
│   │   └── mods.toml           # Mod metadata
│   ├── assets/ipv6relay/lang/
│   │   └── en_us.json          # Language file
│   └── pack.mcmeta             # Resource pack metadata
├── RelayServerApp.java         # Standalone relay server
├── build.gradle                # Gradle build configuration
├── settings.gradle             # Gradle settings
└── gradlew.bat                 # Gradle wrapper (Windows)
```

## Building

To build the mod:

1. Ensure you have Java 21 and Gradle installed
2. Run:
   ```bash
   ./gradlew build
   ```
3. The built JAR will be in `build/libs/`

## License

This project is licensed under the MIT License.
