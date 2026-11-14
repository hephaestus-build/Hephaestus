---
"hephaestus": patch
---

The server no longer fills its Git cache with the folders of finished reviews until Kubernetes evicts it. A finished, failed or retried review attempt's folder, which holds a full checkout of the repository, is now removed within a minute instead of after an hour. A server restart also completes its cleanup instead of stopping with an error on each folder. A folder that stays for any reason counts against the evidence reconcile, so `HephaestusEvidenceReconcileStalled` reports it.
