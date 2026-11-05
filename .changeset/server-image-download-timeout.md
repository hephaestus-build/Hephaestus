---
"hephaestus": patch
---

A build of the server image no longer fails when GitHub takes more than 5 seconds to start sending the Java runtime or another buildpack download. The build now waits up to 60 seconds.
