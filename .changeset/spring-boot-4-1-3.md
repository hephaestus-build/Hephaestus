---
"hephaestus": patch
---

The server runs on Spring Boot 4.1.3, Spring Security 7.1.3 and Spring Framework 7.0.11. These releases fix the Spring security advisories of 19 November 2026. Install the new release. No other operator step is necessary.

Two advisories applied to Hephaestus. With CVE-2026-59330, passkey sign-in could accept a cloned security key. With CVE-2026-59333, the address check of the gateway could accept an address of the other IP family as a private address. The gateway still verified the credential of each call. For the other advisories, Hephaestus does not use the affected function, or its configuration stops the attack. For example, the Actuator endpoints that CVE-2026-59414 concerns are available only on the management port.
