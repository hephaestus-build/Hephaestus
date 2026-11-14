---
title: Connect to services on a private network
description: Let the server reach a model endpoint or a Git provider on a private network or behind a private CA.
page_type: how-to
---

Network policy and the application both refuse private addresses by default.
Use these procedures when a model endpoint or a Git provider is on your own network, or uses a certificate from a private CA.
[Network policy](./helm-chart#network-policy) lists the paths that the chart opens.

## Reach a model endpoint on the cluster's own ingress

The server reaches AI providers through `networkPolicy.externalEgress`, which leaves out private address ranges.
The host name of a model endpoint can resolve to the cluster's own Gateway, for example a model proxy that the same cluster serves.
Then the connection ends at a Gateway Pod with a private address, and the policy refuses it.

1. Set `networkPolicy.modelGatewayPorts` to the container ports of the Gateway Pods.
   Use the container ports, not the Service ports, because the policy applies after address translation.
   For the Traefik of the one-host platform:

   ```yaml title="values.yaml"
   networkPolicy:
     modelGatewayPorts: [8443, 8000]
   ```

2. Run the install command again.

The chart then lets the server reach the `networkPolicy.gateway` Pods on these ports.

## Trust a Git provider's private CA

If your GitLab uses a certificate from a private CA, Hephaestus must trust that CA.
Sign-in, API calls and Git fetches all use the same trust.

1. Create a Secret with the PEM file of the CA. The file can hold more than one certificate.

   ```bash
   kubectl -n hephaestus create secret generic corp-ca --from-file=ca.crt=./corp-ca.pem
   ```

2. Name the Secret in your values, then run the install command again.

   ```yaml title="values.yaml"
   config:
     privateCa:
       existingSecret: corp-ca
       key: ca.crt
   ```

The server adds this CA at start, and the public CAs stay trusted.
After you replace the CA, restart the server.
This option does not change what GitLab trusts. If Hephaestus uses a private certificate, add its CA to the trusted certificates of your GitLab, so that GitLab can deliver webhooks.

## Admit a Git provider on a private network

Hephaestus refuses a Git provider on a private address by default.
Keep this protection unless your GitLab runs on your own network.
To admit one, name it in both places:

```yaml title="values.yaml"
config:
  # The application: host names, IP addresses, or CIDR ranges that the GitLab server URL can use.
  scmPrivateHosts: [gitlab.corp.example]
networkPolicy:
  # The network: the address range and the ports of your GitLab.
  scmEndpoints:
    - cidr: 10.30.0.0/16
      ports: [443]
```

The list has the same rules as `config.llmPrivateHosts`, as [Rules for the private host lists](#rules-for-the-private-host-lists) describes.
A host name entry trusts the DNS of that name, so list only a name whose DNS you control.
A listed host name can also use a private TLD such as `.internal`.

Each GitLab API call and sign-in request checks the address when it connects.
A name that later resolves to another address gets no access.
Loopback, link-local, and known cloud metadata addresses stay refused, and so do the addresses of the Pod itself.
A range that covers the address of the Pod stops the server at startup.

These requests never follow a redirect.
Git fetches check the connected address before they send the request, and they follow no redirect either.
A refused address gets a connection attempt, but no request.
Git fetches through an HTTP proxy are refused, because the proxy chooses the address.
Git fetches with certificate verification turned off are refused too.

If you remove a host from the list, sign-in and API calls to it stop after the next restart.

If your GitLab runs in this cluster, an `ipBlock` does not reliably match its Pod addresses.
Add a NetworkPolicy that lets the server Pods reach the GitLab Pods.
NetworkPolicies add up, so this policy does not change the policies of the chart.
Use the container ports of the GitLab Pods, because the policy applies after address translation:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: server-to-gitlab
  namespace: hephaestus
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: hephaestus
      app.kubernetes.io/component: server
  policyTypes: ["Egress"]
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: <gitlab-namespace>
          podSelector:
            matchLabels:
              <label>: <value>
      ports:
        - { port: <port>, protocol: TCP }
```

Replace `<gitlab-namespace>`, `<label>: <value>`, and `<port>` with the namespace, a pod label, and the container port of the GitLab web service.

If your GitLab uses a certificate from a private CA, also follow [Trust a Git provider's private CA](#trust-a-git-providers-private-ca).

## Admit a model endpoint on a private network

A model endpoint outside the cluster can have a private address, for example a vLLM server on the LAN.
The application and the network policy both refuse private addresses by default, so you must allow the endpoint in both places:

```yaml title="values.yaml"
config:
  # The application: host names, IP addresses, or CIDR ranges that a provider base URL can use.
  llmPrivateHosts: [vllm.lab.example]
  # Only if the endpoint serves plain HTTP.
  llmPrivateHostsAllowHttp: true
networkPolicy:
  # The network: the address range and the ports of the endpoint.
  modelEndpoints:
    - cidr: 10.20.0.0/16
      ports: [8000]
```

If only `config.llmPrivateHosts` names the endpoint, a connection saves, and then the request times out at the network.
The chart cannot compare the two lists, because a host name is not an address range.
[Rules for the private host lists](#rules-for-the-private-host-lists) tells what the application allows.

A `cidr` must be in `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, or `fc00::/7`.
`ports` must name at least one port.
No item can open the link-local range of the cloud metadata service.
Use `modelEndpoints` only for addresses outside the cluster.

Do not list a range in `config.llmPrivateHosts` that covers the Pod or Service range of the cluster.
A range that covers the Pod's own address stops the server at startup, but the server cannot detect the Service range.
A NetworkPolicy `ipBlock` does not reliably match Pod addresses.

For an endpoint behind this cluster's Gateway, use `networkPolicy.modelGatewayPorts`, as [Reach a model endpoint on the cluster's own ingress](#reach-a-model-endpoint-on-the-clusters-own-ingress) shows.
For another endpoint in this cluster, such as a model server behind a ClusterIP Service, add a NetworkPolicy.
Use the example in [Admit a Git provider on a private network](#admit-a-git-provider-on-a-private-network) with the Pods and the port of the model server.
Also list the host name of the Service in `config.llmPrivateHosts`. List the name, not an address range.

## Rules for the private host lists

`config.llmPrivateHosts` and `config.scmPrivateHosts` take these entries:

| Entry | Example | Allows |
| --- | --- | --- |
| A host name | `vllm.lab.example` | This exact name, at any private address that it resolves to. Wildcards are not supported. |
| An IP address | `192.168.5.9` | Any host name that resolves to this address. |
| A CIDR range | `10.20.0.0/16`, `fd00:1::/48` | Any host name that resolves into this range. It can be no wider than `/16` for IPv4 or `/48` for IPv6. |

These rules apply:

- Only private addresses qualify: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, and `fc00::/7`.
  Loopback, link-local, and public addresses stay refused, also when a listed host name resolves to them.
- The metadata and credential ranges in the next table stay refused, although they are private.
- An entry stops startup if it is not a host name, an IP address, or a CIDR range. An IPv4 address needs all four parts.
- A range stops startup if it is outside the private ranges or wider than the limit.
  It also stops startup if it is inside a metadata range or covers an address of the server.
- A host name entry trusts the DNS of that name. Never list the name of a Hephaestus service.
- List single hosts or narrow ranges.
  Each workspace admin can reach every port of every host in a model range, if workspaces may add providers.
- The server checks the address again each time it connects, so a name that later resolves elsewhere gets no access.
- A model endpoint needs HTTPS, unless `config.llmPrivateHostsAllowHttp` is `true`. The API key then crosses your network unencrypted.
- **Allowed provider hosts** in **Instance administration → AI models** still applies on top of the list.

| Refused range | Provider |
| --- | --- |
| `100.100.100.200` | Alibaba Cloud |
| `fd00:ec2::/32` | AWS |
| `fd20:ce::/48` | Google Cloud |
| `fd00:a9fe::/32` | Akamai (Linode) |
| `fd00:42::/32` | Scaleway |
| `fd00:c1::/32` | Oracle Cloud |

## Check that it worked

1. For a model endpoint, test the connection of the AI provider, as [Connect an AI provider](./ai-providers) describes.
2. For a Git provider, sign in with it, and let Hephaestus sync one repository.
