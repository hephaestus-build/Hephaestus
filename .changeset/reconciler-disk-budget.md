---
"hephaestus": patch
---

Pull-based deployment no longer fills the host's disk with old images. After each apply, the host removes the images that neither the running nor the previous release uses, so a rollback to the previous release still needs no download. Before it pulls a release, the host checks the free space for Docker's data and refuses the apply below 10 GiB, after first removing those unused images. A rollback whose images are already on the host is never refused. The new `hephaestus_deploy_free_bytes` metric lets you warn earlier. Set the optional `HEPHAESTUS_MIN_FREE_GIB` in `reconcile.env` to change the threshold.
