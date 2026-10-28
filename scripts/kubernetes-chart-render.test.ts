/**
 * Chart behaviour, read from what Helm actually renders.
 *
 * Each case runs `helm template` on the real chart, a real profile, values that kubernetes-chart.ts
 * prepares from a synthetic lock over the real release image inventory, and operator values that
 * name a Secret but hold no secret value. The assertions read the parsed resources. A render is not
 * a cluster: admission, network enforcement, volume start-up and upgrades stay with the cluster tests.
 *
 * The chart source names no image, so a source render passes the prepared values as a CI run
 * passes its own. One case renders a release package instead, which pins its images itself.
 *
 * The worker renders only with the names of what an operator qualified on the sandbox nodes. These
 * cases pass synthetic names: a render proves which names and which authority the worker receives,
 * never that a node loads a profile or enforces a process limit or a policy.
 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";

import { parseAllDocuments, stringify } from "yaml";

import { parseInventory } from "./commit-image-lock.ts";
import { chartValues } from "./kubernetes-chart.ts";
import { isRecord, parseJson, readJsonFileSync } from "./lib/json.ts";
import { packageReleaseChart } from "./release-chart.ts";
import { parseReleaseImageLock } from "./release-image-lock.ts";

const chart = fileURLToPath(new URL("../deploy/chart/hephaestus/", import.meta.url));
const profile = (name: string) => path.join(chart, "profiles", `${name}.yaml`);

/** A stalled helm process fails the case instead of holding the run. */
const VERSION_TIMEOUT_MS = 10_000;
const RENDER_TIMEOUT_MS = 30_000;

const helm = spawnSync("helm", ["version", "--short"], {
	encoding: "utf8",
	timeout: VERSION_TIMEOUT_MS,
});
if (helm.error !== undefined || helm.status !== 0) {
	throw new Error(
		`The chart render tests need the helm executable on PATH, and it could not run (${helm.error?.message ?? `exit ${String(helm.status)}`}). Install the Helm version that .github/actions/setup-helm pins, then run vp run test:kubernetes:chart again.`,
	);
}

const inventory = parseInventory(
	readJsonFileSync(fileURLToPath(new URL("../security/release-images.json", import.meta.url))),
);
const digest = (character: string) => `sha256:${character.repeat(64)}`;
const platforms = { "linux/amd64": digest("a"), "linux/arm64": digest("b") };
const prepared = chartValues(
	parseReleaseImageLock({
		schemaVersion: 1,
		release: "v1.2.3",
		commit: "c".repeat(40),
		images: [
			...inventory.images.map((image, index) => ({
				image,
				repository: `ghcr.io/hephaestus-build/${image}`,
				provenance: "first-party",
				indexDigest: digest(String(index)),
				platforms,
			})),
			...inventory.upstream.map((entry) => ({
				image: entry.name,
				repository: entry.repository,
				provenance: "upstream",
				indexDigest: entry.digest,
				platforms,
			})),
		],
	}),
	inventory,
);

const secretName = "hephaestus-secrets";
const gatewayPods = { "gateway.networking.k8s.io/gateway-name": "edge" };
const parentRefs = [{ name: "edge", namespace: "gateway-system", sectionName: "https" }];
const operator = {
	host: "hephaestus.example.com",
	trustedProxies: String.raw`10\.42\.\d{1,3}\.\d{1,3}`,
	secret: { name: secretName },
	route: { parentRefs },
	networkPolicy: { gateway: { namespace: "gateway-system", podLabels: gatewayPods } },
	postgresql: { tls: { existingSecret: "hephaestus-postgresql-tls" } },
};
const external = {
	postgresql: { bundled: false, external: { host: "db.internal", cidr: "10.0.12.0/24" } },
	nats: { bundled: false, external: { url: "nats://nats.internal:4222", cidr: "10.0.13.0/24" } },
};
/** Synthetic sandbox nodes: every optional setting named. */
const workerRuntime = {
	runtimeClassName: "gvisor",
	seccompProfile: "hephaestus/agent-seccomp-profile.json",
	nodeSelector: { "hephaestus.build/pool": "sandboxes" },
	tolerations: [{ key: "hephaestus.build/sandboxes", operator: "Exists", effect: "NoSchedule" }],
	probeInterval: "15m",
};
/** The namespace that the operator created for sandboxes. */
const sandboxNamespace = "hephaestus-sandboxes";
/** The API server as the network plugin sees it after the Service address is translated. */
const kubernetesApi = { cidr: "192.168.5.15/32", port: 6443 };
const workerLayer = {
	worker: { enabled: true, runtime: workerRuntime, sandbox: { namespace: sandboxNamespace } },
	networkPolicy: { kubernetesApi },
};
/** What the operator states for the single-node profile's worker (docs/admin/kubernetes.mdx § Install). */
const reviews = {
	worker: { sandbox: { namespace: sandboxNamespace } },
	networkPolicy: { kubernetesApi },
};

type Resource = Record<string, unknown>;
/** A values file path, or values to write to a file. */
type Layer = string | object;
interface Rendered {
	ok: boolean;
	stderr: string;
	resources: Resource[];
}

/**
 * One `helm template` run of the chart source, or of `source`. A string layer is a values file
 * path; an object is written to one.
 */
