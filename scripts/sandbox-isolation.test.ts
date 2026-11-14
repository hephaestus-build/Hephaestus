import assert from "node:assert/strict";
import { test } from "node:test";

import {
	apiEndpoints,
	gateTemplate,
	HOLD,
	type Inside,
	insideFailures,
	namedTarget,
	namespacedName,
	parseAddress,
	podSecurityFailures,
	specFailures,
	type Target,
	unrestrictedPod,
} from "./sandbox-isolation.ts";

const pod = (spec: Record<string, unknown>) => ({
	spec: {
		hostUsers: false,
		automountServiceAccountToken: false,
		enableServiceLinks: false,
		securityContext: { runAsNonRoot: true, seccompProfile: { type: "RuntimeDefault" } },
		containers: [
			{
				name: "agent",
				securityContext: {
					readOnlyRootFilesystem: true,
					allowPrivilegeEscalation: false,
					capabilities: { drop: ["ALL"] },
				},
			},
		],
		...spec,
	},
});

void test("a sandbox Pod must state every isolation setting, and only gVisor may keep host users", () => {
	assert.deepEqual(specFailures(pod({})), []);
	assert.deepEqual(specFailures(pod({ hostUsers: undefined })), ["hostUsers is not false"]);
	assert.deepEqual(specFailures(pod({ hostUsers: undefined, runtimeClassName: "gvisor" })), []);
	assert.deepEqual(specFailures(pod({ automountServiceAccountToken: undefined })), [
		"the service account token is mounted",
	]);
	assert.deepEqual(specFailures(pod({ initContainers: [{ name: "setup" }] })), ["init containers"]);
	assert.deepEqual(
		specFailures(pod({ containers: [{ name: "agent", securityContext: { privileged: true } }] })),
		[
			"the root filesystem is writable",
			"privilege escalation allowed",
			"privileged",
			"capabilities not dropped",
		],
	);
});

void test("the actual sandbox Pod must keep its image root read-only", () => {
	for (const readOnlyRootFilesystem of [undefined, false]) {
		assert.deepEqual(
			specFailures(
				pod({
					containers: [
						{
							name: "agent",
							securityContext: {
								readOnlyRootFilesystem,
								allowPrivilegeEscalation: false,
								capabilities: { drop: ["ALL"] },
							},
						},
					],
				}),
			),
			["the root filesystem is writable"],
		);
	}
});

void test("the actual sandbox Pod must use RuntimeDefault seccomp", () => {
	for (const seccompProfile of [
		undefined,
		{},
		{ type: "Localhost", localhostProfile: "legacy-filter" },
	]) {
		assert.deepEqual(
			specFailures(pod({ securityContext: { runAsNonRoot: true, seccompProfile } })),
			["seccomp is not RuntimeDefault"],
		);
	}
});

const targets: Target[] = [
	{ name: "the gateway", host: "10.0.0.1", port: 8081, reachable: true },
	{ name: "the Kubernetes API", host: "10.0.0.2", port: 443, reachable: false },
];
const inside: Inside = {
	uid: 1000,
	gid: 1000,
	token: false,
	serviceAccount: false,
	serviceHosts: ["KUBERNETES_SERVICE_HOST"],
	uidMap: "0 621871104 65536",
	noNewPrivs: true,
	capEff: "0000000000000000",
	kernel: "6.12.0",
	reached: { "the gateway": true, "the Kubernetes API": false },
	dns: false,
	publicDns: false,
	firstApi: "closed after 120 ms",
};

