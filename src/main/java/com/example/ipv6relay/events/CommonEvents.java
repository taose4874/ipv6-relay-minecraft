package com.example.ipv6relay.events;

import com.example.ipv6relay.IPv6Relay;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

public class CommonEvents {
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        IPv6Relay.LOGGER.info("IPv6 Relay Common Setup");
    }
}