function render(
	layers: readonly Layer[],
	{ release = "heph", namespace = "hephaestus", source = chart } = {},
): Rendered {
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-chart-"));
	try {
		const files = layers.map((layer, index) => {
			if (typeof layer === "string") {
				return layer;
			}
			const file = path.join(directory, `values-${index}.yaml`);
			writeFileSync(file, stringify(layer));
			return file;
		});
		const result = spawnSync(
			"helm",
			[
				"template",
				release,
				source,
				"--namespace",
				namespace,
				...files.flatMap((file) => ["--values", file]),
			],
			{ encoding: "utf8", timeout: RENDER_TIMEOUT_MS },
		);
		// A spawn failure or a timeout is a failed run, never a refusal to assert on.
		if (result.error !== undefined) {
			throw result.error;
		}
		if (result.signal !== null) {
			throw new Error(`helm template stopped with ${result.signal}`);
		}
		if (result.status !== 0) {
			return { ok: false, stderr: result.stderr, resources: [] };
		}
		const documents = parseAllDocuments(result.stdout);
		const resources: Resource[] = [];
		for (const document of documents) {
			assert.deepEqual(document.errors, [], "helm rendered invalid YAML");
			const value: unknown = document.toJS();
			if (isRecord(value)) {
				resources.push(value);
			}
		}
		return { ok: true, stderr: result.stderr, resources };
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

function rendered(layers: readonly Layer[], options = {}): Resource[] {
	const result = render(layers, options);
	assert.ok(result.ok, result.stderr);
	return result.resources;
}

const get = (value: unknown, ...keys: (string | number)[]): unknown => {
	let current = value;
	for (const key of keys) {
		if (typeof key === "number") {
			current = Array.isArray(current) ? current[key] : undefined;
		} else {
			current = isRecord(current) ? current[key] : undefined;
		}
	}
	return current;
};
const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const kindOf = (resource: Resource) => get(resource, "kind");
const nameOf = (resource: Resource) => String(get(resource, "metadata", "name"));
const componentOf = (resource: Resource) =>
	get(resource, "metadata", "labels", "app.kubernetes.io/component");
const workloads = (resources: Resource[]) =>
	resources.filter((resource) => ["Deployment", "StatefulSet"].includes(String(kindOf(resource))));
const workload = (resources: Resource[], component: string) => {
	const found = workloads(resources).filter((resource) => componentOf(resource) === component);
	assert.equal(found.length, 1, `one ${component} workload`);
	return found[0];
};
const podSpec = (resource: unknown) => get(resource, "spec", "template", "spec");
const containers = (resource: unknown) => [
	...list(get(podSpec(resource), "initContainers")),
	...list(get(podSpec(resource), "containers")),
];
const container = (resource: unknown) => {
	const found = list(get(podSpec(resource), "containers"));
	assert.equal(found.length, 1);
	return found[0];
};
const env = (resource: unknown, name: string) =>
	list(get(container(resource), "env")).find((entry) => get(entry, "name") === name);
const envValue = (resource: unknown, name: string) => get(env(resource, name), "value");
/** Code-point order, the same on every platform and locale. */
const byCodePoint = (left: string, right: string) => (left < right ? -1 : Number(left > right));
/** A non-empty selector whose every label the given label set carries. */
const selects = (selector: unknown, labels: unknown) =>
	isRecord(selector) &&
	isRecord(labels) &&
	Object.keys(selector).length > 0 &&
	Object.entries(selector).every(([key, value]) => labels[key] === value);
const named = (resources: Resource[], kind: string, name: unknown) =>
	resources.some((resource) => kindOf(resource) === kind && nameOf(resource) === name);

/** The name and every label value fit Kubernetes' limits, and the full release stays a label. */
function assertBounded(resource: Resource, release: string, label: string): void {
	assert.equal(get(resource, "metadata", "labels", "app.kubernetes.io/instance"), release, label);
	assert.ok(nameOf(resource).length <= 63, label);
	for (const labels of [
		get(resource, "metadata", "labels"),
		get(resource, "spec", "template", "metadata", "labels"),
	].filter(isRecord)) {
		for (const value of Object.values(labels)) {
			assert.ok(String(value).length <= 63, `${label} label ${String(value)}`);
		}
	}
	if (kindOf(resource) === "StatefulSet") {
		// The controller adds controller-revision-hash (the name, a dash and up to 10 characters)
		// and statefulset.kubernetes.io/pod-name ("<name>-0") as label values.
		assert.ok(nameOf(resource).length + 1 + 10 <= 63, label);
	}
}

/** A workload selects its own pods, and its account, claims and config maps are rendered objects. */
function assertWorkloadReferences(resources: Resource[], resource: Resource, label: string): void {
	assert.ok(
		selects(
			get(resource, "spec", "selector", "matchLabels"),
			get(resource, "spec", "template", "metadata", "labels"),
		),
		`${label} selector`,
	);
	const spec = podSpec(resource);
	// PostgreSQL and NATS run under the namespace default account, without its token.
	const account = get(spec, "serviceAccountName");
	assert.ok(
		account === undefined || named(resources, "ServiceAccount", account),
		`${label} service account`,
	);
	for (const volume of list(get(spec, "volumes"))) {
		const claim = get(volume, "persistentVolumeClaim", "claimName");
		const config = get(volume, "configMap", "name");
		assert.ok(
			claim === undefined || named(resources, "PersistentVolumeClaim", claim),
			`${label} claim`,
		);
		assert.ok(config === undefined || named(resources, "ConfigMap", config), `${label} config map`);
	}
}

/** Every name a resource refers to is a rendered object, and every selector selects a rendered pod. */
function assertReferencesResolve(
	resources: Resource[],
	podLabels: readonly unknown[],
	resource: Resource,
	label: string,
): void {
	const kind = kindOf(resource);
	if (kind === "StatefulSet") {
		assert.ok(
			named(resources, "Service", get(resource, "spec", "serviceName")),
			`${label} serviceName`,
		);
	}
	if (kind === "Deployment" || kind === "StatefulSet") {
		assertWorkloadReferences(resources, resource, label);
	}
	if (kind === "Service") {
		const selector = get(resource, "spec", "selector");
		assert.ok(
			podLabels.some((labels) => selects(selector, labels)),
			`${label} selects no pod`,
		);
	}
	// A sandbox namespace's policies select the Pods that the worker's runtime creates there.
	if (kind === "NetworkPolicy" && get(resource, "metadata", "namespace") === undefined) {
		const selector = get(resource, "spec", "podSelector", "matchLabels");
		assert.ok(
			podLabels.some((labels) => selects(selector, labels)),
			`${label} selects no pod`,
		);
	}
	if (kind === "RoleBinding") {
		const namespace = get(resource, "metadata", "namespace");
		assert.ok(
			resources.some(
				(candidate) =>
					kindOf(candidate) === "Role" &&
					nameOf(candidate) === get(resource, "roleRef", "name") &&
					get(candidate, "metadata", "namespace") === namespace,
			),
			`${label} role`,
		);
		for (const subject of list(get(resource, "subjects"))) {
			assert.ok(named(resources, "ServiceAccount", get(subject, "name")), `${label} subject`);
		}
	}
	if (kind === "HTTPRoute") {
		for (const backend of list(get(resource, "spec", "rules")).flatMap((rule) =>
			list(get(rule, "backendRefs")),
		)) {
			assert.ok(named(resources, "Service", get(backend, "name")), `${label} backend`);
		}
	}
}

/** The three profiles, and the small one with an operator's external PostgreSQL and NATS. */
const profiles = ["dev", "preview", "small", "small-16gib", "external"] as const;
const maxFileBytes = {
	dev: 4_294_967_296,
	preview: 2_147_483_648,
	small: 17_179_869_184,
	"small-16gib": 17_179_869_184,
	external: 17_179_869_184,
};
const layersFor = (name: (typeof profiles)[number]) => {
	switch (name) {
		case "external": {
			return [profile("small"), prepared, operator, reviews, external];
		}
		// The 16 GiB profile goes on top of the single-node one.
		case "small-16gib": {
			return [profile("small"), profile("small-16gib"), prepared, operator, reviews];
		}
		case "small": {
			return [profile("small"), prepared, operator, reviews];
		}
		case "dev":
		case "preview": {
			return [profile(name), prepared, operator];
		}
	}
};
const bundled = (name: (typeof profiles)[number]) => name !== "external";
const runsReviews = (name: (typeof profiles)[number]) =>
	name === "small" || name === "small-16gib" || name === "external";
/** A profile as an operator installs it with reviews: CI turns on the worker of the dev profile this way. */
const withWorker = (name: (typeof profiles)[number]) =>
	runsReviews(name) ? layersFor(name) : [...layersFor(name), workerLayer];
/** The worker and what it writes into the sandbox namespace. */
const workerOwned = (resource: Resource) =>
	["worker", "sandbox"].includes(String(componentOf(resource)));
const imageOf = {
	server: "application-server",
	webhook: "application-server",
	webapp: "webapp",
	postgresql: "postgres",
	nats: "nats",
} as const;

for (const name of profiles) {
	await test(`${name}: each role runs its locked image with its own role flags`, () => {
		const resources = rendered(layersFor(name));
		const components = workloads(resources)
			.map((resource) => String(componentOf(resource)))
			.toSorted(byCodePoint);
		assert.deepEqual(components, [
			...(bundled(name) ? ["nats", "postgresql"] : []),
			"server",
			"webapp",
			"webhook",
			...(runsReviews(name) ? ["worker"] : []),
		]);
		for (const [component, image] of Object.entries(imageOf)) {
			if (!components.includes(component)) {
				continue;
			}
			for (const entry of containers(workload(resources, component))) {
				assert.equal(get(entry, "image"), prepared.images[image], `${component} image`);
			}
		}
		const server = workload(resources, "server");
		const webhook = workload(resources, "webhook");
		assert.equal(envValue(server, "SPRING_PROFILES_ACTIVE"), "prod");
		assert.equal(envValue(server, "HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED"), "false");
		assert.equal(envValue(server, "HEPHAESTUS_RUNTIME_WORKER_ENABLED"), "false");
		assert.equal(env(server, "SPRING_LIQUIBASE_ENABLED"), undefined, "the server owns migrations");
		assert.equal(get(server, "spec", "replicas"), 1);
		assert.equal(get(server, "spec", "strategy", "type"), "Recreate");
		assert.equal(envValue(webhook, "SPRING_PROFILES_ACTIVE"), "prod,webhook");
		assert.equal(envValue(webhook, "HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED"), "true");
		assert.equal(envValue(webhook, "HEPHAESTUS_RUNTIME_SERVER_ENABLED"), "false");
		assert.equal(envValue(webhook, "HEPHAESTUS_RUNTIME_WORKER_ENABLED"), "false");
		assert.equal(envValue(webhook, "SPRING_LIQUIBASE_ENABLED"), "false");
		for (const role of [server, webhook]) {
			assert.equal(envValue(role, "HEPHAESTUS_AGENT_IMAGE_REFERENCE"), prepared.images["agent-pi"]);
			assert.equal(
				envValue(role, "HEPHAESTUS_IMAGE_APPLICATION_SERVER"),
				prepared.images["application-server"],
			);
			assert.equal(envValue(role, "HEPHAESTUS_RELEASE_COMMIT"), prepared.release.commit);
			assert.equal(envValue(role, "APP_VERSION"), "1.2.3");
		}
		// One migration owner, the revision's Job; no cluster-wide authority. Only the worker holds a
		// Role, in the sandbox namespace; its own test checks that authority.
		for (const resource of resources.filter(
			(candidate) =>
				!(kindOf(candidate) === "Job" && componentOf(candidate) === "migration") &&
				!workerOwned(candidate),
		)) {
			assert.ok(
				![
					"Job",
					"CronJob",
					"Role",
					"RoleBinding",
					"ClusterRole",
					"ClusterRoleBinding",
					"Namespace",
					"Ingress",
					"Gateway",
					"Secret",
				].includes(String(kindOf(resource))),
				`${String(kindOf(resource))} ${nameOf(resource)}`,
			);
			const annotations = get(resource, "metadata", "annotations");
			assert.ok(!isRecord(annotations) || !("helm.sh/hook" in annotations), nameOf(resource));
		}
	});

	await test(`${name}: every pod is restricted, tokenless and bounded, and reads secrets by reference`, () => {
		const resources = rendered(layersFor(name));
		// Only the worker calls the Kubernetes API, so only its Pods mount a token.
		for (const account of resources.filter(
			(resource) => kindOf(resource) === "ServiceAccount" && !workerOwned(resource),
		)) {
			assert.equal(get(account, "automountServiceAccountToken"), false, nameOf(account));
		}
		for (const resource of workloads(resources)) {
			const label = nameOf(resource);
			const spec = podSpec(resource);
			assert.equal(get(spec, "automountServiceAccountToken"), workerOwned(resource), label);
			assert.equal(get(spec, "securityContext", "runAsNonRoot"), true, label);
			assert.notEqual(get(spec, "securityContext", "runAsUser"), 0, label);
			assert.equal(get(spec, "securityContext", "seccompProfile", "type"), "RuntimeDefault", label);
			for (const flag of ["hostNetwork", "hostPID", "hostIPC"]) {
				assert.notEqual(get(spec, flag), true, `${label} ${flag}`);
			}
			assert.equal(get(spec, "securityContext", "sysctls"), undefined, label);
			for (const volume of list(get(spec, "volumes"))) {
				assert.equal(get(volume, "hostPath"), undefined, label);
			}
			for (const entry of containers(resource)) {
				const security = get(entry, "securityContext");
				assert.equal(get(security, "allowPrivilegeEscalation"), false, label);
				assert.equal(get(security, "readOnlyRootFilesystem"), true, label);
				assert.notEqual(get(security, "privileged"), true, label);
				assert.deepEqual(get(security, "capabilities", "drop"), ["ALL"], label);
				assert.equal(get(security, "capabilities", "add"), undefined, label);
				for (const bound of ["requests", "limits"]) {
					for (const resourceName of ["cpu", "memory", "ephemeral-storage"]) {
						assert.equal(
							typeof get(entry, "resources", bound, resourceName),
							"string",
							`${label} ${bound}.${resourceName}`,
						);
					}
				}
				for (const variable of list(get(entry, "env"))) {
					const variableName = String(get(variable, "name"));
					// The review-ledger identity stays per process: no rendered role overrides it.
					assert.notEqual(variableName, "HEPHAESTUS_WORKER_ID", label);
					const reference = get(variable, "valueFrom", "secretKeyRef");
					if (reference !== undefined) {
						assert.equal(get(reference, "name"), secretName, variableName);
					} else if (
						/(?:SECRET|PASSWORD|TOKEN|PRIVATE_KEY|ENCRYPTION_KEY|COOKIE_KEY)$/u.test(variableName)
					) {
						assert.fail(`${label} sets ${variableName} without the Secret`);
					}
				}
			}
		}
	});

	await test(`${name}: storage is bounded, and one value sizes the broker and the stream budget`, () => {
		const resources = rendered(layersFor(name));
		const budget = maxFileBytes[name];
		assert.equal(
			envValue(workload(resources, "webhook"), "HEPHAESTUS_WEBHOOK_STREAM_STORAGE_BUDGET"),
			String(budget),
		);
		// The git mirrors are caches on node storage: no role claims a volume of its own.
		assert.equal(
			resources.some((resource) => kindOf(resource) === "PersistentVolumeClaim"),
			false,
		);
		const cache = list(get(podSpec(workload(resources, "server")), "volumes")).find(
			(volume) => get(volume, "name") === "git-cache",
		);
		assert.match(String(get(cache, "emptyDir", "sizeLimit")), /^[1-9]\d*(?:Gi)$/u);
		if (!bundled(name)) {
			return;
		}
		const config = resources.find(
			(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "nats",
		);
		assert.match(
			String(get(config, "data", "nats-server.conf")),
			new RegExp(`^\\s*max_file: ${budget}$`, "mu"),
		);
		const nats = workload(resources, "nats");
		assert.equal(get(nats, "spec", "replicas"), 1);
		assert.equal(
			String(
				get(nats, "spec", "volumeClaimTemplates", 0, "spec", "resources", "requests", "storage"),
			),
			String(Math.floor((budget * 5) / 4)),
		);
		// ADR 0038: the volume sits at /var/lib/postgresql; the image keeps its own versioned PGDATA.
		const postgresql = workload(resources, "postgresql");
		assert.equal(get(postgresql, "spec", "replicas"), 1);
		assert.equal(env(postgresql, "PGDATA"), undefined);
		const mounts = list(get(container(postgresql), "volumeMounts"));
		assert.deepEqual(mounts.map((mount) => String(get(mount, "mountPath"))).toSorted(byCodePoint), [
			"/etc/postgresql/tls",
			"/tmp",
			"/var/lib/postgresql",
			"/var/run/postgresql",
		]);
		assert.equal(get(postgresql, "spec", "volumeClaimTemplates", 0, "metadata", "name"), "data");
		assert.equal(get(postgresql, "spec", "template", "spec", "securityContext", "fsGroup"), 999);
	});
}

await test("a release package runs the release's images with the operator's values alone", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-chart-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	// The release's own packaging, with the real Helm: it also reads the package back through
	// `helm show chart` and `helm show values` and refuses one that does not pin these images.
	const packaged = await packageReleaseChart(prepared.release.version, chart, prepared, directory);
	const resources = rendered([profile("small"), operator, reviews], { source: packaged });
	for (const [component, image] of Object.entries(imageOf)) {
		for (const entry of containers(workload(resources, component))) {
			assert.equal(get(entry, "image"), prepared.images[image], `${component} image`);
		}
	}
	const server = workload(resources, "server");
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_IMAGE_REFERENCE"), prepared.images["agent-pi"]);
	assert.equal(envValue(server, "HEPHAESTUS_RELEASE_COMMIT"), prepared.release.commit);
	assert.equal(envValue(server, "APP_VERSION"), "1.2.3");
	// The chart source alone still refuses to render without a run's images.
	assert.match(render([profile("small"), operator, reviews]).stderr, /images|release/u);
});

await test("the webapp runs the non-root image with one writable subtree on 8080", () => {
	const resources = rendered(layersFor("small"));
	const webapp = workload(resources, "webapp");
	const spec = podSpec(webapp);
	assert.equal(get(spec, "securityContext", "runAsUser"), 101);
	assert.equal(get(spec, "securityContext", "runAsGroup"), 101);
	assert.equal(get(spec, "securityContext", "fsGroup"), 101);
	assert.equal(get(spec, "initContainers"), undefined);
	const entry = container(webapp);
	assert.equal(get(entry, "command"), undefined);
	assert.equal(get(entry, "args"), undefined);
	assert.deepEqual(get(entry, "ports"), [{ name: "http", containerPort: 8080 }]);
	assert.deepEqual(get(entry, "volumeMounts"), [
		{ name: "runtime", mountPath: "/tmp/hephaestus-webapp" },
	]);
	assert.deepEqual(get(spec, "volumes"), [{ name: "runtime", emptyDir: { sizeLimit: "16Mi" } }]);
	for (const probe of ["startupProbe", "livenessProbe", "readinessProbe"]) {
		assert.deepEqual(get(entry, probe, "httpGet"), { path: "/", port: "http" }, probe);
	}
	const service = resources.find(
		(resource) => kindOf(resource) === "Service" && componentOf(resource) === "webapp",
	);
	assert.deepEqual(get(service, "spec", "ports"), [
		{ name: "http", port: 8080, targetPort: "http" },
	]);
});

await test("the webapp takes its security contact from one URI value", () => {
	const contact = "mailto:security@example.invalid";
	const webapp = workload(
		rendered([...layersFor("small"), { webapp: { securityContact: contact } }]),
		"webapp",
	);
	assert.equal(envValue(webapp, "SECURITY_CONTACT"), contact);
	assert.equal(envValue(workload(rendered(layersFor("small")), "webapp"), "SECURITY_CONTACT"), "");
	for (const refused of ["mailto:a b@example.invalid", "javascript:alert(1)"]) {
		assert.match(
			render([...layersFor("small"), { webapp: { securityContact: refused } }]).stderr,
			/securityContact/u,
			refused,
		);
	}
});

await test("the webapp serves the operator's legal pages from a ConfigMap, read-only", () => {
	const webapp = workload(
		rendered([...layersFor("small"), { webapp: { legalOverrides: "site-legal" } }]),
		"webapp",
	);
	assert.deepEqual(get(container(webapp), "volumeMounts"), [
		{ name: "runtime", mountPath: "/tmp/hephaestus-webapp" },
		{
			name: "legal-overrides",
			mountPath: "/usr/share/nginx/html/legal-overrides",
			readOnly: true,
		},
	]);
	assert.deepEqual(get(podSpec(webapp), "volumes", 1), {
		name: "legal-overrides",
		configMap: { name: "site-legal" },
	});
	assert.match(
		render([...layersFor("small"), { webapp: { legalOverrides: "../etc" } }]).stderr,
		/legalOverrides/u,
	);
});

await test("the route attaches to the platform Gateway and strips only /api", () => {
	const resources = rendered(layersFor("small"));
	const routes = resources.filter((resource) => kindOf(resource) === "HTTPRoute");
	assert.equal(routes.length, 1);
	const route = routes[0];
	assert.deepEqual(get(route, "spec", "parentRefs"), parentRefs);
	assert.deepEqual(get(route, "spec", "hostnames"), [operator.host]);
	const rules = list(get(route, "spec", "rules"));
	const rule = (type: string, value: string) => {
		const found = rules.filter((candidate) =>
			list(get(candidate, "matches")).some(
				(match) => get(match, "path", "type") === type && get(match, "path", "value") === value,
			),
		);
		assert.equal(found.length, 1, `${type} ${value}`);
		return found[0];
	};
	const filters = (candidate: unknown) => list(get(candidate, "filters"));
	const backends = (candidate: unknown) => get(candidate, "backendRefs");
	assert.equal(rules.length, 4);
	assert.deepEqual(backends(rule("Exact", "/webhooks/slack/interactivity")), [
		{ name: "heph-server", port: 8080 },
	]);
	assert.deepEqual(backends(rule("PathPrefix", "/webhooks")), [
		{ name: "heph-webhook", port: 8080 },
	]);
	assert.deepEqual(backends(rule("PathPrefix", "/api")), [{ name: "heph-server", port: 8080 }]);
	assert.deepEqual(backends(rule("PathPrefix", "/")), [{ name: "heph-webapp", port: 8080 }]);
	assert.deepEqual(
		filters(rule("PathPrefix", "/api")).filter((filter) => get(filter, "type") === "URLRewrite"),
		[
			{
				type: "URLRewrite",
				urlRewrite: { path: { type: "ReplacePrefixMatch", replacePrefixMatch: "/" } },
			},
		],
	);
	for (const candidate of rules) {
		const types = new Set(filters(candidate).map((filter) => get(filter, "type")));
		assert.ok(types.has("ResponseHeaderModifier"), "HSTS on every rule");
		assert.equal(
			types.has("URLRewrite"),
			candidate === rule("PathPrefix", "/api"),
			"only /api is rewritten",
		);
		// Only /api asks the controller to disable both deadlines, for the Heph live stream.
		assert.deepEqual(
			get(candidate, "timeouts"),
			candidate === rule("PathPrefix", "/api")
				? { request: "0s", backendRequest: "0s" }
				: undefined,
			"only /api sets timeouts",
		);
		assert.equal(get(candidate, "retry"), undefined, "no rule retries");
	}
	const hsts = filters(rules[0]).find((filter) => get(filter, "type") === "ResponseHeaderModifier");
	assert.deepEqual(get(hsts, "responseHeaderModifier", "set"), [
		{ name: "Strict-Transport-Security", value: "max-age=2592000; includeSubDomains" },
	]);
	// The Services the route names exist and listen on the same port.
	for (const backend of rules.flatMap((candidate) => list(backends(candidate)))) {
		const service = resources.find(
			(resource) => kindOf(resource) === "Service" && nameOf(resource) === get(backend, "name"),
		);
		assert.equal(get(service, "spec", "ports", 0, "port"), get(backend, "port"));
	}
});

for (const name of ["small", "external"] as const) {
	await test(`${name}: network policy denies by default and names exact peers`, () => {
		const resources = rendered(layersFor(name));
		const policies = resources.filter((resource) => kindOf(resource) === "NetworkPolicy");
		const policy = (component: string) => {
			const found = policies.find((candidate) => nameOf(candidate) === `heph-${component}`);
			assert.ok(found, component);
			return found;
		};
		const deny = policy("default-deny");
		assert.deepEqual(get(deny, "spec"), {
			podSelector: {
				matchLabels: {
					"app.kubernetes.io/name": "hephaestus",
					"app.kubernetes.io/instance": "heph",
				},
			},
			policyTypes: ["Ingress", "Egress"],
		});
		for (const candidate of policies) {
			for (const rule of [
				...list(get(candidate, "spec", "ingress")),
				...list(get(candidate, "spec", "egress")),
			]) {
				for (const peer of [...list(get(rule, "from")), ...list(get(rule, "to"))]) {
					if (get(peer, "ipBlock") !== undefined) {
						continue;
					}
					const selector = get(peer, "podSelector");
					const labels = get(selector, "matchLabels");
					assert.ok(
						(isRecord(labels) && Object.keys(labels).length > 0) ||
							list(get(selector, "matchExpressions")).length > 0,
						`${nameOf(candidate)} admits a whole namespace`,
					);
				}
			}
		}
		const gatewayPeer = {
			namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "gateway-system" } },
			podSelector: { matchLabels: gatewayPods },
		};
		for (const [component, port] of [
			["server", "http"],
			["webhook", "http"],
			["webapp", "http"],
		] as const) {
			const ingress = list(get(policy(component), "spec", "ingress"));
			assert.equal(ingress.length, 1, `${component}: no scraper is configured`);
			assert.deepEqual(get(ingress[0], "from", 0), gatewayPeer, component);
			assert.deepEqual(get(ingress[0], "ports"), [{ port, protocol: "TCP" }], component);
		}
		const webhookEgress = list(get(policy("webhook"), "spec", "egress"));
		const serverEgress = list(get(policy("server"), "spec", "egress"));
		if (name === "external") {
			for (const egress of [serverEgress, webhookEgress]) {
				assert.ok(
					egress.some(
						(rule) =>
							get(rule, "to", 0, "ipBlock", "cidr") === "10.0.12.0/24" &&
							get(rule, "ports", 0, "port") === 5432,
					),
				);
				assert.ok(
					egress.some(
						(rule) =>
							get(rule, "to", 0, "ipBlock", "cidr") === "10.0.13.0/24" &&
							get(rule, "ports", 0, "port") === 4222,
					),
				);
			}
		}
		// The receiver reaches the database and the broker only. The server adds internet HTTPS,
		// which never includes private ranges.
		assert.equal(webhookEgress.length, 2);
		const internet = serverEgress.filter(
			(rule) => get(rule, "to", 0, "ipBlock", "cidr") === "0.0.0.0/0",
		);
		assert.equal(internet.length, 1);
		assert.deepEqual(get(internet[0], "ports"), [{ port: 443, protocol: "TCP" }]);
		assert.ok(list(get(internet[0], "to", 0, "ipBlock", "except")).includes("10.0.0.0/8"));
	});
}

