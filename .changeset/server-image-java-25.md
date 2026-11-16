---
"hephaestus": patch
---

The server image now runs Java 25, the current long-term support release of Java. At startup, the server log shows one warning about native access, which is expected. If you add your own JVM options, check that Java 25 still accepts them: an option that Java 25 removed, such as the Security Manager flags, stops the server at startup. If you build the server from source, install JDK 25 first.
