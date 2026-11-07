#### 🔴 NATS broker certificate names

Hephaestus verifies the broker's hostname when it connects to NATS over TLS.
Before you upgrade, check the certificate names for each configured or advertised TLS broker address.
Start with the host in `nats.external.url`.

For a DNS name, use a DNS subject alternative name. For an IP address, use an IP subject alternative name.

The bundled NATS broker continues to use its connection without TLS.
