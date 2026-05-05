package com.example.ipv6relay.gui;

import com.example.ipv6relay.IPv6Relay;
import com.example.ipv6relay.config.RelayConfig;
import com.example.ipv6relay.networking.IPv6PacketRelay;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class RelayGui extends Screen {
    private EditBox relayHostField;
    private EditBox relayPortField;
    private EditBox localPortField;
    private Button connectButton;
    private Button disconnectButton;
    private final AtomicReference<String> statusMessage = new AtomicReference<>("");
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private volatile String copyAddress = null;
    private volatile int lastShownPort = -1;

    public RelayGui() {
        super(Component.literal("IPv6 \u4e2d\u7ee7"));
    }

    private String safeGetHost() {
        return RelayConfig.relayHost;
    }

    private int safeGetPort() {
        return RelayConfig.relayPort;
    }

    private int safeGetLocalPort() {
        // 优先使用自动检测到的端口
        int detected = IPv6Relay.getDetectedLanPort();
        if (detected > 0) {
            return detected;
        }
        // 否则使用配置或默认值
        return RelayConfig.targetPort;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        int fw = 200;
        int fh = 20;
        int left = cx - fw / 2;
        int y = cy - 60;

        relayHostField = new EditBox(this.font, left, y, fw, fh,
                Component.literal("\u4e2d\u7ee7\u5730\u5740"));
        relayHostField.setMaxLength(128);
        relayHostField.setValue(safeGetHost());
        this.addRenderableWidget(relayHostField);

        relayPortField = new EditBox(this.font, left, y + 40, fw, fh,
                Component.literal("\u4e2d\u7ee7\u7aef\u53e3"));
        relayPortField.setMaxLength(5);
        relayPortField.setValue(String.valueOf(safeGetPort()));
        this.addRenderableWidget(relayPortField);

        localPortField = new EditBox(this.font, left, y + 80, fw, fh,
                Component.literal("\u672c\u5730\u7aef\u53e3"));
        localPortField.setMaxLength(5);
        
        // 优先使用检测到的端口
        int initialLocalPort = safeGetLocalPort();
        localPortField.setValue(String.valueOf(initialLocalPort));
        lastShownPort = initialLocalPort;
        
        this.addRenderableWidget(localPortField);

        connectButton = Button.builder(Component.literal("\u8fde\u63a5"), (btn) -> {
            doConnect();
        }).bounds(left, y + 120, fw / 2 - 5, 20).build();
        this.addRenderableWidget(connectButton);

        disconnectButton = Button.builder(Component.literal("\u65ad\u5f00"), (btn) -> {
            doDisconnect();
        }).bounds(left + fw / 2 + 5, y + 120, fw / 2 - 35, 20).build();
        this.addRenderableWidget(disconnectButton);
        
        // 添加返回按钮，在断开按钮右边
        Button backButton = Button.builder(Component.literal("返回"), (btn) -> {
            if (minecraft != null) {
                minecraft.setScreen(null);
            }
        }).bounds(left + fw - 25, y + 120, 60, 20).build();
        this.addRenderableWidget(backButton);

        updateButtons();
    }

    private void doConnect() {
        if (connecting.get()) return;
        
        // 检查是否已经开放了局域网
        int detectedPort = IPv6Relay.getDetectedLanPort();
        if (detectedPort <= 0) {
            statusMessage.set("请先开放局域网！");
            return;
        }

        String host = relayHostField.getValue().trim();
        if (host.isEmpty()) {
            statusMessage.set("\u8bf7\u8f93\u5165\u4e2d\u7ee7\u5730\u5740");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(relayPortField.getValue().trim());
        } catch (NumberFormatException e) {
            statusMessage.set("\u4e2d\u7ee7\u7aef\u53e3\u65e0\u6548");
            return;
        }
        int localPort;
        try {
            localPort = Integer.parseInt(localPortField.getValue().trim());
        } catch (NumberFormatException e) {
            statusMessage.set("\u672c\u5730\u7aef\u53e3\u65e0\u6548");
            return;
        }

        connecting.set(true);
        statusMessage.set("\u6b63\u5728\u8fde\u63a5...");
        connectButton.active = false;
        disconnectButton.active = false;

        final String fhost = host;
        final int fport = port, flocalPort = localPort;
        new Thread(() -> {
            IPv6PacketRelay old = IPv6Relay.getPacketRelay();
            if (old != null && old.isConnected()) old.disconnect();

            IPv6PacketRelay relay = new IPv6PacketRelay();
            boolean ok = relay.connectToRelay(fhost, fport);
            if (ok) {
                relay.registerServer(flocalPort);
                IPv6Relay.setPacketRelay(relay);
                try {
                    RelayConfig.relayHost = fhost;
                    RelayConfig.relayPort = fport;
                    RelayConfig.targetPort = flocalPort;
                    RelayConfig.save();
                } catch (Exception e) {
                    IPv6Relay.LOGGER.warn("保存配置失败: {}", e.getMessage());
                }
                statusMessage.set("\u5df2\u8fde\u63a5");
            } else {
                IPv6Relay.setPacketRelay(null);
                statusMessage.set("\u8fde\u63a5\u5931\u8d25");
            }
            connecting.set(false);
            minecraft.execute(() -> updateButtons());
        }, "IPv6Relay-Connect").start();
    }

    private void doDisconnect() {
        IPv6PacketRelay relay = IPv6Relay.getPacketRelay();
        if (relay != null) {
            try { relay.disconnect(); } catch (Exception e) {}
        }
        IPv6Relay.setPacketRelay(null);
        statusMessage.set("\u5df2\u65ad\u5f00");
        copyAddress = null;
        updateButtons();
    }

    private void updateButtons() {
        IPv6PacketRelay relay = IPv6Relay.getPacketRelay();
        boolean on = relay != null && relay.isConnected();
        connectButton.active = !on && !connecting.get();
        disconnectButton.active = on;
        relayHostField.setEditable(!on);
        relayPortField.setEditable(!on);
        localPortField.setEditable(!on);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 先让按钮正常处理点击
        boolean handled = super.mouseClicked(mx, my, button);
        // 如果点击了按钮区域，不拦截
        if (handled) return true;
        // 只在空白区域点击时复制地址
        if (button == 0 && copyAddress != null) {
            minecraft.keyboardHandler.setClipboard(copyAddress);
            statusMessage.set("\u5df2\u590d\u5236: " + copyAddress);
            return true;
        }
        return handled;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g, mx, my, pt);
        super.render(g, mx, my, pt);

        Font f = this.font;
        int cx = this.width / 2;
        int y = this.height / 2 - 60;

        // 实时更新本地端口（如果有新检测到的）
        updateLocalPortIfNeeded();

        // 标题
        g.drawCenteredString(f, Component.literal("IPv6 \u4e2d\u7ee7"), cx, y - 30, 0xFFFFFF);

        // 标签
        g.drawString(f, Component.literal("\u4e2d\u7ee7\u5730\u5740:"), cx - 105, y - 12, 0xAAAAAA);
        g.drawString(f, Component.literal("\u4e2d\u7ee7\u7aef\u53e3:"), cx - 105, y + 28, 0xAAAAAA);
        g.drawString(f, Component.literal("\u672c\u5730\u7aef\u53e3:"), cx - 105, y + 68, 0xAAAAAA);
        
        // 提示信息
        IPv6PacketRelay relay = IPv6Relay.getPacketRelay();
        boolean on = relay != null && relay.isConnected();
        
        if (!on) {
            // 显示提示信息
            int detectedPort = IPv6Relay.getDetectedLanPort();
            if (detectedPort > 0) {
                g.drawCenteredString(f, Component.literal("✓ 已检测到局域网端口: " + detectedPort), cx, y + 100, 0x55FF55);
            } else {
                g.drawCenteredString(f, Component.literal("⚠ 请先开放局域网服务器"), cx, y + 100, 0xFFAA00);
            }
        }

        // 连接状态
        int col = on ? 0x55FF55 : 0xFF5555;
        String st = on ? "\u25cf \u5df2\u8fde\u63a5" : "\u25cb \u672a\u8fde\u63a5";
        g.drawCenteredString(f, Component.literal(st), cx, y + 150, col);

        if (on && relay.getAssignedPort() > 0) {
            String addr = relay.getRelayHost() + ":" + relay.getAssignedPort();
            g.drawCenteredString(f, Component.literal("\u5206\u4eab\u5730\u5740: " + addr), cx, y + 170, 0x55FFFF);
            g.drawCenteredString(f, Component.literal("(\u70b9\u51fb\u590d\u5236)"), cx, y + 185, 0xAAAAAA);
            copyAddress = addr;

            // 隧道状态
            int tunnels = relay.getActiveTunnels();
            g.drawCenteredString(f, Component.literal("\u96a7\u9053\u6570\u91cf: " + tunnels), cx, y + 200, 0xAAAAFF);
        } else {
            copyAddress = null;
        }

        // 状态消息
        String msg = statusMessage.get();
        if (!msg.isEmpty()) {
            g.drawCenteredString(f, Component.literal(msg), cx, y + 215, 0xFFFF55);
        }
    }
    
    /**
     * 如果检测到新的端口，自动更新到输入框
     */
    private void updateLocalPortIfNeeded() {
        IPv6PacketRelay relay = IPv6Relay.getPacketRelay();
        boolean on = relay != null && relay.isConnected();
        
        // 只有在未连接时才更新
        if (!on && localPortField != null) {
            int detected = IPv6Relay.getDetectedLanPort();
            if (detected > 0 && detected != lastShownPort) {
                localPortField.setValue(String.valueOf(detected));
                lastShownPort = detected;
                statusMessage.set("自动检测到局域网端口: " + detected);
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
