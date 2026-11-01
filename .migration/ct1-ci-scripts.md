#### 🔴 Move an existing installation to Helm before upgrading

Follow the [0.95 migration guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/compose-to-kubernetes.mdx), then install 0.95.1 before upgrading to 1.0. Keep the matching database backup, broker recovery material, encryption keys and signed release lock together. The [Kubernetes guide](https://docs.hephaestus.build/admin/kubernetes) owns installation, backup and recovery. Compose remains only for local development infrastructure.