await test("admin assurance follows the operator's value and defaults to on", () => {
	const server = (layers: readonly Layer[]) => workload(rendered(layers), "server");
	assert.equal(envValue(server(layersFor("small")), "HEPHAESTUS_AUTH_ASSURANCE_ENFORCED"), "true");
	assert.equal(
		envValue(server(layersFor("preview")), "HEPHAESTUS_AUTH_ASSURANCE_ENFORCED"),
		"true",
	);
	assert.equal(
		envValue(
			server([...layersFor("small"), { config: { auth: { assuranceEnforced: false } } }]),
			"HEPHAESTUS_AUTH_ASSURANCE_ENFORCED",
		),
		"false",
	);
	assert.equal(
		env(workload(rendered(layersFor("small")), "webhook"), "HEPHAESTUS_AUTH_ASSURANCE_ENFORCED"),
		undefined,
	);
});

await test("the longest release names render bounded, distinct and consistent objects", () => {
	// Helm accepts release names of up to 53 characters. These two differ only in the last one.
	const releases = [`${"r".repeat(52)}a`, `${"r".repeat(52)}b`];
	const names = new Map<string, Set<string>>();
	for (const release of releases) {
		const resources = rendered([...layersFor("small"), workerLayer], { release, namespace: "h" });
		const podLabels = [
			...workloads(resources),
			...resources.filter((resource) => kindOf(resource) === "Job"),
		].map((resource) => {
			const labels = get(resource, "spec", "template", "metadata", "labels");
			// The StatefulSet controller names its first pod "<name>-0" and labels it with that name.
			return kindOf(resource) === "StatefulSet" && isRecord(labels)
				? { ...labels, "statefulset.kubernetes.io/pod-name": `${nameOf(resource)}-0` }
				: labels;
		});
		// Sandbox objects carry the release's name, as every other object does.
		names.set(
			release,
			new Set(
				resources.map((resource) => {
					const namespace = get(resource, "metadata", "namespace");
					assert.ok(
						namespace === undefined || typeof namespace === "string",
						"a resource namespace is a string when set",
					);
					return `${String(kindOf(resource))}/${namespace ?? ""}/${nameOf(resource)}`;
				}),
			),
		);
		for (const resource of resources) {
			const label = `${String(kindOf(resource))} ${nameOf(resource)}`;
			// The full release identity stays in the labels, whatever the name became.
			assertBounded(resource, release, label);
			assertReferencesResolve(resources, podLabels, resource, label);
		}
		// The roles reach the bundled services under the names those Services received.
		for (const role of [workload(resources, "server"), workload(resources, "webhook")]) {
			const database = /^postgresql:\/\/(?<service>[^.:/]+)\.h\.svc:5432\//u.exec(
				String(envValue(role, "DATABASE_URL")),
			)?.groups?.service;
			assert.ok(named(resources, "Service", database), `database host ${String(database)}`);
		}
		const broker = /^nats:\/\/(?<host>[^:/]+):4222$/u.exec(
			String(envValue(workload(resources, "server"), "NATS_SERVER")),
		)?.groups?.host;
		assert.ok(named(resources, "Service", broker), `broker host ${String(broker)}`);
		// The workers' gateway policy in the sandbox namespace carries the release's name.
		assert.ok(
			resources.some(
				(resource) =>
					kindOf(resource) === "NetworkPolicy" &&
					get(resource, "metadata", "namespace") === sandboxNamespace &&
					componentOf(resource) === "sandbox" &&
					nameOf(resource).startsWith(release.slice(0, 20)),
			),
			"gateway policy",
		);
	}
	const [first = new Set<string>(), second = new Set<string>()] = releases.map(
		(release) => names.get(release) ?? new Set<string>(),
	);
	assert.equal(first.size, second.size);
	for (const name of first) {
		assert.equal(second.has(name), false, `both releases render ${name}`);
	}
});

