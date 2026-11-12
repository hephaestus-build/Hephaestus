#### 🔴 Install the agent-sandbox controller before the chart

**Affected**: installations on a cluster other than the one-host k3s platform, with sandboxes enabled. The one-host installer applies the controller itself.

The chart now installs `SandboxTemplate` and `SandboxWarmPool` objects, which need the agent-sandbox controller and its resource definitions. Before you upgrade the chart, apply `k3s-agent-sandbox.yaml` from the release's k3s bundle, and wait for the controller:

```bash
kubectl apply --server-side -f k3s-agent-sandbox.yaml
kubectl -n agent-sandbox-system rollout status deployment/agent-sandbox-controller
```

Without it, `helm upgrade` stops with an error that it cannot find the kind `SandboxTemplate`.
