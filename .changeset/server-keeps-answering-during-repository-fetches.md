---
"hephaestus": patch
---

The server no longer stops answering when several reviews fetch the same repository at once, as after a rollout while reviews run. Before, every request could wait for minutes, and the liveness probe then restarted the server, so the running reviews lost an attempt. A Git provider that stops answering for 60 seconds now ends its fetch. The review records the repository as unavailable, and the next review fetches it again. The new alert `HephaestusGitFetchesStopping` reports repeated stops. The server also keeps a flight recording in its temporary volume, which shows the cause if it stops answering again.
