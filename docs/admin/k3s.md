---
title: Install on one host
description: Install Hephaestus on one Linux host with k3s and the signed Helm chart, with one installer command.
page_type: how-to
---

This procedure installs Hephaestus on one Linux host with [k3s](https://docs.k3s.io/) and the Helm chart.
One command, `k3s-install.sh`, does the install.
It checks the host, installs the pinned k3s and the platform, and keeps the release Secret.
Then it verifies the release chart and installs it.
It uses the `small.yaml` profile, the bundled PostgreSQL, and gVisor sandboxes for practice reviews.

A second run of the same release changes nothing that the first run made.
To upgrade, follow [Upgrade the host](#upgrade-the-host).

## Before you start

- A host of 4 vCPU, 16 GiB of memory, and 150 GiB of disk, as [Profiles](./helm-chart#profiles) gives for `small.yaml`.
- Ubuntu 24.04 with its 6.8 GA kernel on `amd64` or `arm64`. The installer refuses another OS or kernel unless you add `--allow-unqualified-host`.
- A DNS A record for your host name that points to the public address of the host.
- Ports 80 and 443 open from the internet, for `Let's Encrypt`, OAuth callbacks, and provider webhooks. Keep port 6443, the Kubernetes API, and port 10250, the kubelet, closed to the internet.
- If `ufw` is active, rules that let in ports 80 and 443 and the k3s networks: `sudo ufw allow 80,443/tcp`, `sudo ufw allow from 10.42.0.0/16`, and `sudo ufw allow from 10.43.0.0/16`.
- Outbound HTTPS to the destinations in [Network egress](./network-egress#one-host-on-k3s).
- `curl`, `jq`, `openssl`, `python3`, [Cosign](https://docs.sigstore.dev/cosign/system_config/installation/) 3.1 or later, and Helm 4. Ubuntu includes `python3`.
- A clock that NTP keeps synchronized, as [Clocks](./helm-chart#clocks) describes.
- A GitHub OAuth app for sign-in, as [First steps § 1](./first-steps#1-configure-a-login-provider) describes. Its callback URL is `https://<host>/api/login/oauth2/code/github`.

## What the installer checks

The installer stops before it changes the host when one of these checks fails. Each message tells you what to change.

| Check | Condition |
| --- | --- |
| Input | The host name, the address, the OAuth client ID, and the admin list have a strict format. |
| Size | The host has at least 4 CPUs, and the kernel reports at least 15 GiB of memory. A host of 16 GiB meets this, because the kernel keeps part of the memory. If `/var` has less than 100 GiB free, the installer warns and continues. |
| Clock | NTP synchronizes the clock. |
| Swap | No swap device or file is active. The kubelet also refuses swap. |
| Ports | No other program listens on port 80 or 443. The installer checks this before k3s runs. |
| Host firewall | If `ufw` is active, it allows `80/tcp` and `443/tcp` by a port rule or by the `Nginx`, `Apache`, or `WWW` profile. If `firewalld` is active, the zone of the default route allows `http` and `https`, or ports 80 and 443. Otherwise the installer stops and gives the command to run. |
| k3s release | The running k3s is not newer than the pin. k3s supports no downgrade. |
| Node name | If k3s is installed, it runs with exactly one node, and the run keeps the node name. A new name registers a second node, and the local volumes stay with the old one. A stopped k3s stops the run, because the installer cannot read its node. |
| Environment | `INSTALL_K3S_EXEC`, `K3S_NODE_NAME` and `K3S_CONFIG_FILE` are not set. The k3s install script writes them into the k3s service, where they override the platform files. |
| DNS | The host name resolves to this host. Before k3s runs, the installer serves a random token on port 80 for a moment, and it fetches the token through the host name. |

The DNS check proves only that the host name leads to this host.
The request can stay on the host, so it does not prove that the internet reaches port 80.
A cloud firewall or a security group of your provider is yours to open, and the installer cannot check it.

cert-manager needs port 80 from the internet to get the certificate.
The installer waits for the certificate for 10 minutes, and then it stops with the command that shows the reason.
If your network cannot reach its own public address, the DNS check fails. Then run the installer with `--skip-check dns`.

## 1. Get the release files

1. Make a directory for the release files. The installer keeps its state there: `site.yaml`, the verified chart, and a `kubeconfig` file.
   Use the version of the newest release on the [releases page](https://github.com/hephaestus-build/Hephaestus/releases).

   ```bash
   VERSION=1.0.0-rc.9   # the release, without the leading "v"
   mkdir -p ~/hephaestus && cd ~/hephaestus
   ```

2. Download the bundle of the release and its signature:

   ```bash
   release="https://github.com/hephaestus-build/Hephaestus/releases/download/v$VERSION"
   curl -fsSLO "$release/hephaestus-k3s-$VERSION.tar.gz"
   curl -fsSLO "$release/hephaestus-k3s-$VERSION.tar.gz.sigstore.json"
   ```

3. Verify the signature, then unpack the bundle. Cosign stops with an error if the bundle does not come from the release workflow, and then `tar` does not run:

   ```bash
   cosign verify-blob --bundle "hephaestus-k3s-$VERSION.tar.gz.sigstore.json" \
     --certificate-identity https://github.com/hephaestus-build/Hephaestus/.github/workflows/release.yml@refs/heads/main \
     --certificate-oidc-issuer https://token.actions.githubusercontent.com \
     "hephaestus-k3s-$VERSION.tar.gz" &&
     tar xzf "hephaestus-k3s-$VERSION.tar.gz"
   ```

   Do not run any file of the bundle before this step succeeds.

## 2. Run the installer

1. Run the installer in a terminal:

   ```bash
   sh k3s-install.sh
   ```

   It asks for each value that a first install needs and that you did not give as a flag:
   - The release, the host name, and the address that gets the expiry notices of `Let's Encrypt`.
   - The client ID and the client secret of a GitHub OAuth app. The installer prints the homepage URL and the callback URL to enter at GitHub first. The terminal does not show the secret.
   - Your GitHub login. The installer looks up your numeric GitHub user ID and puts it in the admin list.

2. To run the installer without questions, for example from a script, give every value instead.
   Put the client secret in `GITHUB_OAUTH_CLIENT_SECRET` from your secret store, for example from a file that only you can read:

   ```bash
   GITHUB_OAUTH_CLIENT_SECRET=$(cat /run/secrets/github-oauth-client-secret) && export GITHUB_OAUTH_CLIENT_SECRET
   sh k3s-install.sh --version "$VERSION" --host hephaestus.example.com --email ops@example.com \
     --github-client-id <the client ID of your OAuth app> --bootstrap-admins github:<your numeric GitHub user ID>
   ```

   Without a terminal, the installer asks nothing. It stops and names the missing flag or variable.
   The value never appears in a command line, so other users of the host do not see it.
   [First steps § 2](./first-steps#2-name-the-first-admin) explains the admin list.

   For a first try, you can use the `Let's Encrypt` staging service. It has higher rate limits, and no browser trusts its certificate:

   1. Add `--staging` to the installer command.
   2. When the install works, run the installer again without `--staging`, and with `--standard-certificates`.
   3. Delete the staging certificate. cert-manager then gets a trusted certificate:

      ```bash
      KUBECONFIG=~/hephaestus/kubeconfig kubectl --namespace traefik delete secret hephaestus-tls
      ```

3. Back up the encryption keys before you continue.
   Hephaestus cannot make them again: without them, the data that they protect is lost.
   The installer ends with the command that copies them to a file that only you can read.
   [Copy the keys from the Secret](./secret-custody#copy-the-keys-from-the-secret) gives the command.
   [Keep the required keys outside the cluster](./secret-custody#keep-the-required-keys-outside-the-cluster) says where to keep the file.
   For `kubectl` on this host, run `export KUBECONFIG=~/hephaestus/kubeconfig` first.

The installer runs `hephaestus-keys.sh`, which keeps every key of the release Secret `hephaestus-app` and generates only the keys that it lacks.
It generates each key as [Secret custody and rotation](./secret-custody#generation-and-rotation) gives it.
If the PostgreSQL volume exists and the Secret lacks the database login or an encryption key, the installer stops: restore the Secret from your backup.

The installer writes `site.yaml` only when the file does not exist.
It holds the host name, the Kubernetes API endpoint, the OAuth client ID, and the admin list.
Change the file to change these values, and run the installer again.
Before a repeat run, pause deployment automation and wait for its active run to finish.

Without a login provider, Hephaestus starts, but nobody can sign in.
A first install stops when it has no GitHub OAuth client ID or client secret.
A repeat run stops when the kept `site.yaml` names no GitHub or GitLab login provider.
Set `config.github.oauthClientId` in `site.yaml`, as [First steps § 1](./first-steps#1-configure-a-login-provider) describes, and run the installer again.

The installer waits up to 10 minutes for the `Let's Encrypt` certificate.
`Let's Encrypt` must reach the host on port 80 from the internet.
If the host has no such path, stop the installer, and run it again with your own certificate in `--certificates`.

On a repeat run, select certificates explicitly:

- For standard apex certificates, pass `--standard-certificates`. Omit `--staging` to use the production issuer.
- For standard workspace certificates, repeat `--workspace-subdomains --dns01-issuer NAME`.
  `NAME` is a ClusterIssuer that you own and install first, with your DNS provider and its credential. [Workspace subdomains](./workspace-subdomains#helm-chart-and-k3s) describes it.
- For custom certificates, including workspace hosts, pass the intended manifest with `--certificates FILE`.
  Keep its issuer and every required apex and wildcard name.

This check prevents a repeat run from replacing wildcard TLS with an implicit apex-only certificate.
`--render-certificates` previews the selected manifest without host changes and needs no repeat-install choice.
`--render-platform` prints the `k3s-platform.yaml` that the run would install, and stops without host changes.
`--render-site` prints the `site.yaml` that a first install would write, and stops without host changes.
It leaves out the Kubernetes API endpoint, which only the running cluster knows.

The installed manifest is root-only, mode 0600.
Before using it with `--certificates`, copy it to a file that you own:

```bash
sudo install -m 0600 -o "$(id -u)" -g "$(id -g)" \
  /var/lib/rancher/k3s/server/manifests/hephaestus-certificates.yaml ./saved-certificates.yaml
```

Then pass `--certificates ./saved-certificates.yaml`. This preserves the existing issuer, including a staging issuer.

A new `GITHUB_OAUTH_CLIENT_SECRET` updates the Kubernetes Secret and restarts the server to seed a missing login provider.
An existing login provider keeps its database value, including after a restart.
Use [Login provider secrets](./secret-custody#login-provider-secrets) to rotate the credential used for sign-in.

## Check that it worked

1. Open `https://<host>`.
   The browser trusts the certificate, unless you used `--staging`.
   The login page shows **Continue with GitHub**.
2. Run the [sandbox isolation gate](./sandboxes#sandbox-isolation-gate) for each sandbox template.
   Connect no repository until the gate passes for each template.
   The installer does not run the gate.
   Run it on the host, because port 6443 of the Kubernetes API stays closed to the internet:

   1. Get the checkout of the release tag:

      ```bash
      git clone --depth 1 --branch "v$VERSION" https://github.com/hephaestus-build/Hephaestus.git ~/hephaestus-src
      ```

   2. Install Node.js at the version that `jq -r .engines.node ~/hephaestus-src/package.json` prints, for example from [nodejs.org](https://nodejs.org/en/download).
   3. Select the kubeconfig of the installer: `export KUBECONFIG=~/hephaestus/kubeconfig`.
   4. Run the gate from `~/hephaestus-src`, as [Run the gate](./sandboxes#run-the-gate) describes.

Then continue with [First steps § 3. Sign in](./first-steps#3-sign-in).
The installer asked for the values of First steps § 1 and § 2.

## Extra host names

`--extra-host NAME` adds one more host name to the Gateway `traefik/traefik-gateway`.
Use it for an alias or for a redirect from an old host name. Repeat it for each name.

For each name, the installer adds an HTTPS listener whose name and host name are `NAME`.
cert-manager issues the certificate `traefik/NAME-tls` through the HTTP-01 challenge of the `gateway` issuer.
It uses that issuer also when the main host uses a DNS-01 issuer.
The redirect from HTTP to HTTPS covers each name.
The installer waits up to 10 minutes for each certificate.

The route of the chart serves only `host`.
To serve an extra name, attach your own HTTPRoute to its listener.
`--extra-host` does not work with `--certificates`. Put the certificates of extra names in that file yourself.
With `--workspace-subdomains`, an extra name cannot be a workspace host of `host`.

1. Point a DNS record of the name to this host. The installer stops when the name does not resolve.
2. Give `--extra-host NAME` on each run of the installer, also with `--platform-only` and `--check-platform`.
3. Attach your route to the listener with `sectionName: NAME`. This example redirects an old host name to `host`:

   ```yaml
   apiVersion: gateway.networking.k8s.io/v1
   kind: HTTPRoute
   metadata:
     name: old-host-redirect
     namespace: traefik
   spec:
     parentRefs:
       - name: traefik-gateway
         namespace: traefik
         sectionName: old.example.com
     hostnames:
       - old.example.com
     rules:
       - filters:
           - type: RequestRedirect
             requestRedirect:
               hostname: hephaestus.example.com
               statusCode: 301
   ```

To remove a name, do these steps:

1. Delete your route of the name.
2. Run the installer again without `--extra-host NAME`.
3. Delete the certificate and the Secret of the name:

   ```bash
   kubectl --namespace traefik delete certificate NAME-tls --ignore-not-found
   kubectl --namespace traefik delete secret NAME-tls --ignore-not-found
   ```

## Certificate renewal

cert-manager renews the Gateway certificate and the PostgreSQL certificates before they expire.
Traefik reads the renewed Gateway certificate without a restart.

The one-host manifest requests a ten-year lifetime for the PostgreSQL server certificate and its CA.
The CA expiry date limits trust even when a server certificate expires later.
Read that date with `kubectl --namespace hephaestus get certificate hephaestus-postgresql-ca -o jsonpath='{.status.notAfter}'`.
Use [Database maintenance](./maintenance#database-maintenance) when you replace the CA.
After applying an updated server certificate, restart PostgreSQL as [TLS certificates](./secret-custody#tls-certificates) describes.

## Upgrade the host

1. Keep the current release directory, including its chart, profile, `k3s-values.yaml`, and `site.yaml`.
   Get the target release files into a separate directory, as [Get the release files](#1-get-the-release-files) describes.
   Copy your protected `site.yaml` into that directory and apply the target release's required value changes.
2. Read the migration instructions for every release between the installed release and the target.
3. Use [Stopped schema upgrades](./upgrade#stopped-schema-upgrades) with the current and target release charts.
   Use the current directory's chart and values in phase 1.
   Use the target directory's files in phases 2 and 3.
   Include `k3s-values.yaml`, `site.yaml`, and the same profile overlays as the installation in each phase.
   Export `KUBECONFIG` to the current installation's saved kubeconfig before you use Helm or kubectl.

   Before phase 2, run the target release's `hephaestus-keys.sh` and complete any platform prerequisites in its migration instructions.
   Export the keys again with `--export-keys` and keep that copy with the restore point.
   The installer refuses a release change: it cannot verify your restore point or complete the stopped phases for you.
4. After the new release passes its checks, set `VERSION` to that completed target release.
   Run its installer with `--version "$VERSION"`, the installation's `--host` and `--email`, and its feature flags.
   Select certificates explicitly, as [Run the installer](#2-run-the-installer) describes.
   Keep the target `site.yaml` as prepared in step 1.

   Keep deployment automation paused until this run finishes.
   It updates the k3s pin and platform files and reapplies the installed release.
   k3s applies a changed `k3s-agent-sandbox.yaml`, which upgrades the agent-sandbox controller and its resource definitions.
   The installer waits until k3s has applied the file and the new controller is ready.
   Automatic rollback in that installer can return only to the same release.
   Do not run it while recovering a failed migration.

## Install only the platform

`k3s-install.sh --platform-only` installs the pinned k3s and the platform files, and no release.
Use it on a host where another tool applies the chart, for example [pull-based deployment](./pull-based-deployment#follow-a-channel-with-the-chart).
It does not read or change the release Secret, `site.yaml`, or the Helm release.
It does the host checks of [What the installer checks](#what-the-installer-checks).
It creates the namespaces `hephaestus` and `hephaestus-sandboxes` with the `restricted` Pod security level, as a full install does.
It waits until the agent-sandbox controller is ready and cert-manager has issued the certificates.

`k3s-install.sh --check-platform` changes nothing.
It compares each installed platform file byte for byte with its source, and the installed k3s with the pin.
It names each file that differs or is missing, and each flag of the k3s service, because a flag overrides `config.yaml`.
It stops with an error when one item differs, and when it cannot read the `ExecStart` of the k3s service.
It renders `k3s-platform.yaml` and the certificates file with your options, as the installer does.
Other files, such as your own manifests in the k3s manifests directory, are yours, and the check does not read them.

The check shows the keys of `/etc/rancher/k3s/config.yaml.d/50-node.yaml`, which is yours. A key other than `node-ip` and `node-name` in it is a difference.
With `--node-ip` or `--node-name`, it compares that file with these options.
Each other `*.yaml` or `*.yml` file in `config.yaml.d/` is a difference, because it overrides `config.yaml`.

The source of the platform files is the directory that holds `k3s-install.sh`.
The installer puts these files on the host as root, so run it only from a verified source that no other user can write.
Use the source of the channel that the host follows:

- A release channel: the verified bundle of the release, as [Get the release files](#1-get-the-release-files) describes.
- The commit channel: the `deploy/platform` directory of the commit that the host applies, in `/var/lib/hephaestus/releases/<commit>/`.

1. Pause deployment automation, as [Operating it](./pull-based-deployment#operating-it) describes.
2. Compare the host with the source. Use the options that the host needs, and select the certificates explicitly:

   ```bash
   sh k3s-install.sh --check-platform --host hephaestus.example.com --email ops@example.com --standard-certificates
   ```

3. If the check names a difference, install the platform with the same options:

   ```bash
   sh k3s-install.sh --platform-only --host hephaestus.example.com --email ops@example.com --standard-certificates
   ```

4. Run the check again. It must report that every platform file and the k3s release match.
5. Run `sh k3s-install.sh --verify-kubelet` to check the effective kubelet policy.
6. Resume deployment automation.

A host that the installer did not set up converges with the same steps.
Before step 3, examine these points:

- Flags of the k3s service override `/etc/rancher/k3s/config.yaml`.
  To remove them, run the pinned k3s install script again with an empty `INSTALL_K3S_EXEC`.
  Give a node address or name with `--node-ip` and `--node-name` instead.
  The installer stops when the effective kubelet policy differs from `k3s-kubelet.yaml`.
- k3s takes over a `HelmChart` with the name `kube-system/traefik` or `kube-system/cert-manager`.
  The Helm controller then upgrades that release with the shipped values.
  On one host, the Traefik restart drops connections for some seconds.
- cert-manager issues the certificates of the installed certificates file into Secrets with the same names.
  A new PostgreSQL CA or server certificate needs the restarts that [TLS certificates](./secret-custody#tls-certificates) describes.

## Undo

:::caution[CAUTION: Host data loss]
The k3s uninstall script deletes the local cluster datastore and local persistent volume data.
This includes PostgreSQL data on a default one-host installation.
Verify a recovery set first if you must keep the data.
:::

1. Complete [Decommission](./production-operations-runbook#decommission), including provider disconnects and any required final backup.
2. Disable the reconciler timer, if installed, as [Operating it](./pull-based-deployment#operating-it) describes.
3. Remove the release, as [Undo](./kubernetes#undo) describes.
4. On the dedicated k3s server, run `sudo /usr/local/bin/k3s-uninstall.sh`.
   Then remove gVisor: `sudo rm -rf /usr/local/bin/runsc /usr/local/bin/containerd-shim-runsc-v1 /usr/local/bin/gvisor-bin`.
5. Remove the reconciler's systemd units and `/var/lib/hephaestus` state if the host will be reused.
6. Remove any separately configured storage only when its retention requirements permit deletion.

Use this procedure only for a host dedicated to this installation.
For a shared cluster, remove only the application release and its resources.
The [k3s uninstall reference](https://docs.k3s.io/installation/uninstall) owns the platform deletion behavior.

## The platform files

Each release publishes the files of the one-host install as one signed bundle, `hephaestus-k3s-<version>.tar.gz`, with its signature `hephaestus-k3s-<version>.tar.gz.sigstore.json`.
[Get the release files](#1-get-the-release-files) verifies and unpacks it.
k3s applies each file in `/var/lib/rancher/k3s/server/manifests/`, and it applies a file again every 15 seconds until the apply succeeds.

| File | What it is |
| --- | --- |
| `k3s-install.sh` | The installer. It is a POSIX shell script, and it needs no Node.js and no checkout of the repository. |
| `hephaestus-keys.sh` | Creates or completes the release Secret. The installer runs it. [Create the Secret](./kubernetes#create-the-secret) uses it on another cluster. |
| `hephaestus-cluster-admin.yaml` | The admin bundle of a [managed cluster](./managed-cluster). The one-host install does not use it. |
| `k3s-wait-condition.sh` | Waits until a cert-manager resource reports a condition as true, also while the resource has no status yet. The installer runs it. |
| `k3s-platform.yaml` | It goes to the manifests directory. Its `pin: k3s` line sets the k3s release. Two `HelmChart` objects pin Traefik and cert-manager by version, and each image by digest. Traefik is the Gateway controller on ports 80 and 443. It creates the Gateway `traefik/traefik-gateway` and the redirect from HTTP to HTTPS. cert-manager has Gateway API support. The installer adds a listener for each [extra host name](#extra-host-names). The Gateway API resource definitions come from the `gateway-api-crd` chart of k3s. cert-manager and the Gateway start some minutes after k3s. |
| `k3s-agent-sandbox.yaml` | It goes to the manifests directory. The [agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox) controller, its extensions and its resource definitions, in the namespace `agent-sandbox-system` at the priority `system-cluster-critical`. Its `pin: agent-sandbox` line sets the release, and the file pins the image by digest. The installer waits until the controller is ready. |
| `k3s-containerd.toml.tmpl` | The containerd configuration of k3s, with [gVisor](https://gvisor.dev) as the `runsc` runtime. Its `pin: gvisor` line sets the gVisor release and the SHA-512 of its archive for each architecture. The installer downloads that archive from `storage.googleapis.com`, checks it, and installs `runsc`, its containerd shim and the `gvisor-bin` directory in `/usr/local/bin`. It restarts k3s when this file changes. |
| `k3s-runtimeclass.yaml` | It goes to the manifests directory. The RuntimeClass `gvisor`, which gives each sandbox its own kernel ([Sandbox templates](./sandboxes#sandbox-templates)). |
| `k3s-certificates.yaml` | The `Let's Encrypt` issuer, the Gateway certificate, and the PostgreSQL CA and server certificate. The installer writes your host name and address into it. |
| `k3s-config.yaml` | Becomes `/etc/rancher/k3s/config.yaml`: the kubelet configuration file, no bundled Traefik, no ServiceLB, and no metrics server. |
| `k3s-kubelet.yaml` | At most 4096 processes for each Pod. 5 minutes for the Pods to stop at a host shutdown, of which Traefik and CoreDNS get the last minute. A review that a shutdown stops runs again after the start. 1 GiB of memory and 250m CPU reserved for k3s, and 512 MiB and 250m CPU for the host services. Eviction when less than 256 MiB of memory is available. Container logs rotate at 10 MiB, with at most five files. Image garbage collection from 75 % to 65 % disk use, never of the image of a running container. The installer restarts k3s when this file changes, then stops if the effective kubelet configuration or the Node allocatable differs. `sh k3s-install.sh --verify-kubelet` repeats that check. |
| `k3s-logind.conf` | Raises the logind limit of Ubuntu for a held shutdown from 30 seconds to 5 minutes, for the kubelet. |
| `k3s-journald.conf` | Host journal limits: 512 MiB on disk, 128 MiB in memory, and seven days. |
| `k3s-values.yaml` | The chart values of every one-host install. They name the Gateway, the Traefik Pods, the Secrets, the sandbox namespace, and the gVisor runtime (`sandbox.templates.runtimeClassName: gvisor`). |

Host swap must stay off, also at boot. The installer refuses active swap, and the kubelet refuses to start with swap.

### Node address and name

k3s reads each `*.yaml` and `*.yml` file in `/etc/rancher/k3s/config.yaml.d/`, and such a file overrides `config.yaml`.
For the node address and the node name of k3s, give `--node-ip ADDRESS` and `--node-name NAME`.
The installer writes these options only to `config.yaml.d/50-node.yaml`, and it restarts k3s when that file changes.
Give them on each run. A run without them keeps the file.

The node name is fixed for the life of the cluster.
A new name registers a second node, and the local volumes stay with the old node.
The installer stops when a run would change the name of the running node.
It also stops when `--node-ip` names an address that the host does not have.

A new node address also moves the Kubernetes API endpoint.
After you change `--node-ip`, set `networkPolicy.kubernetesApi.cidr` in `site.yaml` to the new address.
To remove `50-node.yaml`, do these steps:

1. Delete the file.
2. Run the installer again. It stops if the file named a node other than the host name, because the node name would change.
3. Restart k3s with `sudo systemctl restart k3s`. k3s keeps the old node address until it restarts.

## Known limits of the one-host platform

- Traefik sets no request body limit on this platform. The server refuses a webhook body over 25 MiB itself. The default idle timeout of Traefik is 180 seconds.
- The k3s default Pod network is `10.42.0.0/16`. `trustedProxies` in `k3s-values.yaml` matches it. If you change the network of k3s, change `trustedProxies` too.
- The platform issues a separate backup client certificate, `hephaestus-postgresql-backup-tls`. Enable backups after you configure the off-host repository ([Backup and restore](./backup-restore#back-up-the-database)).
- If the model endpoint resolves to this host's own Gateway, set `networkPolicy.modelGatewayPorts: [8443, 8000]` in `site.yaml`, as [Network policy](./helm-chart#network-policy) describes.

## Troubleshooting

### A first install fails

The installer does not use automatic rollback on a first install.
When the install fails, the installer stops and links to [A first install fails](./kubernetes#a-first-install-fails).

1. Select the kubeconfig in the release directory of the installer. For the default directory:

   ```bash
   export KUBECONFIG="$HOME/hephaestus/kubeconfig"
   ```

   The installer exports this path only inside its own process.
   If the file does not exist, read the saved installer output and the state of k3s on the host.
   Do not run the cluster checks with another kubeconfig.

2. Do the checks of [A first install fails](./kubernetes#a-first-install-fails).
3. Run the installer again with the same release. It tries the failed install again.