void test("from inside, a sandbox reaches the gateway alone, as a non-root user with no token", () => {
	assert.deepEqual(insideFailures(inside, targets, false), []);
	assert.deepEqual(
		insideFailures(
			{ ...inside, reached: { "the gateway": false, "the Kubernetes API": true }, dns: true },
			targets,
			false,
		),
		[
			"cannot reach the gateway (10.0.0.1:8081)",
			"reaches the Kubernetes API (10.0.0.2:443)",
			"the cluster DNS resolver answers",
		],
	);
	// The probe runs seconds after the start; the container's own record covers the start.
	assert.deepEqual(insideFailures({ ...inside, firstApi: "open" }, targets, false), [
		"still reached the Kubernetes API 10 seconds after its start",
	]);
	assert.deepEqual(insideFailures({ ...inside, firstApi: null, publicDns: true }, targets, false), [
		"a public DNS resolver answers",
		"recorded no check of the Kubernetes API at its start",
	]);
	assert.deepEqual(
		insideFailures({ ...inside, uid: 0, token: true, capEff: "00000000a80425fb" }, targets, false),
		["runs as 0:1000", "a Kubernetes token is mounted", "effective capabilities 00000000a80425fb"],
	);
	// Without a user namespace of its own, the container's map is the identity.
	assert.deepEqual(insideFailures({ ...inside, uidMap: "0 0 4294967295" }, targets, false), [
		"no user namespace of its own",
	]);
	assert.deepEqual(insideFailures({ ...inside, uidMap: "0 0 4294967295" }, targets, true), [
		"kernel 6.12.0 is not gVisor",
	]);
	assert.deepEqual(
		insideFailures({ ...inside, uidMap: "0 0 4294967295", kernel: "4.19.0-gvisor" }, targets, true),
		[],
	);
});

void test("the gate's template keeps the Pod spec, labels and policies and changes only the command", () => {
	const source = {
		apiVersion: "extensions.agents.x-k8s.io/v1beta1",
		kind: "SandboxTemplate",
		metadata: {
			name: "heph-sandbox-review",
			namespace: "sandboxes",
			uid: "u",
			resourceVersion: "1",
		},
		spec: {
			networkPolicyManagement: "Unmanaged",
			podTemplate: {
				metadata: { labels: { "hephaestus.build/sandbox-kind": "review" } },
				spec: { hostUsers: false, containers: [{ name: "agent", image: "agent", args: ["x"] }] },
			},
		},
	};
	const clone = gateTemplate(source, "isolation-gate-1");
	assert.deepEqual(clone.metadata, {
		name: "isolation-gate-1",
		namespace: "sandboxes",
		labels: { "hephaestus.build/isolation-gate": "true" },
	});
	assert.deepEqual(clone.spec, {
		...source.spec,
		podTemplate: {
			...source.spec.podTemplate,
			spec: {
				hostUsers: false,
				containers: [{ name: "agent", image: "agent", command: HOLD, args: [] }],
			},
		},
	});
	assert.deepEqual(source.spec.podTemplate.spec.containers[0], {
		name: "agent",
		image: "agent",
		args: ["x"],
	});
});

void test("a target is a host and a port", () => {
	assert.deepEqual(parseAddress("10.43.0.10:8081"), { host: "10.43.0.10", port: 8081 });
	assert.throws(() => parseAddress("10.43.0.10"), /not a host:port/u);
	assert.throws(() => parseAddress("host:99999"), /not a host:port/u);
});

void test("the DNS Service of a distribution is a namespace and a name", () => {
	assert.deepEqual(namespacedName("kube-system/rke2-coredns-rke2-coredns"), {
		namespace: "kube-system",
		name: "rke2-coredns-rke2-coredns",
	});
	for (const refused of ["kube-dns", "/kube-dns", "kube-system/", "a/b/c"]) {
		assert.throws(() => namespacedName(refused), /not a <namespace>\/<name>/u, refused);
	}
});

void test("the gate tries every address behind the API Service, and refuses to run without one", () => {
	assert.deepEqual(
		apiEndpoints({
			items: [
				{
					ports: [{ name: "https", port: 6443 }],
					endpoints: [{ addresses: ["192.168.5.15"] }, { addresses: ["192.168.5.16"] }],
				},
			],
		}),
		[
			{ host: "192.168.5.15", port: 6443 },
			{ host: "192.168.5.16", port: 6443 },
		],
	);
	assert.throws(() => apiEndpoints({ items: [] }), /no endpoint address/u);
	assert.throws(
		() => apiEndpoints({ items: [{ ports: [{ port: 6443 }], endpoints: [] }] }),
		/no endpoint/u,
	);
});

