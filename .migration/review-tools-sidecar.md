#### 🔴 Rebuild a custom agent image

**Affected**: deployments that pin their own agent image. An image from the release runs without any action.

A review Pod now has more than one container, and they start from programs in the image. Rebuild your image from this release, then verify that its `hephaestus.agent.runtime-contract` label matches the official agent image for this release.
