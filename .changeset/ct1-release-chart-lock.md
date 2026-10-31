---
"hephaestus": minor
---

Releases publish one signed chart lock with the chart digest, image digests, and source commit. Image-only locks and their environment-output command are removed. Release verification no longer resolves artifacts from versions before 0.75.0.

Stopped upgrades still check the previous receiver’s NATS delivery protocol against the candidate. The schema compatibility waiver applies only to the previous application boot.

**Operators:** Complete the move to Helm on k3s or Kubernetes before upgrading. Update custom release consumers to use the schema-2 `release-vX.Y.Z.chart.json` asset and its signature bundle.
