---
"hephaestus": patch
---

An instance whose NATS server URI is blank or missing its `nats://` scheme now starts far enough to print the configuration readiness report that names the setting, instead of stopping on an internal error that said nothing about which value was wrong. The shipped default is blank, so this was every first production start that had not set it yet.
