---
"hephaestus": patch
---

The one-host installer no longer stops at "Waiting for cert-manager to issue the certificates" when cert-manager has not yet given a certificate a status. It now waits for the certificate for as long as before, and a wait that runs out names what kubectl last reported.
