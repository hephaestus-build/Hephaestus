#### 🔴 Check TLS 1.2 signatures of self-hosted providers for Java 25

**Who is affected:** Operators whose installation connects to a self-hosted GitLab, Outline or mail server that offers only TLS 1.2 and signs its handshakes with SHA-1. The server image now runs Java 25, which refuses such handshakes ([JDK-8340321](https://bugs.openjdk.org/browse/JDK-8340321)).

**Action:** Before you update, make sure each of these servers offers TLS 1.3, or signs its TLS 1.2 handshakes with SHA-256 or stronger. A server that still uses SHA-1 fails to connect after the update: synchronization, webhook registration or email to that server stops with a TLS handshake error in the server log.
