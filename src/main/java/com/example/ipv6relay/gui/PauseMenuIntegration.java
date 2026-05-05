package com.example.ipv6relay.gui;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

public class PauseMenuIntegration {
    @SubscribeEvent
    public void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof PauseScreen) {
            int width = event.getScreen().width;
            int height = event.getScreen().height;

            int lanY = height / 4 + 72;
            int btnW = 100;
            int btnH = 20;

            Button relayButton = Button.builder(Component.literal("IPv6 \u4e2d\u7ee7"), (button) -> {
                net.minecraft.client.Minecraft.getInstance().setScreen(new RelayGui());
            }).bounds(width / 2 + 106, lanY, btnW, btnH).build();

            event.addListener(relayButton);
        }
    }
}