/** Each grant of a Role, as `apiGroup/resource[names]:verb`, in code-point order. */
const grants = (role: unknown) =>
	list(get(role, "rules"))
		.flatMap((rule) => {
			const names = list(get(rule, "resourceNames"));
			const suffix = names.length > 0 ? `[${names.map(String).join(",")}]` : "";
			return list(get(rule, "apiGroups")).flatMap((group) =>
				list(get(rule, "resources")).flatMap((resource) =>
					list(get(rule, "verbs")).map(
						(verb) => `${String(group)}/${String(resource)}${suffix}:${String(verb)}`,
					),
				),
			);
		})
		.toSorted(byCodePoint);

await test("a release can apply a stream limit that deletes stored messages only when its operator turns it on", () => {
	// The migration that shortens an existing stream's age needs one start with this flag.
	const defaults = workload(rendered(layersFor("small")), "webhook");
	assert.equal(
		envValue(defaults, "HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES"),
		"false",
	);
	assert.equal(envValue(defaults, "HEPHAESTUS_WEBHOOK_STREAM_LIMIT_UPDATE_TIMEOUT"), "5m");

	const migrating = workload(
		rendered([
			...layersFor("small"),
			{ webhook: { stream: { allowDestructiveLimitUpdates: true, limitUpdateTimeout: "30m" } } },
		]),
		"webhook",
	);
	assert.equal(
		envValue(migrating, "HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES"),
		"true",
	);
	assert.equal(envValue(migrating, "HEPHAESTUS_WEBHOOK_STREAM_LIMIT_UPDATE_TIMEOUT"), "30m");
});

await test("an enabled worker receives its runtime settings, its sandbox namespace and only the authority its runtime uses", () => {
	const resources = rendered([...layersFor("small"), workerLayer]);
	const worker = workload(resources, "worker");
	assert.ok(worker, "one worker workload");
	const workerName = nameOf(worker);
	// The operator owns the sandbox namespace: the chart writes into it and creates no namespace.
	assert.equal(resources.filter((resource) => kindOf(resource) === "Namespace").length, 0);
	const sandboxes = sandboxNamespace;

	// The selected settings, the same strings that the chart's objects use.
	assert.equal(envValue(worker, "SPRING_PROFILES_ACTIVE"), "prod,worker");
	assert.equal(envValue(worker, "HEPHAESTUS_SANDBOX_RUNTIME"), "kubernetes");
	assert.equal(envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE"), sandboxes);
	for (const [variable, field] of [
		["POD_NAME", "metadata.name"],
		["POD_IP", "status.podIP"],
	] as const) {
		assert.equal(
			get(
				env(worker, `HEPHAESTUS_SANDBOX_KUBERNETES_${variable}`),
				"valueFrom",
				"fieldRef",
				"fieldPath",
			),
			field,
			variable,
		);
	}
	assert.deepEqual(
		{
			runtimeClassName: envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_RUNTIME_CLASS_NAME"),
			seccompProfile: envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_SECCOMP_PROFILE"),
			nodeSelector: parseJson(
				String(envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_NODE_SELECTOR")),
			),
			tolerations: parseJson(String(envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_TOLERATIONS"))),
			probeInterval: envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_PROBE_INTERVAL"),
		},
		workerRuntime,
	);
	assert.equal(env(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_POD_PIDS_LIMIT"), undefined);
	assert.equal(envValue(worker, "SANDBOX_API_PORT"), "8081");
	assert.ok(
		list(get(container(worker), "ports")).some(
			(port) => get(port, "name") === "sandbox-gateway" && get(port, "containerPort") === 8081,
		),
	);
	assert.equal(
		env(worker, "HEPHAESTUS_WORKER_ID"),
		undefined,
		"the ledger identity stays per process",
	);
	assert.equal(envValue(worker, "HEPHAESTUS_AGENT_IMAGE_REFERENCE"), prepared.images["agent-pi"]);
	assert.equal(get(container(worker), "image"), prepared.images["application-server"]);

	// The single-node profile's one worker: a new one starts before the old one stops, a node drain
	// takes one, and a stopping worker has its drain and the shutdown phases.
	assert.equal(kindOf(worker), "Deployment");
	assert.equal(get(worker, "spec", "replicas"), 1);
	assert.deepEqual(get(worker, "spec", "strategy"), {
		type: "RollingUpdate",
		rollingUpdate: { maxSurge: "100%", maxUnavailable: 0 },
	});
	const spec = podSpec(worker);
	assert.equal(envValue(worker, "HEPHAESTUS_WORKER_DRAIN_TIMEOUT"), "3600s");
	assert.equal(get(spec, "terminationGracePeriodSeconds"), 3600 + 120);
	assert.equal(get(spec, "topologySpreadConstraints", 0, "whenUnsatisfiable"), "ScheduleAnyway");
	assert.deepEqual(
		list(get(spec, "tolerations")).map((toleration) => [
			get(toleration, "key"),
			get(toleration, "tolerationSeconds"),
		]),
		[
			["node.kubernetes.io/not-ready", 60],
			["node.kubernetes.io/unreachable", 60],
		],
	);
	const budgets = resources.filter((resource) => kindOf(resource) === "PodDisruptionBudget");
	assert.equal(budgets.length, 1);
	assert.equal(get(budgets[0], "spec", "maxUnavailable"), 1);
	assert.ok(
		selects(
			get(budgets[0], "spec", "selector", "matchLabels"),
			get(worker, "spec", "template", "metadata", "labels"),
		),
		"the budget selects the workers",
	);
	assert.deepEqual(
		list(get(spec, "volumes")).find((volume) => get(volume, "name") === "git-cache"),
		{ name: "git-cache", emptyDir: { sizeLimit: "10Gi" } },
	);

	// Restricted worker pods with the installation labels that their sandboxes copy, and a token.
	assert.equal(get(spec, "serviceAccountName"), workerName);
	assert.equal(get(spec, "automountServiceAccountToken"), true);
	assert.equal(get(spec, "securityContext", "runAsNonRoot"), true);
	const security = get(container(worker), "securityContext");
	assert.equal(get(security, "allowPrivilegeEscalation"), false);
	assert.equal(get(security, "readOnlyRootFilesystem"), true);
	assert.deepEqual(get(security, "capabilities", "drop"), ["ALL"]);
	const labels = get(worker, "spec", "template", "metadata", "labels");
	for (const [key, value] of [
		["app.kubernetes.io/name", "hephaestus"],
		["app.kubernetes.io/part-of", "hephaestus"],
		["app.kubernetes.io/instance", "heph"],
		["app.kubernetes.io/component", "worker"],
	] as const) {
		assert.equal(get(labels, key), value, key);
	}

	// The chart creates no account in the sandbox namespace: sandbox Pods turn the token off.
	assert.equal(
		resources.filter(
			(resource) =>
				kindOf(resource) === "ServiceAccount" &&
				get(resource, "metadata", "namespace") === sandboxes,
		).length,
		0,
	);
	const quota = resources.find((resource) => kindOf(resource) === "ResourceQuota");
	assert.equal(get(quota, "metadata", "namespace"), sandboxes);
	assert.equal(get(quota, "spec", "hard", "pods"), "12", "the single-node profile's quota");

	// Exactly the calls that the runtime makes, each in one namespace, and no cluster-wide authority.
	for (const kind of ["ClusterRole", "ClusterRoleBinding"]) {
		assert.equal(resources.filter((resource) => kindOf(resource) === kind).length, 0, kind);
	}
	const expected = new Map<string | undefined, string[]>([
		[
			sandboxes,
			[
				...["create", "delete", "get", "list", "patch", "watch"].map((verb) => `/pods:${verb}`),
				"/pods/exec:create",
				"/pods/exec:get",
				"/pods/log:get",
				...["create", "delete", "get", "list", "watch"].map((verb) => `batch/jobs:${verb}`),
				// A quota refusal of a review Pod: the Job controller's event.
				...["get", "list"].map((verb) => `/events:${verb}`),
				// How many reviews fit, watched as Pods and Jobs are, so a claim poll reads a cache.
				...["list", "watch"].map((verb) => `/resourcequotas:${verb}`),
			],
		],
	]);
	const roles = resources.filter((resource) => kindOf(resource) === "Role");
	const bindings = resources.filter((resource) => kindOf(resource) === "RoleBinding");
	assert.equal(roles.length, expected.size);
	assert.equal(bindings.length, expected.size);
	for (const [namespace, granted] of expected) {
		const label = namespace ?? "the release namespace";
		const role = roles.find((resource) => get(resource, "metadata", "namespace") === namespace);
		assert.ok(role, label);
		assert.deepEqual(grants(role), granted.toSorted(byCodePoint), label);
		const binding = bindings.find(
			(resource) => get(resource, "metadata", "namespace") === namespace,
		);
		assert.deepEqual(
			get(binding, "roleRef"),
			{ apiGroup: "rbac.authorization.k8s.io", kind: "Role", name: nameOf(role) },
			label,
		);
		assert.deepEqual(
			get(binding, "subjects"),
			[{ kind: "ServiceAccount", name: workerName, namespace: "hephaestus" }],
			label,
		);
	}

	// Turn sandboxes have no network. Reviews and hosts reach the workers' gateway port only; the
	// credential, not the network, binds a sandbox to its own worker.
	const policiesIn = (namespace: string) =>
		resources
			.filter(
				(resource) =>
					kindOf(resource) === "NetworkPolicy" &&
					get(resource, "metadata", "namespace") === namespace,
			)
			.map(nameOf)
			.toSorted(byCodePoint);
	assert.deepEqual(policiesIn(sandboxes), ["heph-sandbox-deny", "heph-sandbox-gateway"]);
	const gateway = resources.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-sandbox-gateway",
	);
	const sources = {
		matchExpressions: [
			{ key: "hephaestus.build/sandbox-kind", operator: "In", values: ["review", "host"] },
		],
	};
	assert.deepEqual(get(gateway, "spec", "podSelector"), sources);
	const workerSelector = get(worker, "spec", "selector", "matchLabels");
	assert.deepEqual(get(gateway, "spec", "egress"), [
		{
			to: [
				{
					namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "hephaestus" } },
					podSelector: { matchLabels: workerSelector },
				},
			],
			ports: [{ port: 8081, protocol: "TCP" }],
		},
	]);
	const own = resources.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" &&
			nameOf(resource) === workerName &&
			get(resource, "metadata", "namespace") === undefined,
	);
	assert.deepEqual(get(own, "spec", "podSelector", "matchLabels"), workerSelector);
	assert.deepEqual(get(own, "spec", "ingress", 0, "from"), [
		{
			namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": sandboxes } },
			podSelector: sources,
		},
	]);
	assert.deepEqual(get(own, "spec", "ingress", 0, "ports"), [{ port: 8081, protocol: "TCP" }]);
	// The API only at the endpoint the operator qualified, as the plugin sees it after translation.
	const api = list(get(own, "spec", "egress")).filter(
		(rule) => get(rule, "to", 0, "ipBlock", "cidr") === kubernetesApi.cidr,
	);
	assert.deepEqual(api, [
		{
			to: [{ ipBlock: { cidr: kubernetesApi.cidr } }],
			ports: [{ port: kubernetesApi.port, protocol: "TCP" }],
		},
	]);
});

await test("a worker without the optional runtime settings uses the cluster's defaults, and the quota can be left out", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			worker: {
				enabled: true,
				sandbox: { namespace: sandboxNamespace, quota: { enabled: false } },
			},
			networkPolicy: { kubernetesApi },
		},
	]);
	const worker = workload(resources, "worker");
	assert.ok(worker, "one worker workload");
	assert.equal(envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_RUNTIME_CLASS_NAME"), "");
	assert.equal(envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_SECCOMP_PROFILE"), "");
	assert.equal(envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_NODE_SELECTOR"), "{}");
	assert.equal(envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_TOLERATIONS"), "[]");
	assert.equal(resources.filter((resource) => kindOf(resource) === "ResourceQuota").length, 0);
});

const caDirectory = "/etc/hephaestus/postgresql-ca";
const tlsSecret = operator.postgresql.tls.existingSecret;
const verifyFull = `sslmode=verify-full&sslrootcert=${caDirectory}/ca.crt`;

/** The role's only Secret volume holds ca.crt alone, mounted read-only where its URL names it. */
function assertCaOnly(role: Resource, label: string): void {
	assert.deepEqual(
		list(get(podSpec(role), "volumes")).filter((volume) => get(volume, "secret") !== undefined),
		[
			{
				name: "postgresql-ca",
				secret: {
					secretName: tlsSecret,
					items: [{ key: "ca.crt", path: "ca.crt" }],
					defaultMode: 292,
				},
			},
		],
		label,
	);
	assert.deepEqual(
		list(get(container(role), "volumeMounts")).filter(
			(mount) => get(mount, "name") === "postgresql-ca",
		),
		[{ name: "postgresql-ca", mountPath: caDirectory, readOnly: true }],
		label,
	);
}

await test("every role verifies the bundled PostgreSQL at its Service name with the CA alone", () => {
	const resources = rendered([...layersFor("small"), workerLayer]);
	const service = resources.find(
		(resource) => kindOf(resource) === "Service" && componentOf(resource) === "postgresql",
	);
	assert.ok(service, "the PostgreSQL Service");
	const url = `postgresql://${nameOf(service)}.hephaestus.svc:5432/hephaestus?${verifyFull}`;
	for (const component of ["server", "webhook", "worker"]) {
		const role = workload(resources, component);
		assert.ok(role, component);
		assert.equal(envValue(role, "DATABASE_URL"), url, component);
		assertCaOnly(role, component);
	}

	// The image's own entrypoint starts the server with TLS, from the certificate and key alone.
	const postgresql = workload(resources, "postgresql");
	assert.ok(postgresql, "postgresql");
	const entry = container(postgresql);
	assert.equal(get(entry, "command"), undefined);
	assert.deepEqual(get(entry, "args"), [
		"-c",
		"ssl=on",
		"-c",
		"ssl_cert_file=/etc/postgresql/tls/tls.crt",
		"-c",
		"ssl_key_file=/etc/postgresql/tls/tls.key",
	]);
	assert.deepEqual(
		list(get(entry, "volumeMounts")).filter((mount) => get(mount, "name") === "tls"),
		[{ name: "tls", mountPath: "/etc/postgresql/tls", readOnly: true }],
	);
	assert.deepEqual(
		list(get(podSpec(postgresql), "volumes")).filter(
			(volume) => get(volume, "secret") !== undefined,
		),
		[
			{
				name: "tls",
				secret: {
					secretName: tlsSecret,
					items: [
						{ key: "tls.crt", path: "tls.crt" },
						{ key: "tls.key", path: "tls.key" },
					],
					defaultMode: 288,
				},
			},
		],
	);
	const security = get(podSpec(postgresql), "securityContext");
	assert.deepEqual(
		[get(security, "runAsUser"), get(security, "runAsGroup"), get(security, "fsGroup")],
		[999, 999, 999],
	);
	assert.equal(get(entry, "securityContext", "readOnlyRootFilesystem"), true);
	// No other pod projects the server's key.
	assert.deepEqual(
		workloads(resources)
			.filter((resource) =>
				list(get(podSpec(resource), "volumes")).some((volume) =>
					list(get(volume, "secret", "items")).some((item) => get(item, "key") === "tls.key"),
				),
			)
			.map(componentOf),
		["postgresql"],
	);
});

await test("backups archive the WAL from PostgreSQL and read its data through mutual TLS only", () => {
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small"),
		{ postgresql: { backup: { enabled: true, s3 } } },
	]);
	const postgresql = workload(resources, "postgresql");
	const [database, server] = list(get(podSpec(postgresql), "containers"));
	assert.ok(
		list(get(database, "args")).includes(
			"archive_command=pgbackrest --stanza=hephaestus archive-push %p",
		),
	);
	assert.deepEqual(get(server, "command"), ["pgbackrest", "server"]);
	// The repository's credentials reach both sides by reference only.
	for (const side of [database, server]) {
		for (const [variable, key] of [
			["PGBACKREST_REPO1_S3_KEY", "BACKUP_S3_KEY"],
			["PGBACKREST_REPO1_S3_KEY_SECRET", "BACKUP_S3_KEY_SECRET"],
			["PGBACKREST_REPO1_CIPHER_PASS", "BACKUP_CIPHER_PASS"],
		] as const) {
			const entry = list(get(side, "env")).find((candidate) => get(candidate, "name") === variable);
			assert.deepEqual(
				get(entry, "valueFrom", "secretKeyRef"),
				{ name: secretName, key },
				variable,
			);
		}
	}
	const jobs = resources.filter((resource) =>
		["CronJob", "Job"].includes(String(kindOf(resource))),
	);
	assert.deepEqual(
		jobs.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`).toSorted(byCodePoint),
		[
			"CronJob/heph-postgresql-backup-diff",
			"CronJob/heph-postgresql-backup-full",
			"Job/heph-migration-1",
			"Job/heph-postgresql-backup-stanza-1",
		],
	);
	for (const job of jobs.filter((resource) => kindOf(resource) === "CronJob")) {
		assert.equal(get(job, "spec", "concurrencyPolicy"), "Forbid");
		assert.equal(get(job, "spec", "timeZone"), "Etc/UTC");
		const pod = get(job, "spec", "jobTemplate", "spec", "template", "spec");
		assert.equal(get(pod, "automountServiceAccountToken"), false);
		// The backup Pods mount no PostgreSQL volume: they read through the TLS server.
		assert.equal(
			list(get(pod, "volumes")).some(
				(volume) => get(volume, "persistentVolumeClaim") !== undefined,
			),
			false,
		);
		const keys = list(get(pod, "volumes"))
			.flatMap((volume) => list(get(volume, "secret", "items")))
			.map((item) => String(get(item, "key")))
			.toSorted(byCodePoint);
		assert.deepEqual(keys, ["backup.crt", "backup.key", "ca.crt"]);
	}
	const config = resources.find(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "postgresql-backup",
	);
	assert.match(
		String(get(config, "data", "server.conf")),
		/^tls-server-auth=hephaestus-backup=hephaestus$/mu,
	);
	assert.match(
		String(get(config, "data", "client.conf")),
		/^pg1-host=heph-postgresql\.hephaestus\.svc$/mu,
	);
	assert.match(
		String(get(config, "data", "client.conf")),
		/^repo1-s3-bucket=hephaestus-backups$/mu,
	);
	assert.doesNotMatch(JSON.stringify(config), /key-secret|cipher-pass/u);
	// PostgreSQL admits the TLS server's port from the backup Jobs only.
	const policy = resources.find(
		(resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-postgresql",
	);
	const backupIngress = list(get(policy, "spec", "ingress")).find(
		(rule) => get(rule, "ports", 0, "port") === 8432,
	);
	assert.equal(
		get(backupIngress, "from", 0, "podSelector", "matchLabels", "app.kubernetes.io/component"),
		"postgresql-backup",
	);
	assert.deepEqual(get(policy, "spec", "policyTypes"), ["Ingress", "Egress"]);
	// The backup Jobs reach the TLS server through PostgreSQL's Service.
	const service = resources.find(
		(resource) => kindOf(resource) === "Service" && nameOf(resource) === "heph-postgresql",
	);
	assert.deepEqual(
		list(get(service, "spec", "ports")).map((port) => get(port, "port")),
		[5432, 8432],
	);
	// A change of the repository restarts PostgreSQL, whose archive command reads it.
	const checksum = (layers: readonly Layer[]) =>
		get(
			workload(rendered(layers), "postgresql"),
			"spec",
			"template",
			"metadata",
			"annotations",
			"checksum/backup",
		);
	const bucket = {
		postgresql: { backup: { enabled: true, s3: { ...s3, bucket: "other-backups" } } },
	};
	assert.equal(
		typeof checksum([...layersFor("small"), { postgresql: { backup: { enabled: true, s3 } } }]),
		"string",
	);
	assert.notEqual(
		checksum([...layersFor("small"), { postgresql: { backup: { enabled: true, s3 } } }]),
		checksum([...layersFor("small"), bucket]),
	);
});

await test("a restore stops PostgreSQL and the database roles, suspends the backups, and replays to the asked time", () => {
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const enabled = [
		...layersFor("small"),
		workerLayer,
		{ postgresql: { backup: { enabled: true, s3 } } },
	];
	const time = "2026-10-24 19:25:31+00";
	const restoring = rendered([
		...enabled,
		{ postgresql: { backup: { restore: { enabled: true, time } } } },
	]);
	for (const component of ["postgresql", "server", "webhook", "worker"]) {
		assert.equal(get(workload(restoring, component), "spec", "replicas"), 0, component);
	}
	const jobs = restoring.filter((resource) =>
		["CronJob", "Job"].includes(String(kindOf(resource))),
	);
	// No stanza Job: it would reach a PostgreSQL that does not run.
	assert.deepEqual(
		jobs.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`).toSorted(byCodePoint),
		[
			"CronJob/heph-postgresql-backup-diff",
			"CronJob/heph-postgresql-backup-full",
			"Job/heph-postgresql-backup-restore-1",
		],
	);
	for (const job of jobs.filter((resource) => kindOf(resource) === "CronJob")) {
		assert.equal(get(job, "spec", "suspend"), true, nameOf(job));
	}
	const restore = jobs.find((resource) => kindOf(resource) === "Job");
	const [entry] = list(get(podSpec(restore), "containers"));
	assert.equal(
		get(
			list(get(entry, "env")).find((variable) => get(variable, "name") === "RESTORE_TIME"),
			"value",
		),
		time,
	);
	assert.ok(list(get(entry, "command")).some((part) => String(part).includes("--type=time")));
	// The restore Job alone mounts PostgreSQL's volume.
	assert.deepEqual(
		list(get(podSpec(restore), "volumes"))
			.map((volume) => get(volume, "persistentVolumeClaim", "claimName"))
			.filter((claim) => claim !== undefined),
		["data-heph-postgresql-0"],
	);

	// Ending the restore starts the release again with its backups.
	const running = rendered(enabled);
	for (const component of ["postgresql", "server", "webhook"]) {
		assert.equal(get(workload(running, component), "spec", "replicas"), 1, component);
	}
	for (const job of running.filter((resource) => kindOf(resource) === "CronJob")) {
		assert.equal(get(job, "spec", "suspend"), false, nameOf(job));
	}
	assert.equal(named(running, "Job", "heph-postgresql-backup-restore-1"), false);
});

