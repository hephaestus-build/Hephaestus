---
"hephaestus": minor
---

You can now install Hephaestus on a managed Kubernetes cluster on which you are not a cluster-admin. The new `cluster.yaml` profile runs reviews on the cluster's default runtime with user namespaces and runs the agent-sandbox controller in the release namespace. With it, the chart creates no cluster-scoped object. The release's k3s bundle now carries an admin bundle for the cluster's admins: the agent-sandbox definitions, the three PriorityClasses and one read-only ClusterRole for the controller. A preflight checks your access, the admin bundle, the namespaces, the Gateway and the storage before you install, and gives one line per missing item that you can forward to the admins. The admin guide's new page, "Install on a managed cluster without cluster-admin", gives the steps. The sandbox isolation gate takes the cluster's DNS Service with `--dns`, for distributions such as RKE2.
