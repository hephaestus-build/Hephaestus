package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.nats.client.Options;
import java.util.Objects;

public final class NatsOptions {

    private NatsOptions() {}

    public static Options.Builder builder(NatsConnectionProperties properties) {
        Options.Builder builder = Options.builder()
                .server(properties.server())
                // Keep the configured name, rather than a resolved IP, available to TLS.
                .hostnameResolveMode(Options.HostnameResolveMode.HappyEyeballs)
                .dataPortType(HostnameVerifiedNatsDataPort.class.getName());
        if (properties.username() != null) {
            builder.userInfo(
                    properties.username().toCharArray(),
                    Objects.requireNonNull(properties.password()).toCharArray());
        }
        return builder;
    }
}