const mountsS3Ca = (spec: unknown) =>
	list(get(spec, "volumes")).some((volume) =>
		list(get(volume, "secret", "items")).some((item) => get(item, "key") === "s3-ca.crt"),
	);

await test("a private S3 CA reaches every Pod that reads the repository, and no other", () => {
	const s3 = {
		bucket: "hephaestus-backups",
		endpoint: "s3.internal",
		region: "local",
		privateCa: true,
	};
	const resources = rendered([
		...layersFor("small"),
		workerLayer,
		{ postgresql: { backup: { enabled: true, s3 } } },
	]);
	const config = resources.find(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "postgresql-backup",
	);
	for (const file of ["server.conf", "client.conf"]) {
		assert.match(
			String(get(config, "data", file)),
			/^repo1-storage-ca-file=\/etc\/hephaestus\/s3-ca\/s3-ca\.crt$/mu,
			file,
		);
	}
	const readers = [
		...workloads(resources).map((resource) => ({
			name: String(componentOf(resource)),
			spec: podSpec(resource),
		})),
		...resources
			.filter((resource) => kindOf(resource) === "CronJob")
			.map((resource) => ({
				name: nameOf(resource),
				spec: get(resource, "spec", "jobTemplate", "spec", "template", "spec"),
			})),
		...resources
			.filter((resource) => kindOf(resource) === "Job")
			.map((resource) => ({ name: nameOf(resource), spec: podSpec(resource) })),
	];
	assert.deepEqual(
		readers
			.filter((reader) => mountsS3Ca(reader.spec))
			.map((reader) => reader.name)
			.toSorted(byCodePoint),
		[
			"heph-postgresql-backup-diff",
			"heph-postgresql-backup-full",
			"heph-postgresql-backup-stanza-1",
			"postgresql",
		],
	);
});

await test("the server requires the worker registration token exactly when a worker runs", () => {
	const optional = (layers: readonly Layer[]) =>
		get(
			env(workload(rendered(layers), "server"), "HEPHAESTUS_WORKER_HUB_TOKEN_REGISTRATION_TOKEN"),
			"valueFrom",
			"secretKeyRef",
			"optional",
		);
	assert.equal(optional(layersFor("dev")), true);
	assert.equal(optional([...layersFor("small"), workerLayer]), undefined);
	const worker = workload(rendered([...layersFor("small"), workerLayer]), "worker");
	assert.equal(
		get(
			env(worker, "HEPHAESTUS_WORKER_REGISTRATION_TOKEN"),
			"valueFrom",
			"secretKeyRef",
			"optional",
		),
		undefined,
	);
});

