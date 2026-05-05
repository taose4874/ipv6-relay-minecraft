package com.example.ipv6relay.events;

import com.example.ipv6relay.IPv6Relay;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;

public class ServerEvents {
    public static void onServerSetup(FMLDedicatedServerSetupEvent event) {
        IPv6Relay.LOGGER.info("IPv6 Relay Server Setup");
    }
}
