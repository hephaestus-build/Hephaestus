---
"hephaestus": patch
---

The server runs on Spring Boot 4.1.3, Spring Security 7.1.3 and Spring Framework 7.0.11. These releases fix the Spring security advisories of 19 November 2026. Install the new release. No other operator step is necessary.

Two advisories applied to Hephaestus. With CVE-2026-59330, passkey sign-in could accept a cloned security key. CVE-2026-59333 applied to the address check of the gateway, but no address that a network routes passed that check. Each call to the gateway also needs its attempt token. The other advisories, including CVE-2026-59411 (Cloud Foundry) and CVE-2026-59367 (embedded LDAP test server), apply to functions that Hephaestus does not use.
