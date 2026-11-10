import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	ADMIN_BUNDLE,
	canIArgs,
	documents,
	mergeValues,
	preflight,
	readFailure,
	type Cluster,
	type Read,
	type Resource,
	type Served,
} from "./cluster-preflight.ts";
import { readInventory } from "./commit-image-lock.ts";
import { chartImagesFrom, operatorValues } from "./kubernetes-cluster.ts";
import { asArray, asRecord, asString } from "./lib/json.ts";

const ROOT = path.join(import.meta.dirname, "..");
const CHART = path.join(ROOT, "deploy", "chart", "hephaestus");
const NAMESPACE = "hephaestus";
const SANDBOXES = "hephaestus-sandboxes";
const bundle = documents(readFileSync(ADMIN_BUNDLE, "utf8"));
const crds = bundle.filter((object) => object.kind === "CustomResourceDefinition");
const readRole = bundle.find((object) => object.kind === "ClusterRole");
assert.ok(readRole);
const nameOf = (object: Resource) => asString(asRecord(object.metadata, "metadata").name, "name");

const inventory = await readInventory(path.join(ROOT, "security", "release-images.json"));
const images = chartImagesFrom(
	inventory.images.map((image) => ({
		image,
		repository: `ghcr.io/hephaestus-build/${image}`,
		indexDigest: `sha256:${"0".repeat(64)}`,
	})),
	inventory.upstream,
);
/** An operator's values for the managed cluster, as the admin guide asks for them. */
const operator = operatorValues({
	images,
	commit: "0".repeat(40),
	api: { address: "192.168.10.11", port: 6443 },
});

