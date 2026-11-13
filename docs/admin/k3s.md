---
title: Install on one host
description: Install Hephaestus on one Linux host with k3s and the signed Helm chart, with one installer command.
page_type: how-to
---

This procedure installs Hephaestus on one Linux host with [k3s](https://docs.k3s.io/) and the Helm chart.
One command, `k3s-install.sh`, does the install.
It checks the host, installs the pinned k3s and the platform, and keeps the release Secret.
Then it verifies the release chart and installs it.
It uses the `small.yaml` profile, the bundled PostgreSQL, and one worker that runs reviews.

A second run of the same release changes nothing that the first run made.
A run with the files of a newer release upgrades the host.

## Before you start

- A host of 4 vCPU, at least 16 GiB of memory, and 150 GiB of disk, as [Profiles](./helm-chart#profiles) gives for `small.yaml`.
  Each worker upgrade pauses reviews and conversations.
- Ubuntu 24.04 with its 6.8 GA kernel on `amd64` or `arm64`. Other OS or kernel families require explicit qualification with `--allow-unqualified-host`.
- A DNS A record for your host name that points to the public address of the host.
- Ports 80 and 443 open from the internet, for `Let's Encrypt`, OAuth callbacks, and provider webhooks. Keep port 6443, the Kubernetes API, and port 10250, the kubelet, closed to the internet.
- If `ufw` is active, rules that let in ports 80 and 443 and the k3s networks: `sudo ufw allow 80,443/tcp`, `sudo ufw allow from 10.42.0.0/16`, and `sudo ufw allow from 10.43.0.0/16`.
- Outbound HTTPS to the destinations in [Network egress](./network-egress#one-host-on-k3s).
- `curl`, `jq`, `openssl`, `python3`, Cosign, and the Helm CLI, as [Verify the release chart](./kubernetes#verify-the-release-chart) describes. Ubuntu includes `python3`.
- A clock that NTP keeps synchronized, as [Clocks](./helm-chart#clocks) describes.
- A GitHub OAuth app for sign-in, as [First steps § 1](./first-steps#1-configure-a-login-provider) describes. Its callback URL is `https://<host>/api/login/oauth2/code/github`.

## What the installer checks

The installer stops before it changes the host when one of these checks fails. Each message tells you what to change.

| Check | Condition |
| --- | --- |
| Input | The host name, the address, the OAuth client ID, and the admin list have a strict format. |
| Size | The host has 4 CPUs and at least 15 GiB of memory. The installer uses `small.yaml` for every supported host. |
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

   ```bash
   VERSION=1.0.0-rc.8   # the release, without the leading "v"
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

   For a first try, add `--staging`: the `Let's Encrypt` staging service has higher rate limits, and no browser trusts its certificate.
   Later, run the installer again without `--staging`, with the explicit certificate choice described below.
   Delete the Secret `traefik/hephaestus-tls` to get a trusted certificate.
   [First steps § 2](./first-steps#2-name-the-first-admin) explains the admin list.

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
Thus, a first install stops when it has no GitHub OAuth client ID or client secret.
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
2. Sign in as the bootstrap admin.

Then continue with [First steps § 4](./first-steps#4-connect-repository-data).

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

## Keep old images off the disk

Each upgrade pulls the images of the new release, and the chart removes no image.
The kubelet removes unused images when the image filesystem is fuller than `imageGCHighThresholdPercent` (85 % by default), until it is below `imageGCLowThresholdPercent` (80 %).
On one host, set lower thresholds and a maximum age, so that the images of old releases go before the disk fills.
On k3s, put a KubeletConfiguration file whose name ends in `.conf` into `/var/lib/rancher/k3s/agent/etc/kubelet.conf.d/`, then run `sudo systemctl restart k3s`:

```yaml
apiVersion: kubelet.config.k8s.io/v1beta1
kind: KubeletConfiguration
imageGCHighThresholdPercent: 75
imageGCLowThresholdPercent: 65
imageMaximumGCAge: 168h
```

The thresholds count the whole filesystem, also data that is not an image.
If other data keeps the disk above the low threshold, the kubelet logs that image garbage collection failed, and it removes every unused image.
The kubelet never removes an image that a Pod uses.

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
The bundle holds these files:

| File | What it is |
| --- | --- |
| `k3s-install.sh` | The installer. It is a POSIX shell script, and it needs no Node.js and no checkout of the repository. |
| `hephaestus-keys.sh` | Creates or completes the release Secret. The installer runs it. [Create the Secret](./kubernetes#create-the-secret) uses it on another cluster. |
| `k3s-wait-condition.sh` | Waits until a cert-manager resource reports a condition as true, also while the resource has no status yet. The installer runs it. |
| `k3s-platform.yaml` | The pins and the platform add-ons. It goes to the k3s manifests directory. The installer adds a listener for each [extra host name](#extra-host-names). |
| `k3s-agent-sandbox.yaml` | The agent-sandbox controller and its resource definitions, with its pin. It goes to the k3s manifests directory. |
| `k3s-containerd.toml.tmpl` | The containerd configuration of k3s with gVisor as the `runsc` runtime, and the pin of the gVisor release. |
| `k3s-runtimeclass.yaml` | The RuntimeClass `gvisor` of the sandbox templates. It goes to the k3s manifests directory. |
| `k3s-certificates.yaml` | The `Let's Encrypt` issuer, the Gateway certificate, and the PostgreSQL CA and server certificate. The installer writes your host name and address into it. |
| `k3s-config.yaml` | The k3s configuration: the kubelet configuration file, no bundled Traefik, no ServiceLB, and no metrics server. |
| `k3s-kubelet.yaml` | A limit of 4096 processes for each Pod, and the shutdown grace period. Also the memory and CPU reserved for k3s and the host, and the eviction thresholds. |
| `k3s-logind.conf` | How long logind lets the kubelet hold a shutdown. |
| `k3s-journald.conf` | Host journal limits: 512 MiB on disk, 128 MiB in memory, and seven days. |
| `k3s-values.yaml` | The chart values of every one-host install: the Gateway, the Traefik Pods, the Secret names, the sandbox namespace, and reviews with repository checkouts. |

`k3s-platform.yaml` contains these add-ons:

- Traefik as the Gateway controller on the host's ports 80 and 443. Its chart creates the Gateway `traefik/traefik-gateway` and a redirect from HTTP to HTTPS.
- cert-manager, with Gateway API support.

The `pin: k3s` line of `k3s-platform.yaml` sets the k3s release, and its two `HelmChart` objects set the versions of Traefik and cert-manager.
The file pins each chart by version and each image by digest.

The Gateway API resource definitions come from the `gateway-api-crd` chart that k3s includes, so no second owner installs them.
k3s deploys each file in `/var/lib/rancher/k3s/server/manifests/`, and it applies a file again every 15 seconds until the apply succeeds.
Thus, cert-manager and the Gateway start some minutes after k3s.

`k3s-agent-sandbox.yaml` contains the [agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox) controller, with its extensions.
The controller creates sandbox Pods from `SandboxTemplate` and `SandboxClaim` objects.
It runs in the namespace `agent-sandbox-system`, at the priority `system-cluster-critical`, as Traefik and cert-manager do.
Its `pin: agent-sandbox` line sets the release, and the file pins the controller image by digest.
The installer waits until the controller is ready.

The sandbox templates of the one-host platform use [gVisor](https://gvisor.dev), which gives each sandbox its own kernel ([Sandbox templates](./sandboxes#sandbox-templates)).
The `pin: gvisor` line of `k3s-containerd.toml.tmpl` sets the gVisor release and the SHA-512 of its archive for each architecture.
The installer downloads that archive from `storage.googleapis.com` and checks it, so the host must reach that address.
It installs `runsc`, its containerd shim and the `gvisor-bin` directory in `/usr/local/bin`.
It restarts k3s when `k3s-containerd.toml.tmpl` changes: at the first install with gVisor, and at each new gVisor pin.
`k3s-values.yaml` sets `sandbox.templates.runtimeClassName: gvisor`.

`k3s-config.yaml` becomes `/etc/rancher/k3s/config.yaml`.
k3s then reads each `*.yaml` and `*.yml` file in `/etc/rancher/k3s/config.yaml.d/`, and such a file overrides `config.yaml`.
For the node address and the node name of k3s, give `--node-ip ADDRESS` and `--node-name NAME`.
The installer writes these options only to `config.yaml.d/50-node.yaml`, and it restarts k3s when that file changes.
Give them on each run. A run without them keeps the file.

The node name is fixed for the life of the cluster.
A new name registers a second node, and the local volumes stay with the old node.
Thus, the installer stops when a run would change the name of the running node.
It also stops when `--node-ip` names an address that the host does not have.

A new node address also moves the Kubernetes API endpoint.
After you change `--node-ip`, set `networkPolicy.kubernetesApi.cidr` in `site.yaml` or in your chart values to the new address.
To remove `50-node.yaml`, do these steps:

1. Delete the file.
2. Run the installer again. It stops if the file named a node other than the host name, because the node name would change.
3. Restart k3s with `sudo systemctl restart k3s`. k3s keeps the old node address until it restarts.

The installer does not run the application itself as a k3s `HelmChart`.
The k3s Helm controller does not wait for the migration Job, and it does not roll back a failed upgrade.

The limit of 4096 processes applies to each Pod on the host, the server and the worker included.
Size the Pod PID limit for every runtime role and its subprocesses.
CI's clusters run with the same 4096.

When the host shuts down, the kubelet stops the Pods first.
The configured budget is 5 minutes: 4 minutes for the application Pods, then 1 minute for Traefik and CoreDNS.
PostgreSQL stops within its termination grace period of 120 seconds, inside the first 4 minutes.
Ubuntu sets the logind limit to 30 seconds, so `k3s-logind.conf` raises it to 5 minutes.
At the end of the budget, a worker stops the reviews that it runs. They run again after the start.

Pods cannot request all of the host's memory.
`k3s-kubelet.yaml` reserves 1 GiB and 250m CPU for k3s, which runs the API server, the kubelet and containerd in one process of about 1 GB.
It reserves 512 MiB and 250m CPU for the host's own services.
The kubelet evicts Pods when less than 256 MiB of memory is available, before the kernel's out-of-memory killer stops a process on the host.

For a Node API capacity of exactly 16 GiB and 4 vCPU, Pods get 14 592 MiB (14.25 GiB) and 3.5 CPUs.
At the recorded capacity of 16 372 036 KiB used in the profile table, Pods get about 13.9 GiB.

The installer restarts k3s when this file changes.
After the node becomes ready, it reads the kubelet configuration endpoint and Node allocatable.
It stops before the application upgrade if the configured policy is absent or overridden.

Allocatable must equal measured capacity less the reservations and the hard memory floor.
The soft threshold does not reduce allocatable again.
Only Pod allocatable is enforced. The reservations do not impose hard memory caps on the host or k3s system slices.

To repeat these read-only cluster checks with your current kubeconfig, run `sh k3s-install.sh --verify-kubelet`.
The command uses Helm locally to parse the policy.
It does not change the cluster.
After a restart, verification allows 31 checks with two-second pauses for the Node status and configuration endpoint to converge.

Soft eviction is disabled until stateful-service requests and admission protection are qualified.
For memory eviction, usage above requests ranks before Pod priority.
Thus, a higher priority cannot protect PostgreSQL above their requests from a review below its request.
The existing hard thresholds remain emergency recovery boundaries.
The kubelet targets an additional 256 MiB of reclaim and holds a pressure condition for five minutes.
Application admission must stop new background work before those boundaries.

Container logs rotate at 10 MiB with at most five files per container.
Image garbage collection starts at 75 percent disk use and targets 65 percent.
It does not reclaim live volumes or database files.
The installer bounds the host journal and restarts journald only when its policy changes.

Host swap must be disabled, including at boot.
The installer rejects active swap, and the kubelet requires `failSwapOn=true` and `NoSwap`.
These policies do not qualify a smaller container memory limit or a higher review count.
Before enabling either, measure synchronized node peaks, pressure, queue waits, and OOM counters under the reference load.

The verifier accepts the declared Ubuntu 24.04/kernel 6.8 GA baseline and rejects unknown OS or kernel families.
The optional `--allow-unqualified-host` flag permits an explicitly authorized rehearsal on another family.
It preserves all effective-policy and allocatable checks.
A matching host family alone does not prove installation, pressure behavior, or loaded capacity.

The current proof consists of policy/parser fixtures, read-only Node API observations, and rendered chart checks.
The release host smoke targets Ubuntu 24.04 on both architectures.
This change has not run a full host installation.

Before target-host rollout, the release lane must rehearse the exact assets and pinned k3s release on disposable Ubuntu 26.04/kernel 7 with 16 GiB.
Use the qualification override for that rehearsal and record the actual OS, kernel, CPU count, capacity, and allocatable.
Run the installer, then repeat it and verify stateful Pod identity and service continuity.
The second run must preserve unchanged configuration and avoid unnecessary k3s or journald restarts.
Verify disabled swap and its boot activation settings.

Apply pressure with PostgreSQL above their requests and review/turn Pods below theirs.
Require application backoff and recovery before hard eviction, with no database loss or OOM.
Observe container and journal rollover, image GC at 75/65, and image pull/start latency.
Retain the command logs, effective configuration, pressure/OOM counters, and synchronized footprint samples as release evidence.

## What the release workflow tests

Before it publishes a release, the release workflow runs `k3s-install.sh` on Ubuntu 24.04 on `amd64` and on `arm64`.
It runs the command twice, then an upgrade that restarts every role except PostgreSQL, whose Pods must stay.
That run differs from your install in these points:

- A local CA issues the Gateway certificate, because `Let's Encrypt` cannot reach a CI runner. CI does not test the HTTP-01 challenge of the `Let's Encrypt` issuer.
- It skips the DNS check, because a runner has no public host name.
- It skips the clock check: a hosted runner takes its time from the hypervisor and can report no NTP sync.
- A runner has 16 GiB of memory and uses the same `small.yaml` profile.
- It checks the shutdown settings that the kubelet and logind use. It does not reboot the host.

## Known limits of the one-host platform

- Traefik has no request body limit on this platform. Its default idle timeout is 180 seconds.
- The k3s default Pod network is `10.42.0.0/16`. `trustedProxies` in `k3s-values.yaml` matches it. If you change the network of k3s, change `trustedProxies` too.
- The platform issues a separate backup client certificate, `hephaestus-postgresql-backup-tls`. Enable backups after you configure the off-host repository ([Backup and restore](./backup-restore#back-up-the-database)).
- If the model endpoint resolves to this host's own Gateway, set `networkPolicy.modelGatewayPorts: [8443, 8000]` in `site.yaml`, as [Network policy](./helm-chart#network-policy) describes.
- CI's clusters run no cert-manager. Only the release workflow's install tests the cert-manager pin.

## Troubleshooting

If the installer stops during a first install, follow [A first install fails](./kubernetes#a-first-install-fails).
