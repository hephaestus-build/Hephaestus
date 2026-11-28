---
"hephaestus": patch
---

A person erasure no longer stops when a rolling release adds a person-data store. A server of the older release leaves the request for one of the newer release, and a server of the newer release finishes it. The new `HephaestusPersonErasureDeferred` alert fires if an erasure keeps waiting for an hour, for example when no running server knows its stores.