await test("practice reviews run exactly when a worker runs, with its Heph turn capacity", () => {
	// Without a worker, a submitted review job would wait for a worker that never comes.
	assert.equal(envValue(workload(rendered(layersFor("dev")), "server"), "AGENT_ENABLED"), "false");
	// The switch the CI clusters did not exercise: a worker without it claims no review. Every shipped
	// profile, also the ones whose worker the operator turns on.
	for (const name of profiles) {
		const resources = rendered(withWorker(name));
		for (const role of ["server", "worker"]) {
			assert.equal(envValue(workload(resources, role), "AGENT_ENABLED"), "true", `${name} ${role}`);
		}
		assert.equal(env(workload(resources, "webhook"), "AGENT_ENABLED"), undefined, name);
		// A worker without git checkout reports itself out of service, so its Deployment never rolls out.
		assert.equal(envValue(workload(resources, "worker"), "GIT_CHECKOUT_ENABLED"), "true", name);
	}
	// The worker's Heph turns and the server's Heph capacity come from one resolved worker.hephTurns. Its auto is
	// the number in the chart's table, which the worker's own auto matches.
	const table = readJsonFileSync(path.join(chart, "sandbox-pod-requests.json"));
	assert.ok(isRecord(table) && typeof table.autoHephTurns === "number");
	const auto = table.autoHephTurns;
	const capacities = (layers: readonly Layer[]) => {
		const resources = rendered(layers);
		return {
			worker: envValue(workload(resources, "worker"), "HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX"),
			server: envValue(workload(resources, "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY"),
		};
	};
	assert.deepEqual(capacities(layersFor("small")), { worker: "1", server: "1" });
	assert.deepEqual(capacities([...layersFor("small"), { worker: { hephTurns: "auto" } }]), {
		worker: String(auto),
		server: String(auto),
	});
	assert.deepEqual(
		capacities([...layersFor("small"), { worker: { replicas: 3, hephTurns: "auto" } }]),
		{
			worker: String(auto),
			server: String(3 * auto),
		},
	);
	assert.deepEqual(capacities([...layersFor("small"), { worker: { replicas: 2, hephTurns: 5 } }]), {
		worker: "5",
		server: "10",
	});
	// No Heph slot at all: the server knows no capacity, so no question says that every slot is taken.
	assert.deepEqual(capacities([...layersFor("small"), { worker: { hephTurns: 0 } }]), {
		worker: "0",
		server: undefined,
	});
	assert.equal(
		env(
			workload(rendered([...layersFor("small"), { worker: { replicas: 0 } }]), "server"),
			"HEPHAESTUS_MENTOR_TURN_CAPACITY",
		),
		undefined,
	);
	assert.equal(
		env(workload(rendered(layersFor("dev")), "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY"),
		undefined,
	);
});

/** The server's SMTP authentication and TLS settings for an email configuration. */
const mail = (email: object) => {
	const server = workload(rendered([...layersFor("small"), { config: { email } }]), "server");
	return [
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH",
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE",
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE",
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED",
	].map((name) => envValue(server, name));
};

await test("email follows the relay's TLS mode, and authenticates only with a username", () => {
	const relay = { host: "smtp.example.com", from: "hephaestus@example.com" };
	assert.deepEqual(mail({ ...relay, username: "hephaestus" }), ["true", "false", "true", "true"]);
	assert.deepEqual(mail({ ...relay, port: 465, tls: "implicit", username: "hephaestus" }), [
		"true",
		"true",
		"false",
		"false",
	]);
	// An internal relay that takes no credentials.
	assert.deepEqual(mail(relay), ["false", "false", "true", "true"]);
});

await test("the server's operator settings reach the server alone", () => {
	const settings = [
		"HEPHAESTUS_RESEARCH_ORGANIZATION",
		"HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED",
		"HEPHAESTUS_PUBLIC_ACTIVITY_REPOSITORY_VISIBILITY_MAX_AGE",
		"HEPHAESTUS_LLM_DISPLAY_CURRENCY",
		"HEPHAESTUS_AUTH_PASSKEYS_INSTANCE_ADMIN_REQUIRED",
		"HEPHAESTUS_AUTH_PASSKEYS_WORKSPACE_ADMIN_REQUIRED",
		"HEPHAESTUS_AUTH_PASSKEYS_RP_ID",
		"HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS",
		"MONITORING_RUN_ON_STARTUP",
		"MONITORING_TIMEFRAME",
		"MONITORING_SYNC_CRON",
		"MONITORING_SYNC_COOLDOWN_IN_MINUTES",
	];
	// The application's defaults under the prod profile (application.yml, application-prod.yml).
	const defaults = workload(rendered(layersFor("small")), "server");
	assert.deepEqual(
		settings.map((name) => envValue(defaults, name)),
		["", "false", "48h", "", "true", "false", "", "", "true", "7", "0 0 * * * *", "60"],
	);
	const resources = rendered([
		...layersFor("small"),
		{
			config: {
				researchOrganization: "A research group",
				publicActivity: { enabled: true, repositoryVisibilityMaxAge: "12h" },
				llmDisplayCurrency: "EUR",
				auth: {
					passkeys: {
						instanceAdminRequired: false,
						workspaceAdminRequired: true,
						rpId: "example.com",
						allowedOrigins: "https://hephaestus.example.com",
					},
				},
				monitoring: {
					runOnStartup: false,
					timeframeDays: 14,
					syncCron: "0 0 3 * * *",
					cooldownMinutes: 15,
				},
			},
		},
	]);
	assert.deepEqual(
		settings.map((name) => envValue(workload(resources, "server"), name)),
		[
			"A research group",
			"true",
			"12h",
			"EUR",
			"false",
			"true",
			"example.com",
			"https://hephaestus.example.com",
			"false",
			"14",
			"0 0 3 * * *",
			"15",
		],
	);
	for (const role of ["webhook", "worker"]) {
		for (const name of settings) {
			assert.equal(env(workload(resources, role), name), undefined, `${role} ${name}`);
		}
	}
});

/** A Kubernetes quantity in bytes or in cores. */
const amount = (value: unknown): number => {
	const text = typeof value === "string" || typeof value === "number" ? String(value) : "0";
	const units: Record<string, number> = {
		m: 1e-3,
		Ki: 2 ** 10,
		Mi: 2 ** 20,
		Gi: 2 ** 30,
		Ti: 2 ** 40,
	};
	const match = /^(?<number>[0-9.]+)(?<unit>[A-Za-z]*)$/u.exec(text);
	assert.ok(match?.groups, `a quantity: ${text}`);
	const { number = "0", unit = "" } = match.groups;
	return Number(number) * (units[unit] ?? 1);
};
/** What one Pod requests: its containers together, or its largest init container when that is more. */
const podRequests = (spec: unknown) => {
	const sum = (entries: unknown[], key: string) =>
		entries.reduce<number>(
			(total, entry) => total + amount(get(entry, "resources", "requests", key)),
			0,
		);
	const most = (entries: unknown[], key: string) =>
		Math.max(0, ...entries.map((entry) => amount(get(entry, "resources", "requests", key))));
	const running = list(get(spec, "containers"));
	const init = list(get(spec, "initContainers"));
	return {
		cpu: Math.max(sum(running, "cpu"), most(init, "cpu")),
		memory: Math.max(sum(running, "memory"), most(init, "memory")),
	};
};
/** The Pods that a rollout adds beside the old ones: maxSurge, 25 % by default, none for Recreate. */
const surge = (deployment: Resource) => {
	const replicas = Number(get(deployment, "spec", "replicas") ?? 1);
	if (get(deployment, "spec", "strategy", "type") === "Recreate") {
		return 0;
	}
	const configured = get(deployment, "spec", "strategy", "rollingUpdate", "maxSurge");
	const value =
		typeof configured === "string" || typeof configured === "number" ? String(configured) : "25%";
	return value.endsWith("%")
		? Math.ceil((replicas * Number(value.slice(0, -1))) / 100)
		: Number(value);
};

const gib = (bytes: number) => (bytes / 2 ** 30).toFixed(1);

await test("the single-node profile's requests, one upgrade beside them and its sandbox quota fit the node it states", () => {
	// The node of profiles/small.yaml and docs/admin/kubernetes.mdx § Profiles, less what k3s requests
	// itself, and less the disk that images and the review workspaces use: sandboxes have no disk limit.
	const node = { cpu: 4 - 0.25, memory: 31 * 2 ** 30, disk: (150 - 40) * 2 ** 30 };
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small"),
		workerLayer,
		{ postgresql: { backup: { enabled: true, s3 } } },
	]);
	let cpu = 0;
	let memory = 0;
	let disk = 0;
	const add = (spec: unknown, count: number) => {
		const requests = podRequests(spec);
		cpu += requests.cpu * count;
		memory += requests.memory * count;
		// A Pod's node disk: its containers' ephemeral-storage limits, which bound their emptyDir caches too.
		disk +=
			list(get(spec, "containers")).reduce<number>(
				(total, entry) => total + amount(get(entry, "resources", "limits", "ephemeral-storage")),
				0,
			) * count;
	};
	for (const resource of workloads(resources)) {
		const replicas = Number(get(resource, "spec", "replicas") ?? 1);
		// An upgrade runs the old Pods beside the new ones: a draining worker until its drain ends.
		add(podSpec(resource), replicas + (kindOf(resource) === "Deployment" ? surge(resource) : 0));
		for (const claim of list(get(resource, "spec", "volumeClaimTemplates"))) {
			disk += amount(get(claim, "spec", "resources", "requests", "storage"));
		}
	}
	// The migration Job and the stanza Job run beside the upgrade, and one backup at a time.
	for (const job of resources.filter((resource) => kindOf(resource) === "Job")) {
		add(podSpec(job), 1);
	}
	const backup = resources.find((resource) => kindOf(resource) === "CronJob");
	add(get(backup, "spec", "jobTemplate", "spec", "template", "spec"), 1);
	// Every sandbox Pod is inside the quota: reviews, Heph hosts, turn sandboxes and probe Pods. They
	// request their memory limit and a tenth of their CPU limit (KubernetesPods.cpuRequest).
	const quota = resources.find((resource) => kindOf(resource) === "ResourceQuota");
	cpu += amount(get(quota, "spec", "hard", "limits.cpu")) / 10;
	memory += amount(get(quota, "spec", "hard", "limits.memory"));
	assert.equal(get(workload(resources, "worker"), "spec", "replicas"), 1);
	assert.ok(cpu <= node.cpu, `${cpu.toFixed(2)} CPU requested, ${node.cpu} on the node`);
	assert.ok(
		memory <= node.memory,
		`${gib(memory)} GiB requested, ${gib(node.memory)} GiB on the node`,
	);
	assert.ok(disk <= node.disk, `${gib(disk)} GiB of disk, ${gib(node.disk)} GiB on the node`);
	process.stdout.write(
		`single-node budget: ${cpu.toFixed(2)} CPU, ${gib(memory)} GiB, ${gib(disk)} GiB disk\n`,
	);
});

/** The memory of the sandboxes of docs/admin/runtime-roles.mdx, in GiB: they keep their full size. */
const sandboxGib = { review: 3, hephHost: 2, turn: 1, pooledTurns: 2, probe: 1.5 };

for (const name of ["small", "small-16gib"] as const) {
	await test(`${name}: the sandbox quota holds the reviews, Heph turns and pooled turn sandboxes that its workers may run`, () => {
		const resources = rendered(layersFor(name));
		const worker = workload(resources, "worker");
		assert.ok(worker, "the profile runs a worker");
		// A rollout by surge runs the old workers' Heph hosts and turn sandboxes beside the new ones; the
		// quota admits one review between them. Recreate never runs two workers.
		const workers = Number(get(worker, "spec", "replicas")) + surge(worker);
		const turns = Number(envValue(worker, "HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX"));
		assert.ok(Number.isInteger(turns), "the profile bounds the Heph turns");
		const perWorker = sandboxGib.hephHost + (sandboxGib.pooledTurns + turns) * sandboxGib.turn;
		const sandboxes = (sandboxGib.review + workers * perWorker + sandboxGib.probe) * 2 ** 30;
		const quota = resources.find((resource) => kindOf(resource) === "ResourceQuota");
		const hard = get(quota, "spec", "hard", "limits.memory");
		assert.ok(
			sandboxes <= amount(hard),
			`${gib(sandboxes)} GiB of sandboxes, a quota of ${String(hard)}`,
		);
	});
}

