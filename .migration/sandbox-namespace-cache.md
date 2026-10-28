#### 🔴 Grant the worker `watch` on ResourceQuotas if you manage its Role yourself

The worker now watches the ResourceQuotas of the sandbox namespace, as it watches its Pods and Jobs. The chart's Role grants `list` and `watch` on `resourcequotas`, and no longer `get`, so an install that uses the chart's Role needs no action.

If you replaced the worker's Role in the sandbox namespace with your own, add `watch` on `resourcequotas` before you upgrade. Without it, the worker logs that a sandbox namespace informer failed with status `403`, and it stays not ready until the verb is granted.
