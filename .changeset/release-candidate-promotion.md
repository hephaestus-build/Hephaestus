---
"hephaestus": patch
---

Publish numbered release candidates without changing the stable image aliases or GitHub's latest release. Promote a qualified candidate to a stable release with the same signed image and chart digests.

A promoted stable release keeps its candidate's chart version and image build metadata. Use the signed release lock and the release values from the Kubernetes guide to install it.