const strategy = (name: (typeof profiles)[number], component: string) =>
	get(workload(rendered(layersFor(name)), component), "spec", "strategy");

await test("workers roll by surge unless the profile asks for Recreate, and the webhook always surges", () => {
	assert.deepEqual(strategy("small", "worker"), {
		type: "RollingUpdate",
		rollingUpdate: { maxSurge: "100%", maxUnavailable: 0 },
	});
	assert.deepEqual(strategy("small-16gib", "worker"), { type: "Recreate" });
	// Push events are not redeliverable: the new receiver starts before the old one stops.
	for (const name of ["small", "small-16gib"] as const) {
		assert.deepEqual(strategy(name, "webhook"), {
			type: "RollingUpdate",
			rollingUpdate: { maxUnavailable: 0, maxSurge: 1 },
		});
	}
});

interface Budget {
	cpu: number;
	memory: number;
}
/** The requests of Pods, each with its count. */
const total = (pods: [unknown, number][]): Budget => {
	const budget = { cpu: 0, memory: 0 };
	for (const [spec, count] of pods) {
		const requests = podRequests(spec);
		budget.cpu += requests.cpu * count;
		budget.memory += requests.memory * count;
	}
	return budget;
};
/** Sandboxes request their memory limit and a tenth of their CPU limit of one core. */
const sandboxes = (gibs: number, pods: number): Budget => ({
	cpu: pods / 10,
	memory: gibs * 2 ** 30,
});
const sum = (...parts: Budget[]): Budget => {
	const budget = { cpu: 0, memory: 0 };
	for (const part of parts) {
		budget.cpu += part.cpu;
		budget.memory += part.memory;
	}
	return budget;
};

await test("the 16 GiB profile runs a review and a Heph turn, and an upgrade, within one node of 16 GiB", () => {
	// MemTotal of the qualified node, less what k3s requests itself.
	const node = { cpu: 4 - 0.25, memory: 16_372_036 * 1024 - 0.25 * 2 ** 30 };
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small-16gib"),
		{ postgresql: { backup: { enabled: true, s3 } } },
	]);
	const worker = workload(resources, "worker");
	assert.ok(worker, "the profile runs a worker");
	assert.equal(get(worker, "spec", "replicas"), 1);
	const turns = Number(envValue(worker, "HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX"));
	const backup = get(
		resources.find((resource) => kindOf(resource) === "CronJob"),
		"spec",
		"jobTemplate",
		"spec",
		"template",
		"spec",
	);
	const roles = workloads(resources).map((resource): [unknown, number] => [
		podSpec(resource),
		Number(get(resource, "spec", "replicas") ?? 1),
	]);
	// Steady: every role, one backup, one review, the Heph host, the pooled turn sandboxes and one turn each running.
	const steady = sum(
		total([...roles, [backup, 1]]),
		sandboxes(
			sandboxGib.review + sandboxGib.hephHost + (sandboxGib.pooledTurns + turns) * sandboxGib.turn,
			2 + sandboxGib.pooledTurns + turns,
		),
	);
	assert.ok(steady.cpu <= node.cpu, `steady: ${steady.cpu.toFixed(2)} CPU of ${node.cpu}`);
	assert.ok(
		steady.memory <= node.memory,
		`steady: ${gib(steady.memory)} of ${gib(node.memory)} GiB`,
	);
	// The isolation probe may wait for room then: a probe whose Pods cannot start keeps the last proof.
	const probing = sum(steady, sandboxes(sandboxGib.probe, 3));
	// An upgrade: the old worker is gone (Recreate), the webhook and webapp surge, the server restarts in
	// place, and the migration and stanza Jobs run. The new worker must pass its probe at its start, while
	// the old worker's pooled turn sandboxes wait for the reconciler; the new pool waits for room.
	const jobs = resources
		.filter((resource) => kindOf(resource) === "Job")
		.map((job): [unknown, number] => [podSpec(job), 1]);
	const upgrade = sum(
		total([
			...workloads(resources).map((resource): [unknown, number] => [
				podSpec(resource),
				Number(get(resource, "spec", "replicas") ?? 1) +
					(kindOf(resource) === "Deployment" ? surge(resource) : 0),
			]),
			...jobs,
		]),
		sandboxes(
			sandboxGib.probe + sandboxGib.pooledTurns * sandboxGib.turn,
			3 + sandboxGib.pooledTurns,
		),
	);
	assert.ok(upgrade.cpu <= node.cpu, `upgrade: ${upgrade.cpu.toFixed(2)} CPU of ${node.cpu}`);
	assert.ok(
		upgrade.memory <= node.memory,
		`upgrade: ${gib(upgrade.memory)} of ${gib(node.memory)} GiB`,
	);
	process.stdout.write(
		`16 GiB budget of ${gib(node.memory)} GiB: steady ${gib(steady.memory)} GiB ${steady.cpu.toFixed(2)} CPU, with the probe ${gib(probing.memory)} GiB, upgrade ${gib(upgrade.memory)} GiB ${upgrade.cpu.toFixed(2)} CPU\n`,
	);
});

await test("roles outrank conversations, which outrank reviews, and a review never preempts", () => {
	const resources = rendered(layersFor("small"));
	const classes = new Map(
		resources
			.filter((resource) => kindOf(resource) === "PriorityClass")
			.map((resource) => [String(get(resource, "metadata", "name")), resource]),
	);
	const worker = workload(resources, "worker");
	const tier = (variable: string) => classes.get(String(envValue(worker, variable)));
	const conversations = tier("HEPHAESTUS_SANDBOX_KUBERNETES_CONVERSATION_PRIORITY_CLASS");
	const reviewClass = tier("HEPHAESTUS_SANDBOX_KUBERNETES_REVIEW_PRIORITY_CLASS");
	assert.ok(conversations && reviewClass, "the worker names classes that the chart creates");
	const roleClasses = new Set(
		[...workloads(resources), ...resources.filter((resource) => kindOf(resource) === "Job")].map(
			(resource) => get(podSpec(resource), "priorityClassName"),
		),
	);
	assert.equal(roleClasses.size, 1, "every role Pod has the same class");
	const roles = classes.get(String([...roleClasses][0]));
	assert.ok(roles, "the roles' class exists");
	const value = (priority: unknown) => Number(get(priority, "value"));
	assert.ok(value(roles) > value(conversations) && value(conversations) > value(reviewClass));
	assert.ok(value(roles) < 1_000_000_000, "below the system classes");
	assert.equal(get(reviewClass, "preemptionPolicy"), "Never");
	assert.equal(classes.size, 3);
	for (const priority of classes.values()) {
		assert.equal(get(priority, "globalDefault"), false);
	}
	// Cluster-wide names: another release or namespace gets its own.
	const inNamespace = (namespace: string) =>
		rendered(layersFor("small"), { release: "hephaestus", namespace })
			.filter((resource) => kindOf(resource) === "PriorityClass")
			.map((resource) => String(get(resource, "metadata", "name")));
	const elsewhere = new Set(inNamespace("other"));
	assert.ok(inNamespace("hephaestus").every((name) => !elsewhere.has(name)));
	const withoutWorker = rendered(layersFor("dev")).filter(
		(resource) => kindOf(resource) === "PriorityClass",
	);
	assert.equal(withoutWorker.length, 1, "no sandbox classes without a worker");
});

await test("on a shared cluster, the chart uses the operator's PriorityClasses and creates none", () => {
	const operators = {
		roles: "platform-high",
		conversations: "people-waiting",
		reviews: "batch-low",
	};
	const resources = rendered([
		...layersFor("small"),
		{ priorityClasses: { create: false, ...operators } },
	]);
	assert.equal(resources.filter((each) => kindOf(each) === "PriorityClass").length, 0);
	for (const pod of [
		...workloads(resources),
		...resources.filter((each) => kindOf(each) === "Job"),
	]) {
		assert.equal(get(podSpec(pod), "priorityClassName"), operators.roles, nameOf(pod));
	}
	const worker = workload(resources, "worker");
	assert.equal(
		envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_CONVERSATION_PRIORITY_CLASS"),
		operators.conversations,
	);
	assert.equal(
		envValue(worker, "HEPHAESTUS_SANDBOX_KUBERNETES_REVIEW_PRIORITY_CLASS"),
		operators.reviews,
	);
});

await test("every role that runs git writes the JGit configuration to an emptyDir, in every profile", () => {
	// The root filesystem is read-only, so JGit's default $HOME/.config cannot hold its config lock.
	for (const [name, role] of profiles.flatMap((shipped) =>
		(["server", "worker"] as const).map((candidate) => [shipped, candidate] as const),
	)) {
		const pod = workload(rendered(withWorker(name)), role);
		const home = String(envValue(pod, "XDG_CONFIG_HOME"));
		const emptyDirs = new Set(
			list(get(podSpec(pod), "volumes"))
				.filter((volume) => get(volume, "emptyDir") !== undefined)
				.map((volume) => get(volume, "name")),
		);
		const writable = list(get(container(pod), "volumeMounts")).filter(
			(mount) => emptyDirs.has(get(mount, "name")) && get(mount, "readOnly") !== true,
		);
		assert.ok(
			writable.some((mount) => home.startsWith(`${String(get(mount, "mountPath"))}/`)),
			`${name} ${role}: XDG_CONFIG_HOME ${home} is not on a writable emptyDir`,
		);
	}
});

const podTemplate = (layers: readonly Layer[], component: string) =>
	get(workload(rendered(layers), component), "spec", "template");

await test("a release that changes neither image restarts neither PostgreSQL nor NATS, in every profile", () => {
	// A Pod template that names the release would roll both on every upgrade. The application roles
	// report the release they run, so their Pods carry it on purpose.
	const next = { release: { version: "v1.2.4", commit: "b".repeat(40) } };
	for (const name of profiles.filter((candidate) => bundled(candidate))) {
		for (const component of ["postgresql", "nats"]) {
			assert.deepEqual(
				podTemplate([...withWorker(name), next], component),
				podTemplate(withWorker(name), component),
				`${name} ${component}`,
			);
		}
	}
});

await test("a followed commit reports the commit as its version, as the promotion verifies", () => {
	const commit = "c".repeat(40);
	const resources = rendered([...layersFor("small"), { release: { version: commit, commit } }]);
	assert.equal(envValue(workload(resources, "webapp"), "APPLICATION_VERSION"), commit);
	assert.equal(envValue(workload(resources, "server"), "APP_VERSION"), commit);
});

