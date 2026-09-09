---
"hephaestus": patch
---

An instance whose NATS server URI is blank or missing its `nats://` scheme now starts far enough to print the configuration readiness report that names the setting, instead of stopping on an internal error that said nothing about which value was wrong. The URI is required of the server and webhook roles, and the shipped default is blank, so this was every first production start of those roles that had not set it yet. A NATS URI whose scheme is written in capitals is also no longer reported as needing action, since the client accepts it.
