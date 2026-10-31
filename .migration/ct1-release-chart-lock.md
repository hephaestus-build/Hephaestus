#### 🔴 Use the chart release lock

Before upgrading, move Compose deployments to the supported Helm installation on k3s or Kubernetes.
Update custom release consumers to download `release-vX.Y.Z.chart.json` and `release-vX.Y.Z.chart.json.sigstore.json`.
The schema-2 lock covers the chart digest, image digests, and source commit.
The release no longer publishes the schema-1 `release-vX.Y.Z.json` asset or its signature bundle.

Replace the positional environment-output command with `release-image-lock.ts verify <lock.json> <manifest.json> <release>`.
This command verifies the joint lock against the evidence manifest and writes no environment file.
The `projection` command is removed.

Current release tools refuse versions before 0.75.0 because the supported identity map no longer contains their namespace and signing repository.
Use the tooling from those original releases when you must inspect their archived artifacts.