/** The release as helm renders it from the cluster profile and `layers`, and the values that it merged. */
function release(...layers: Record<string, unknown>[]) {
	const directory = mkdtempSync(path.join(tmpdir(), "cluster-preflight-"));
	try {
		const files = [
			path.join(CHART, "profiles", "cluster.yaml"),
			...[operator, ...layers].map((layer, index) => {
				const file = path.join(directory, `values-${String(index)}.yaml`);
				writeFileSync(file, JSON.stringify(layer));
				return file;
			}),
		];
		const rendered = spawnSync(
			"helm",
			[
				"template",
				"heph",
				CHART,
				"--namespace",
				NAMESPACE,
				...files.flatMap((file) => ["--values", file]),
			],
			{ encoding: "utf8" },
		);
		assert.equal(rendered.status, 0, rendered.stderr);
		return { manifests: documents(rendered.stdout), values: mergeValues(files) };
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

function plural(kind: string) {
	const lower = kind.toLowerCase();
	if (lower.endsWith("y")) {
		return `${lower.slice(0, -1)}ies`;
	}
	return lower.endsWith("s") ? `${lower}es` : `${lower}s`;
}
const CLUSTER_KINDS = new Set(["PriorityClass", "ClusterRole", "ClusterRoleBinding", "Namespace"]);

interface Fake {
	/** Group versions that the cluster does not serve. */
	unserved?: string[];
	/** `verb resource namespace` triples that the operator lacks. */
	denied?: string[];
	/** Reads by `resource/name`, overriding the healthy cluster. */
	reads?: Record<string, Read>;
	/** Whether this access may impersonate the controller's ServiceAccount; by default it may. */
	impersonates?: boolean;
	/** `verb resource` pairs that the controller lacks in all namespaces. */
	controllerLacks?: string[];
}

/** What a cluster that the admins prepared answers to a read. */
function healthy(resource: string, name: string): Read {
	switch (resource) {
		case "customresourcedefinitions.apiextensions.k8s.io": {
			const crd = crds.find((entry) => nameOf(entry) === name);
			return crd === undefined ? "absent" : { found: structuredClone(crd) };
		}
		case "namespaces": {
			return {
				found: {
					metadata: { name, labels: { "pod-security.kubernetes.io/enforce": "restricted" } },
				},
			};
		}
		case "priorityclasses.scheduling.k8s.io": {
			return { found: { metadata: { name } } };
		}
		case "clusterroles.rbac.authorization.k8s.io": {
			return { found: structuredClone(readRole) };
		}
		case "clusterrolebindings.rbac.authorization.k8s.io": {
			return {
				found: {
					roleRef: { kind: "ClusterRole", name: "hephaestus-agent-sandbox-read" },
					subjects: [
						{ kind: "ServiceAccount", name: "heph-agent-sandbox-controller", namespace: NAMESPACE },
					],
				},
			};
		}
		case "gateways.gateway.networking.k8s.io": {
			return gateway("All");
		}
		case "storageclasses.storage.k8s.io": {
			return {
				found: {
					items: [
						{
							metadata: {
								name: "longhorn",
								annotations: { "storageclass.kubernetes.io/is-default-class": "true" },
							},
						},
					],
				},
			};
		}
		default: {
			return "absent";
		}
	}
}

/** A Gateway with one listener `https` that admits routes `from` these namespaces. */
function gateway(from: string): Read {
	return {
		found: { spec: { listeners: [{ name: "https", allowedRoutes: { namespaces: { from } } }] } },
	};
}

/** A cluster that serves every API of the release and the admin bundle, and lets the operator do all of it. */
function fakeCluster(manifests: readonly Resource[], fake: Fake = {}): Cluster {
	const served = new Map<string, Served[]>();
	const add = (apiVersion: string, entry: Served) => {
		const entries = served.get(apiVersion) ?? [];
		if (!entries.some((existing) => existing.kind === entry.kind)) {
			entries.push(entry);
		}
		served.set(apiVersion, entries);
	};
	for (const object of manifests) {
		const kind = asString(object.kind, "kind");
		add(asString(object.apiVersion, "apiVersion"), {
			name: plural(kind),
			kind,
			namespaced: !CLUSTER_KINDS.has(kind),
		});
	}
	for (const crd of crds) {
		const spec = asRecord(crd.spec, "spec");
		const names = asRecord(spec.names, "names");
		for (const version of asArray(spec.versions, "versions")) {
			add(
				`${asString(spec.group, "group")}/${asString(asRecord(version, "version").name, "name")}`,
				{
					name: asString(names.plural, "plural"),
					kind: asString(names.kind, "kind"),
					namespaced: true,
				},
			);
		}
	}
	return {
		discover: async (apiVersion) =>
			(fake.unserved ?? []).includes(apiVersion) ? null : (served.get(apiVersion) ?? null),
		canI: async (verb, resource, namespace) =>
			!(fake.denied ?? []).includes(`${verb} ${resource} ${String(namespace)}`),
		canIAs: async (serviceAccount, verb, resource) => {
			assert.equal(
				serviceAccount,
				`system:serviceaccount:${NAMESPACE}:heph-agent-sandbox-controller`,
			);
			if (fake.impersonates === false) {
				return null;
			}
			return !(fake.controllerLacks ?? []).includes(`${verb} ${resource}`);
		},
		read: async (resource, name) => fake.reads?.[`${resource}/${name}`] ?? healthy(resource, name),
	};
}

const run = async (fake: Fake = {}, ...layers: Record<string, unknown>[]) => {
	const { manifests, values } = release(...layers);
	return preflight(
		{ manifests, values, namespace: NAMESPACE, bundle },
		fakeCluster(manifests, fake),
	);
};
const lines = (findings: Awaited<ReturnType<typeof run>>) =>
	findings.map((finding) => `${finding.kind}: ${finding.text}`);

void test("a cluster that the admins prepared passes with no finding", async () => {
	assert.deepEqual(lines(await run()), []);
});

void test("each permission that the operator lacks is one line that names the object", async () => {
	const findings = lines(
		await run({
			denied: [`create resourcequotas ${SANDBOXES}`, `patch deployments.apps ${NAMESPACE}`],
		}),
	);
	assert.equal(findings.length, 2, findings.join("\n"));
	assert.match(
		findings[0] ?? "",
		/^missing: The operator may not create resourcequotas in the namespace hephaestus-sandboxes \(ResourceQuota /u,
	);
	assert.match(
		findings[1] ?? "",
		/^missing: The operator may not patch deployments\.apps in the namespace hephaestus \(Deployment /u,
	);
});

void test("the operator holds every permission of the worker's Role, unless it may escalate", async () => {
	// The release creates no SandboxClaim itself, so the only reason to hold this verb is the Role.
	const lacking = `create sandboxclaims.extensions.agents.x-k8s.io ${SANDBOXES}`;
	const escalate = `escalate roles.rbac.authorization.k8s.io ${SANDBOXES}`;
	const findings = lines(await run({ denied: [lacking, escalate] }));
	assert.equal(findings.length, 1, findings.join("\n"));
	assert.match(
		findings[0] ?? "",
		/may not create sandboxclaims\.extensions\.agents\.x-k8s\.io .*\(the Role heph-[a-z-]+\)/u,
	);
	assert.deepEqual(lines(await run({ denied: [lacking] })), []);
});

void test("an agent-sandbox API that the cluster does not serve names the admin bundle once per resource", async () => {
	const findings = lines(await run({ unserved: ["extensions.agents.x-k8s.io/v1beta1"] }));
	assert.ok(findings.length > 0);
	for (const line of findings) {
		assert.match(
			line,
			/^missing: The cluster does not serve \w+ in extensions\.agents\.x-k8s\.io\/v1beta1\. The admins apply the admin bundle of this release/u,
		);
	}
});

void test("a CRD that differs from the pinned one is missing, and one that cannot be read is unverified", async () => {
	const name = "sandboxtemplates.extensions.agents.x-k8s.io";
	const changed = structuredClone(crds.find((crd) => nameOf(crd) === name));
	assert.ok(changed);
	const first = asRecord(
		asArray(asRecord(changed.spec, "spec").versions, "versions")[0],
		"version",
	);
	first.schema = { openAPIV3Schema: { type: "object" } };
	const differs = lines(
		await run({
			reads: { [`customresourcedefinitions.apiextensions.k8s.io/${name}`]: { found: changed } },
		}),
	);
	assert.deepEqual(differs.length, 1, differs.join("\n"));
	assert.match(
		differs[0] ?? "",
		/^missing: The CRD sandboxtemplates\.extensions\.agents\.x-k8s\.io differs/u,
	);
	const hidden = lines(
		await run({
			reads: { [`customresourcedefinitions.apiextensions.k8s.io/${name}`]: "forbidden" },
		}),
	);
	assert.deepEqual(hidden.length, 1);
	assert.match(hidden[0] ?? "", /^unverified: The CRD sandboxtemplates/u);
});

void test("a namespace without the Restricted level, a missing PriorityClass and no default StorageClass each fail", async () => {
	const findings = lines(
		await run({
			reads: {
				[`namespaces/${SANDBOXES}`]: { found: { metadata: { labels: {} } } },
				"priorityclasses.scheduling.k8s.io/hephaestus-reviews": "absent",
				"storageclasses.storage.k8s.io/": { found: { items: [{ metadata: { name: "slow" } }] } },
			},
		}),
	);
	assert.deepEqual(findings, [
		`missing: The namespace ${SANDBOXES} lacks the label pod-security.kubernetes.io/enforce=restricted. The admins add it.`,
		"missing: The PriorityClass hephaestus-reviews does not exist. The admins apply the admin bundle of this release (hephaestus-cluster-admin.yaml).",
		"missing: The cluster has no default StorageClass. The admins mark one as default, or the operator names one in the values.",
	]);
});

void test("a Gateway listener that admits only its own namespace fails, and one that selects by label is unverified", async () => {
	const same = lines(
		await run({ reads: { "gateways.gateway.networking.k8s.io/cluster": gateway("Same") } }),
	);
	assert.equal(same.length, 1, same.join("\n"));
	assert.match(
		same[0] ?? "",
		/^missing: The listener https of the Gateway gateway\/cluster accepts routes from its own namespace only/u,
	);
	const selector = lines(
		await run({ reads: { "gateways.gateway.networking.k8s.io/cluster": gateway("Selector") } }),
	);
	assert.match(selector[0] ?? "", /^unverified: /u);
});

void test("a release that renders its own PriorityClasses fails: the profile leaves them to the admins", async () => {
	const findings = lines(
		await run({}, { priorityClasses: { create: true, roles: "", conversations: "", reviews: "" } }),
	);
	assert.ok(findings.length >= 3, findings.join("\n"));
	for (const line of findings) {
		assert.match(line, /^missing: The release renders the cluster-scoped PriorityClass /u);
	}
});

void test("the controller's read-only ClusterRole is checked as its ServiceAccount, with one forwardable line", async () => {
	const findings = lines(
		await run({ controllerLacks: ["list pods", "watch networkpolicies.networking.k8s.io"] }),
	);
	assert.deepEqual(findings, [
		"missing: The agent-sandbox controller may not list pods, watch networkpolicies.networking.k8s.io in all namespaces. The admins apply the ClusterRole hephaestus-agent-sandbox-read and its ClusterRoleBinding to the ServiceAccount hephaestus/heph-agent-sandbox-controller from the admin bundle (hephaestus-cluster-admin.yaml).",
	]);
});

void test("without impersonation, the ClusterRole must exist, match the bundle exactly and name the controller", async () => {
	const role = "clusterroles.rbac.authorization.k8s.io/hephaestus-agent-sandbox-read";
	const binding = "clusterrolebindings.rbac.authorization.k8s.io/hephaestus-agent-sandbox-read";
	assert.deepEqual(lines(await run({ impersonates: false })), []);
	const absent = lines(await run({ impersonates: false, reads: { [role]: "absent" } }));
	assert.match(
		absent.join("\n"),
		/^missing: The ClusterRole hephaestus-agent-sandbox-read or its binding does not exist/u,
	);
	const wider = structuredClone(readRole);
	const rule = asRecord(asArray(wider.rules, "rules")[0], "rule");
	rule.verbs = [...asArray(rule.verbs, "verbs"), "delete"];
	const widened = lines(await run({ impersonates: false, reads: { [role]: { found: wider } } }));
	assert.match(
		widened.join("\n"),
		/^missing: The ClusterRole hephaestus-agent-sandbox-read differs from the read-only one/u,
	);
	const unbound = lines(
		await run({ impersonates: false, reads: { [binding]: { found: { subjects: [] } } } }),
	);
	assert.match(
		unbound.join("\n"),
		/^missing: The ClusterRoleBinding hephaestus-agent-sandbox-read does not bind hephaestus-agent-sandbox-read to hephaestus\/heph-agent-sandbox-controller/u,
	);
	const hidden = lines(await run({ impersonates: false, reads: { [role]: "forbidden" } }));
	assert.match(
		hidden.join("\n"),
		/^unverified: The ClusterRole hephaestus-agent-sandbox-read cannot be checked/u,
	);
});

void test("a subresource goes to kubectl's --subresource, because a slash in the resource names an object", () => {
	assert.deepEqual(canIArgs("get", "pods/log"), [
		"auth",
		"can-i",
		"get",
		"pods",
		"--subresource=log",
	]);
	assert.deepEqual(canIArgs("patch", "sandboxes/status.agents.x-k8s.io"), [
		"auth",
		"can-i",
		"patch",
		"sandboxes.agents.x-k8s.io",
		"--subresource=status",
	]);
	assert.deepEqual(canIArgs("create", "jobs.batch"), ["auth", "can-i", "create", "jobs.batch"]);
});

void test("a kind that the cluster does not serve reads as absent, a refusal as forbidden, anything else fails", () => {
	assert.equal(
		readFailure(
			`error: the server doesn't have a resource type "gateways"`,
			"kubectl get gateways",
		),
		"absent",
	);
	assert.equal(
		readFailure(
			`Error from server (NotFound): gateways.gateway.networking.k8s.io "x" not found`,
			"get",
		),
		"absent",
	);
	assert.equal(
		readFailure(
			`Error from server (Forbidden): namespaces "x" is forbidden: User "u" cannot get`,
			"get",
		),
		"forbidden",
	);
	assert.throws(
		() => readFailure("Unable to connect to the server: dial tcp: i/o timeout", "get"),
		/i\/o timeout/u,
	);
});
