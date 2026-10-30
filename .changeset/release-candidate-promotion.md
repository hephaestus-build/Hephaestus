---
"hephaestus": patch
---

Publish numbered release candidates without changing the stable image aliases or GitHub's latest release. Promote a qualified candidate to a stable release with the same signed image digests and stable chart metadata.

A promoted stable release keeps its candidate's image build metadata. Use the signed release lock and the release values from the Kubernetes guide to install it.
