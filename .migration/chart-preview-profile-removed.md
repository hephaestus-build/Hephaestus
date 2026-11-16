#### 🔴 Install with a profile that the chart still ships

- **When:** before the window
- **Action:** If a Helm command gives `profiles/preview.yaml`, give `profiles/dev.yaml` or the profile of your cluster.

The chart no longer ships `profiles/preview.yaml`. No release workflow used it.
A Helm command that gives it fails, because the file is missing.
`profiles/dev.yaml` sets the same capacity for a disposable cluster without sandboxes.
It sets `deploymentEnvironment` to `dev`, not `preview`. If your telemetry or alerts select `preview`, set `deploymentEnvironment: preview` in your own values.

| Where | Before | After |
| --- | --- | --- |
| Profile | `profiles/preview.yaml` | `profiles/dev.yaml` for a disposable cluster, or the profile of your cluster |