await test("every revision migrates the database once, in a Job that only reaches PostgreSQL", () => {
	const first = rendered([...layersFor("small"), workerLayer]);
	const migration = first.find(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
	);
	assert.ok(migration, "one migration Job");
	assert.equal(nameOf(migration), "heph-migration-1");
	assert.equal(get(migration, "spec", "backoffLimit"), 0);
	const entry = container(migration);
	assert.equal(get(entry, "image"), get(container(workload(first, "server")), "image"));
	assert.equal(
		get(
			list(get(entry, "env")).find(
				(variable) => get(variable, "name") === "SPRING_PROFILES_ACTIVE",
			),
			"value",
		),
		"prod,migrate",
	);
	assert.deepEqual(
		list(get(entry, "env")).map((variable) => get(variable, "name")),
		["SPRING_PROFILES_ACTIVE", "DATABASE_URL", "DATABASE_USERNAME", "DATABASE_PASSWORD"],
	);
	// It starts once PostgreSQL accepts connections: on a first install, both start together.
	const [wait] = list(get(podSpec(migration), "initContainers"));
	const waitEnv = (name: string) =>
		list(get(wait, "env")).find((variable) => get(variable, "name") === name);
	assert.equal(get(waitEnv("DATABASE_HOST"), "value"), "heph-postgresql.hephaestus.svc");
	assert.equal(get(waitEnv("DATABASE_PORT"), "value"), "5432");
	// pg_isready runs as a user that the image's passwd does not name: without PGUSER it never dials.
	assert.deepEqual(get(waitEnv("PGUSER"), "valueFrom", "secretKeyRef"), {
		name: secretName,
		key: "DATABASE_USERNAME",
	});
	assert.match(String(list(get(wait, "command")).at(-1)), /pg_isready/u);
	// No Helm hook: the Job is part of the release, so `--wait-for-jobs` waits for it.
	assert.equal(get(migration, "metadata", "annotations", "helm.sh/hook"), undefined);
	const policy = first.find(
		(resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-migration",
	);
	assert.deepEqual(get(policy, "spec", "policyTypes"), ["Ingress", "Egress"]);
	assert.equal(list(get(policy, "spec", "egress")).length, 1);
	assert.equal(get(policy, "spec", "egress", 0, "ports", 0, "port"), 5432);
	// The server waits for the schema; it does not migrate.
	assert.equal(envValue(workload(first, "server"), "SPRING_PROFILES_ACTIVE"), "prod");
	// A restore stops every database role, and nothing migrates the restored database.
	const restoring = rendered([
		...layersFor("small"),
		{
			postgresql: {
				backup: {
					enabled: true,
					s3: { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" },
					restore: { enabled: true },
				},
			},
		},
	]);
	assert.equal(
		restoring.some(
			(resource) => componentOf(resource) === "migration" && kindOf(resource) === "Job",
		),
		false,
	);
});

await test("the JVM roles request the memory that they may use", () => {
	const resources = rendered([...layersFor("small"), workerLayer]);
	for (const component of ["server", "webhook", "worker"]) {
		const resourcesOf = get(container(workload(resources, component)), "resources");
		assert.equal(
			get(resourcesOf, "requests", "memory"),
			get(resourcesOf, "limits", "memory"),
			component,
		);
	}
});

await test("every role verifies an external PostgreSQL with the CA alone", () => {
	const resources = rendered(layersFor("external"));
	assert.equal(
		workloads(resources).some((resource) => componentOf(resource) === "postgresql"),
		false,
	);
	const url = `postgresql://${external.postgresql.external.host}:5432/hephaestus?${verifyFull}`;
	for (const component of ["server", "webhook"]) {
		const role = workload(resources, component);
		assert.ok(role, component);
		assert.equal(envValue(role, "DATABASE_URL"), url, component);
		assertCaOnly(role, component);
	}
});

await test("replica counts stop the database roles, start the server alone, and restore the release", () => {
	const roles = ["server", "webhook", "worker"] as const;
	const counts = (resources: Resource[]) =>
		roles.map((component) => get(workload(resources, component), "spec", "replicas"));
	const enabled = [...layersFor("small"), workerLayer];
	const running = rendered(enabled);
	assert.deepEqual(counts(running), [1, 1, 1]);

	// Stopped: the objects stay, so the workers keep their Deployment and its policies.
	const stopped = rendered([
		...enabled,
		{ server: { replicas: 0 }, webhook: { replicas: 0 }, worker: { replicas: 0 } },
	]);
	assert.deepEqual(counts(stopped), [0, 0, 0]);
	assert.deepEqual(
		stopped.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`),
		running.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`),
	);
	assert.equal(get(workload(stopped, "server"), "spec", "strategy", "type"), "Recreate");
	for (const component of ["postgresql", "nats", "webapp"]) {
		assert.equal(get(workload(stopped, component), "spec", "replicas"), 1, component);
	}

	// The server migrates alone before the roles that read its schema start again.
	const migrating = rendered([...enabled, { webhook: { replicas: 0 }, worker: { replicas: 0 } }]);
	assert.deepEqual(counts(migrating), [1, 0, 0]);

	// Restoring the prior counts renders the release as it was.
	const restored = rendered([
		...enabled,
		{ server: { replicas: 1 }, webhook: { replicas: 1 }, worker: { replicas: 1 } },
	]);
	assert.deepEqual(restored, running);

	// A count does not create a worker that is not enabled.
	const disabled = rendered([...layersFor("small"), { worker: { enabled: false, replicas: 1 } }]);
	assert.equal(
		disabled.some((resource) => componentOf(resource) === "worker"),
		false,
	);
});

await test("values that the chart does not accept fail the render", () => {
	const withoutPrepared = [profile("small"), operator];
	const { host: _host, ...withoutHost } = operator;
	const { secret: _secret, ...withoutSecret } = operator;
	const { trustedProxies: _trustedProxies, ...withoutProxies } = operator;
	const worker = (change: object): Layer[] => [...layersFor("small"), workerLayer, change];
	const cases: [string, Layer[], RegExp][] = [
		[
			"an image tag",
			[...layersFor("small"), { images: { webapp: "ghcr.io/hephaestus-build/webapp:latest" } }],
			/images[./]webapp/u,
		],
		["values without the prepared lock file", withoutPrepared, /images|release/u],
		[
			"a generic environment hatch",
			[...layersFor("small"), { server: { extraEnv: [{ name: "X", value: "y" }] } }],
			/extraEnv/u,
		],
		[
			"a pod spec hatch",
			[...layersFor("small"), { webapp: { podSpec: { hostNetwork: true } } }],
			/podSpec/u,
		],
		[
			"network policy turned off",
			[...layersFor("small"), { networkPolicy: { enabled: false } }],
			/enabled/u,
		],
		["no Gateway parent", [...layersFor("small"), { route: { parentRefs: [] } }], /parentRefs/u],
		[
			"a Gateway parent without a name",
			[...layersFor("small"), { route: { parentRefs: [{ name: "" }] } }],
			/parentRefs[./]0[./]name/u,
		],
		["no host", [profile("small"), prepared, withoutHost], /host/u],
		["no Secret name", [profile("small"), prepared, withoutSecret], /secret[./]name/u],
		["no proxy trust", [profile("small"), prepared, withoutProxies], /trustedProxies/u],
		// Helm merges maps, so only null removes the labels that an earlier layer set.
		[
			"a whole Gateway namespace",
			[...layersFor("small"), { networkPolicy: { gateway: { podLabels: null } } }],
			/podLabels/u,
		],
		[
			"a whole scraper namespace",
			[
				...layersFor("small"),
				{ networkPolicy: { metrics: { namespace: "monitoring", podLabels: {} } } },
			],
			/networkPolicy\.metrics\.podLabels needs at least one label/u,
		],
		[
			"a zero CPU limit",
			[...layersFor("small"), { server: { resources: { limits: { cpu: "0" } } } }],
			/cpu/u,
		],
		[
			"an external database without a host",
			[
				profile("small"),
				prepared,
				operator,
				reviews,
				{ ...external, postgresql: { bundled: false, external: { cidr: "10.0.12.0/24" } } },
			],
			/postgresql\.external\.host is required/u,
		],
		// The single-node profile runs reviews, so an install that skips either sandbox value stops.
		[
			"the single-node profile without a sandbox namespace",
			[profile("small"), prepared, operator, { networkPolicy: { kubernetesApi } }],
			/worker\.sandbox\.namespace is required/u,
		],
		[
			"the single-node profile without the Kubernetes API endpoint",
			[
				profile("small"),
				prepared,
				operator,
				{ worker: { sandbox: { namespace: sandboxNamespace } } },
			],
			/networkPolicy\.kubernetesApi\.cidr is required/u,
		],
		[
			"a second server",
			[...layersFor("small"), { server: { replicas: 2 } }],
			/server[./]replicas/u,
		],
		["a fourth webhook receiver", worker({ webhook: { replicas: 4 } }), /webhook[./]replicas/u],
		["a negative webhook count", worker({ webhook: { replicas: -1 } }), /webhook[./]replicas/u],
		["a seventeenth worker", worker({ worker: { replicas: 17 } }), /worker[./]replicas/u],
		[
			"no PostgreSQL TLS Secret",
			[...layersFor("small"), { postgresql: { tls: { existingSecret: "" } } }],
			/postgresql\.tls\.existingSecret is required/u,
		],
		[
			"no PostgreSQL TLS Secret for an external database",
			[...layersFor("external"), { postgresql: { tls: { existingSecret: "" } } }],
			/postgresql\.tls\.existingSecret is required/u,
		],
		[
			"an external database without certificate verification",
			[...layersFor("external"), { postgresql: { external: { sslmode: "require" } } }],
			/sslmode/u,
		],
		[
			"an external database in plain text",
			[...layersFor("external"), { postgresql: { external: { sslmode: "disable" } } }],
			/sslmode/u,
		],
		[
			"a worker without the API port",
			worker({ networkPolicy: { kubernetesApi: { port: 0 } } }),
			/networkPolicy\.kubernetesApi\.port is required/u,
		],
		[
			"a worker without its sandbox namespace",
			worker({ worker: { sandbox: { namespace: "" } } }),
			/worker\.sandbox\.namespace is required/u,
		],
		[
			"sandboxes in the release namespace",
			worker({ worker: { sandbox: { namespace: "hephaestus" } } }),
			/worker\.sandbox\.namespace must not be the release namespace/u,
		],
		[
			"a backup of an external database",
			[
				...layersFor("external"),
				{
					postgresql: {
						backup: {
							enabled: true,
							s3: { bucket: "backups", endpoint: "s3.example.com", region: "eu" },
						},
					},
				},
			],
			/postgresql\.backup\.enabled needs postgresql\.bundled/u,
		],
		[
			"a backup without its bucket",
			[...layersFor("small"), { postgresql: { backup: { enabled: true } } }],
			/postgresql\.backup\.s3\.bucket is required/u,
		],
		[
			"a seccomp profile outside the kubelet's directory",
			worker({ worker: { runtime: { seccompProfile: "../agent.json" } } }),
			/worker\.runtime\.seccompProfile must stay inside/u,
		],
	];
	for (const [label, layers, message] of cases) {
		const result = render(layers);
		assert.equal(result.ok, false, `${label} rendered`);
		assert.match(result.stderr, message, label);
	}
});

await test("a model endpoint on a private network reaches the server and the worker, never the metadata range", () => {
	const endpoint = { cidr: "10.20.0.0/16", ports: [8000] };
	const resources = rendered([
		...layersFor("small"),
		{
			config: {
				llmPrivateHosts: ["vllm.lab.example", "10.20.0.0/16"],
				llmPrivateHostsAllowHttp: true,
			},
			networkPolicy: { modelEndpoints: [endpoint] },
		},
	]);
	const rule = {
		to: [{ ipBlock: { cidr: endpoint.cidr } }],
		ports: [{ port: 8000, protocol: "TCP" }],
	};
	for (const component of ["server", "worker", "webhook"]) {
		const role = workload(resources, component);
		const calls = component !== "webhook";
		assert.equal(
			envValue(role, "HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS"),
			calls ? "vllm.lab.example,10.20.0.0/16" : undefined,
			component,
		);
		assert.equal(
			envValue(role, "HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS_ALLOW_HTTP"),
			calls ? "true" : undefined,
		);
		const policy = resources.find(
			(resource) =>
				kindOf(resource) === "NetworkPolicy" &&
				get(resource, "metadata", "namespace") === undefined &&
				isDeepStrictEqual(
					get(resource, "spec", "podSelector", "matchLabels"),
					get(role, "spec", "selector", "matchLabels"),
				),
		);
		const egress = list(get(policy, "spec", "egress"));
		assert.equal(
			egress.some((candidate) => isDeepStrictEqual(candidate, rule)),
			calls,
			component,
		);
	}
	for (const refused of [
		{ cidr: "169.254.0.0/16", ports: [80] },
		{ cidr: "0.0.0.0/0", ports: [443] },
		{ cidr: "10.0.0.0/7", ports: [443] },
		// fd::/7 is 00fd::/7, the range that holds ::1.
		{ cidr: "fd::/7", ports: [443] },
		{ cidr: "10.20.0.0/16", ports: [] },
	]) {
		const result = render([
			...layersFor("small"),
			{ networkPolicy: { modelEndpoints: [refused] } },
		]);
		assert.equal(result.ok, false, `${refused.cidr} ${JSON.stringify(refused.ports)} rendered`);
		assert.match(result.stderr, /modelEndpoints/u, refused.cidr);
	}
});
