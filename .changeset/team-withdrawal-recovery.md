---
"hephaestus": patch
---

The erasure recovery manifest can withdraw submitted team work again after a database restore, with the optional lists `teamVersionWithdrawals` and `workspaceDepartures`. Nothing submits team work yet, so existing manifests stay valid. Recovery now stops on a manifest that has a fraction where it expects a whole number, such as an account ID, instead of cutting it off.
