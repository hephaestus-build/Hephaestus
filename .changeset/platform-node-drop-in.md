---
"hephaestus": minor
---

The one-host installer sets the node address and the node name of k3s with `--node-ip` and `--node-name`. It writes them to `/etc/rancher/k3s/config.yaml.d/50-node.yaml`, so `/etc/rancher/k3s/config.yaml` stays the shipped file. `--check-platform` shows the keys of that file, reports any other key in it, and reports each other file in `config.yaml.d/`.

The installer replaces `config.yaml` with the shipped file on each run, as before. If you added `node-ip` or `node-name` to it yourself, give them with these options. The installer stops when a run would rename the running node, or when `--node-ip` names an address that the host does not have.
