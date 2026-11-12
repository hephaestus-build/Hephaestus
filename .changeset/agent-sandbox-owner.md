---
"hephaestus": patch
---

The server can now request and delete agent-sandbox claims in the sandbox namespace, and it deletes each claim that no work needs. The chart grants this in the server's Role in the sandbox namespace and gives the server the names of the sandbox warm pools and the gateway address. No operator action is needed. A new alert, `HephaestusAgentSandboxReaperFailing`, fires when the server keeps failing to delete such claims.
