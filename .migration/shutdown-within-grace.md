#### 🔴 Stop grace periods

The Helm chart and Compose files set new minimum stop grace periods: 45 seconds for the server, and the drain plus 2 minutes for the worker (7 minutes in Compose for the default 5-minute drain).
If you override `stop_grace_period` in your own Compose files, keep at least these values. If you raised `HEPHAESTUS_WORKER_DRAIN_TIMEOUT`, set the worker's `stop_grace_period` to at least the drain plus 2 minutes.
