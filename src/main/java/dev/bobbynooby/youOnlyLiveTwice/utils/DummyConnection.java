package dev.bobbynooby.youOnlyLiveTwice.utils;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

public class DummyConnection extends Connection {
    public DummyConnection(PacketFlow enumprotocoldirection) {
        super(enumprotocoldirection);

    }
}