void test("a named target names what a sandbox must or must not reach", () => {
	assert.deepEqual(namedTarget("the server's application port=10.43.0.9:8080", false), {
		name: "the server's application port",
		host: "10.43.0.9",
		port: 8080,
		reachable: false,
	});
	assert.equal(namedTarget("a gateway=10.42.0.7:8081", true).reachable, true);
	assert.throws(() => namedTarget("10.43.0.9:8080", false), /<name>=<host>:<port>/u);
});

void test("no credential reaches a sandbox: no token, no Secret, and no Service variables but the API's", () => {
	assert.deepEqual(specFailures(pod({ enableServiceLinks: undefined })), [
		"enableServiceLinks is not false",
	]);
	assert.deepEqual(
		specFailures(
			pod({
				volumes: [
					{ name: "kube-api-access", projected: { sources: [] } },
					{ name: "keys", secret: { secretName: "keys" } },
					{ name: "scratch", emptyDir: {} },
				],
			}),
		),
		[
			"volume kube-api-access projects a token or a Secret",
			"volume keys projects a token or a Secret",
		],
	);
	assert.deepEqual(
		specFailures(
			pod({
				containers: [
					{
						name: "agent",
						securityContext: {
							readOnlyRootFilesystem: true,
							allowPrivilegeEscalation: false,
							capabilities: { drop: ["ALL"] },
						},
						env: [{ name: "KEY", valueFrom: { secretKeyRef: { name: "keys", key: "k" } } }],
					},
				],
			}),
		),
		["a variable comes from a Secret"],
	);
	assert.deepEqual(
		insideFailures(
			{
				...inside,
				serviceAccount: true,
				serviceHosts: ["KUBERNETES_SERVICE_HOST", "HEPH_SERVER_SERVICE_HOST"],
			},
			targets,
			false,
		),
		[
			"the service account directory exists",
			"Service variables beside the API's: HEPH_SERVER_SERVICE_HOST",
		],
	);
});

void test("the sandbox namespace must refuse, by Pod Security admission, a Pod that only the Baseline level admits", () => {
	const unrestricted = unrestrictedPod("sandboxes", "isolation-restricted-1", "agent");
	// The Pod breaks the Restricted rules that Baseline does not have.
	const broken = specFailures(unrestricted);
	for (const rule of [
		"runAsNonRoot is not set",
		"seccomp is not RuntimeDefault",
		"privilege escalation allowed",
		"capabilities not dropped",
	]) {
		assert.ok(broken.includes(rule), rule);
	}
	const refused =
		'Error from server (Forbidden): error when creating "STDIN": pods "isolation-restricted-1" is forbidden: violates PodSecurity "restricted:latest": allowPrivilegeEscalation != false';
	assert.deepEqual(podSecurityFailures(refused, "restricted"), []);
	// A cluster default can enforce the level without the label; the refusal is the proof.
	assert.deepEqual(podSecurityFailures(refused, undefined), []);
	assert.deepEqual(podSecurityFailures(null, "baseline"), [
		"admits a Pod that the Restricted Pod Security Standard refuses (pod-security.kubernetes.io/enforce=baseline)",
	]);
	assert.deepEqual(podSecurityFailures(null, undefined), [
		"admits a Pod that the Restricted Pod Security Standard refuses (no pod-security.kubernetes.io/enforce label)",
	]);
	// A warning of the warn level and a refusal by a quota prove nothing about enforcement.
	assert.deepEqual(
		podSecurityFailures(
			'Warning: would violate PodSecurity "restricted:latest": runAsNonRoot != true\nError from server (Forbidden): error when creating "STDIN": pods "isolation-restricted-1" is forbidden: failed quota: disk: must specify requests.cpu for: baseline\n',
			undefined,
		),
		[
			'refused a Pod that the Restricted Pod Security Standard refuses, but not by Pod Security admission (no pod-security.kubernetes.io/enforce label): Error from server (Forbidden): error when creating "STDIN": pods "isolation-restricted-1" is forbidden: failed quota: disk: must specify requests.cpu for: baseline',
		],
	);
});
