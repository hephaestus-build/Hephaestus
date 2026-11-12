---
title: Connect to services on a private network
description: Let the server and the worker reach a model endpoint or a Git provider on a private network or behind a private CA.
page_type: how-to
---

Network policy and the application both refuse private addresses by default.
Use these procedures when a model endpoint or a Git provider is on your own network, or uses a certificate from a private CA.
[Network policy](./helm-chart#network-policy) lists the paths that the chart opens.

## Reach a model endpoint on the cluster's own ingress

The worker reaches AI providers through `networkPolicy.externalEgress`, which leaves out private address ranges.
The host name of a model endpoint can resolve to the cluster's own ingress.
Then the connection ends at an ingress Pod with a private address, and the policy refuses it.
An example is a model proxy that the same cluster serves.

Add a NetworkPolicy that lets the worker Pods reach the ingress Pods.
NetworkPolicies add up, so this policy does not change the chart's policies.
Use the container ports of the ingress Pods, not the Service ports: the policy applies after address translation.
For Traefik in the namespace `traefik`, with container ports 8000 and 8443:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: worker-to-ingress
  namespace: hephaestus
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: hephaestus
      app.kubernetes.io/component: worker
  policyTypes: ["Egress"]
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: traefik
          podSelector:
            matchLabels:
              app.kubernetes.io/name: traefik
      ports:
        - { port: 8443, protocol: TCP }
        - { port: 8000, protocol: TCP }
```

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

The server and the worker add this CA at start, and the public CAs stay trusted.
After you replace the CA, restart the server and the worker.
This option does not change what GitLab trusts. If Hephaestus uses a private certificate, add its CA to the trusted certificates of your GitLab, so that GitLab can deliver webhooks.

The server also uses this trust bundle for an external broker whose `nats.external.url` uses `tls://`.
Include the broker's CA in the bundle if it uses a private CA.

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

The list has the same rules as `config.llmPrivateHosts`: [Connect an AI provider](./ai-providers#model-endpoints-on-your-own-network) describes them.
A host name entry trusts the DNS of that name, so list only a name whose DNS you control.
A listed host name can also use a private TLD such as `.internal`.

Each GitLab API call and sign-in request checks the address when it connects.
Thus, a name that later resolves elsewhere gets no access.
Loopback, link-local, and known cloud metadata addresses stay refused, and so do the addresses of the Pod itself.
A range that covers the address of the Pod stops the server or the worker at startup.

These requests never follow a redirect.
Git fetches check the connected address before they send the request, and they follow no redirect either.
A refused address gets a connection attempt, but no request.
Git fetches through an HTTP proxy are refused, because the proxy chooses the address.
Git fetches with certificate verification turned off are refused too.

If you remove a host from the list, sign-in and API calls to it stop after the next restart.

If your GitLab runs in this cluster, an `ipBlock` does not reliably match its Pod addresses.
Use a policy with a `podSelector` for the server and the worker instead, as [Reach a model endpoint on the cluster's own ingress](#reach-a-model-endpoint-on-the-clusters-own-ingress) shows.

If your GitLab uses a certificate from a private CA, also follow [A Git provider with a private CA](#trust-a-git-providers-private-ca).

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
[Connect an AI provider](./ai-providers#model-endpoints-on-your-own-network) tells what the application allows.

A `cidr` must be in `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `100.64.0.0/10`, or `fc00::/7`.
`ports` must name at least one port.
Thus, no item can open the link-local range of the cloud metadata service.
Use `modelEndpoints` only for addresses outside the cluster.

Do not list a range in `config.llmPrivateHosts` that covers the Pod or Service range of the cluster.
A range that covers the Pod's own address stops the server or the worker at startup, but the server cannot detect the Service range.
A NetworkPolicy `ipBlock` does not reliably match Pod addresses.
For an endpoint in this cluster, use `networkPolicy.workerGatewayPorts` or a policy with a `podSelector`, as [Reach a model endpoint on the cluster's own ingress](#reach-a-model-endpoint-on-the-clusters-own-ingress) shows.

## Check that it worked

1. For a model endpoint, test the connection of the AI provider, as [Connect an AI provider](./ai-providers) describes.
2. For a Git provider, sign in with it, and let Hephaestus sync one repository.
