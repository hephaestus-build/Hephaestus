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
 * The sandboxes render only with the names of what an operator qualified on the sandbox nodes. These
 * cases pass synthetic names: a render proves which names and which authority the server receives,
 * never that a node loads a profile or enforces a process limit or a policy.
 */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { appendFile, cp, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";

import { parseAllDocuments, stringify } from "yaml";

import { bundle as generatedBundle, CONTROLLER } from "./cluster-admin-bundle.ts";
import { grants as ruleGrants } from "./cluster-preflight.ts";
import { parseInventory } from "./commit-image-lock.ts";
import { chartValues } from "./kubernetes-chart.ts";
import { pressureNodeTaint, priorityClassResources } from "./kubernetes-memory-drill.ts";
import { asArray, asRecord, isRecord, parseJson, readJsonFileSync } from "./lib/json.ts";
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
const sandboxRuntime = {
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
const sandboxLayer = {
	sandbox: { enabled: true, runtime: sandboxRuntime, namespace: sandboxNamespace },
	networkPolicy: { kubernetesApi },
};
/** What the operator states for the single-node profile's sandboxes (docs/admin/kubernetes.mdx § Install). */
const reviews = {
	sandbox: { namespace: sandboxNamespace },
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

/** The values files of `layers`: a string layer is a path, and an object is written to `directory`. */
function valuesFiles(directory: string, layers: readonly Layer[]): string[] {
	return layers.map((layer, index) => {
		if (typeof layer === "string") {
			return layer;
		}
		const file = path.join(directory, `values-${index}.yaml`);
		writeFileSync(file, stringify(layer));
		return file;
	});
}

/** One `helm template` run of the chart source, or of `source`. */
function render(
	layers: readonly Layer[],
	{ release = "heph", namespace = "hephaestus", source = chart } = {},
): Rendered {
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-chart-"));
	try {
		const files = valuesFiles(directory, layers);
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
/** A Pod selector of one sandbox kind. */
const sandboxKind = (value: string) => ({
	matchLabels: { "hephaestus.build/sandbox-kind": value },
});

const workloads = (resources: Resource[]) =>
	resources.filter((resource) => ["Deployment", "StatefulSet"].includes(String(kindOf(resource))));
const workload = (resources: Resource[], component: string) => {
	const [found, ...others] = workloads(resources).filter(
		(resource) => componentOf(resource) === component,
	);
	assert.ok(found !== undefined && others.length === 0, `one ${component} workload`);
	return found;
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
	// A sandbox namespace's policies select the Pods that the server's runtime creates there.
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

/** The four profiles, and the small one with an operator's external PostgreSQL and NATS. */
const profiles = ["dev", "preview", "small", "cluster", "external"] as const;
const maxFileBytes = {
	dev: 4_294_967_296,
	preview: 2_147_483_648,
	small: 8_589_934_592,
	cluster: 17_179_869_184,
	external: 8_589_934_592,
};
const layersFor = (name: (typeof profiles)[number]) => {
	switch (name) {
		case "external": {
			return [profile("small"), prepared, operator, reviews, external];
		}
		case "small":
		case "cluster": {
			return [profile(name), prepared, operator, reviews];
		}
		case "dev":
		case "preview": {
			return [profile(name), prepared, operator];
		}
	}
};
const bundled = (name: (typeof profiles)[number]) => name !== "external";
const runsReviews = (name: (typeof profiles)[number]) =>
	name === "small" || name === "cluster" || name === "external";
/** A profile as an operator installs it with reviews: CI turns on the sandboxes of the dev profile this way. */
const withSandboxes = (name: (typeof profiles)[number]) =>
	runsReviews(name) ? layersFor(name) : [...layersFor(name), sandboxLayer];
/** What the server writes into the sandbox namespace. */
const sandboxOwned = (resource: Resource) => componentOf(resource) === "sandbox";
/** The managed-cluster controller has its own Kubernetes API authority. */
const controllerOwned = (resource: Resource) =>
	componentOf(resource) === "agent-sandbox-controller";
const imageOf = {
	"conversation-loop": "agent-pi",
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
			...(name === "cluster" ? ["agent-sandbox-controller"] : []),
			...(runsReviews(name) ? ["conversation-loop"] : []),
			...(bundled(name) ? ["nats", "postgresql"] : []),
			"server",
			"webapp",
			"webhook",
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
		assert.equal(env(server, "HEPHAESTUS_RUNTIME_WORKER_ENABLED"), undefined);
		assert.equal(env(server, "SPRING_LIQUIBASE_ENABLED"), undefined, "the server owns migrations");
		assert.equal(get(server, "spec", "replicas"), 1);
		// The single-node profile cannot hold two servers; every other profile rolls without a gap.
		assert.equal(get(server, "spec", "strategy", "type"), "Recreate");
		assert.equal(envValue(server, "AGENT_ENABLED"), String(runsReviews(name)));
		assert.equal(envValue(webhook, "SPRING_PROFILES_ACTIVE"), "prod,webhook");
		assert.equal(envValue(webhook, "HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED"), "true");
		assert.equal(envValue(webhook, "HEPHAESTUS_RUNTIME_SERVER_ENABLED"), "false");
		assert.equal(env(webhook, "HEPHAESTUS_RUNTIME_WORKER_ENABLED"), undefined);
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
		// One migration owner, the revision's Job; no cluster-wide authority. Only the server holds a
		// Role, in the sandbox namespace; its own test checks that authority.
		for (const resource of resources.filter(
			(candidate) =>
				!(kindOf(candidate) === "Job" && componentOf(candidate) === "migration") &&
				!sandboxOwned(candidate) &&
				!controllerOwned(candidate),
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
		// Only a server that runs sandboxes calls the Kubernetes API, so only its Pods mount a token.
		for (const account of resources.filter(
			(resource) => kindOf(resource) === "ServiceAccount" && !controllerOwned(resource),
		)) {
			assert.equal(get(account, "automountServiceAccountToken"), false, nameOf(account));
		}
		for (const resource of workloads(resources)) {
			const label = nameOf(resource);
			const spec = podSpec(resource);
			assert.equal(
				get(spec, "automountServiceAccountToken"),
				(componentOf(resource) === "server" && runsReviews(name)) || controllerOwned(resource),
				label,
			);
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

/** The sandbox quota of one PriorityClass tier. */
const tierQuota = (resources: Resource[], tier: "reviews" | "conversations") => {
	const quota = resources.find(
		(resource) =>
			kindOf(resource) === "ResourceQuota" &&
			String(get(resource, "metadata", "name")).endsWith(`sandbox-quota-${tier}`),
	);
	assert.ok(quota, `the ${tier} quota`);
	return quota;
};

/** The sandbox namespace's quota without a scope. */
const sandboxQuota = (resources: Resource[]) => {
	const quota = resources.find(
		(resource) =>
			kindOf(resource) === "ResourceQuota" &&
			String(get(resource, "metadata", "name")).endsWith("sandbox-quota"),
	);
	assert.ok(quota, "the unscoped sandbox quota");
	return quota;
};

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

await test("a release package refuses a later quoted images key, which Helm would render", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-chart-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const source = path.join(directory, "hephaestus");
	await cp(chart, source, { recursive: true });
	const other = { ...prepared.images, webapp: `ghcr.io/other/webapp@${digest("d")}` };
	await appendFile(path.join(source, "values.yaml"), `"images": ${JSON.stringify(other)}\n`);
	await assert.rejects(
		packageReleaseChart(prepared.release.version, source, prepared, directory),
		/does not pin the v1\.2\.3 images/u,
	);
});

await test("small: a Recreate server stops and starts within the upgrade's Helm timeout", () => {
	const server = workload(rendered([...layersFor("small"), sandboxLayer]), "server");
	assert.equal(get(server, "spec", "strategy", "type"), "Recreate");
	// Past the timeout, Helm rolls the release back, also after its migration ran.
	const installer = readFileSync(
		new URL("../deploy/platform/k3s-install.sh", import.meta.url),
		"utf8",
	);
	const timeout = /--wait-for-jobs --timeout (?<minutes>\d+)m\b/u.exec(installer)?.groups?.minutes;
	const stop = Number(get(podSpec(server), "terminationGracePeriodSeconds"));
	const probe = get(container(server), "startupProbe");
	const start = Number(get(probe, "periodSeconds")) * Number(get(probe, "failureThreshold"));
	assert.ok(stop + start <= Number(timeout) * 60, `${stop} s to stop, ${start} s to start`);
	assert.ok(Number(get(server, "spec", "progressDeadlineSeconds")) >= stop + start);
});

const alertRules = readFileSync(path.join(chart, "files", "hephaestus-alerts.yml"), "utf8");
const alertRulesOn = {
	observability: {
		alertRules: { configMap: true, prometheusRule: true, labels: { release: "monitoring" } },
	},
};
const ruleResources = (resources: Resource[]) =>
	resources.filter((resource) => componentOf(resource) === "alert-rules");

await test("the alert rules render as the tested file, and only when asked", () => {
	assert.deepEqual(ruleResources(rendered(layersFor("small"))), []);
	const resources = ruleResources(rendered([...layersFor("small"), alertRulesOn]));
	assert.deepEqual(resources.map(kindOf), ["ConfigMap", "PrometheusRule"]);
	// The rule selector of the operator's Prometheus finds both by the labels that it asks for.
	for (const resource of resources) {
		assert.equal(get(resource, "metadata", "labels", "release"), "monitoring");
	}
	const [configMap, prometheusRule] = resources;
	assert.equal(get(configMap, "data", "hephaestus-alerts.yml"), alertRules);
	assert.deepEqual(
		get(prometheusRule, "spec", "groups"),
		get(parseAllDocuments(alertRules)[0]?.toJS(), "groups"),
	);
});

const stackOn = {
	observability: { stack: { enabled: true, alertmanager: { configSecret: "alertmanager" } } },
};
await test("external monitoring receives the release dashboards without a second monitoring stack", () => {
	const dashboardConfig = (resources: Resource[]) =>
		resources.filter(
			(resource) =>
				kindOf(resource) === "ConfigMap" && get(resource, "data", "overview.json") !== undefined,
		);
	assert.deepEqual(dashboardConfig(rendered(layersFor("small"))), []);
	const resources = rendered([
		...layersFor("small"),
		{
			observability: { dashboards: { namespace: "monitoring" } },
		},
	]);
	const [config] = dashboardConfig(resources);
	assert.ok(config);
	assert.equal(get(config, "metadata", "namespace"), "monitoring");
	assert.equal(nameOf(config), "heph-victoria-metrics-dashboards");
	assert.deepEqual(
		get(config, "data"),
		get(dashboardConfig(rendered([...layersFor("small"), stackOn]))[0], "data"),
	);
	assert.ok(
		!resources.some(
			(resource) =>
				componentOf(resource) === "victoria-metrics" && kindOf(resource) !== "ConfigMap",
		),
	);
	assert.ok(
		!resources.some(
			(resource) => kindOf(resource) === "Namespace" && nameOf(resource) === "monitoring",
		),
	);
});

await test("external dashboard namespace names stay strings in the rendered resource", () => {
	for (const namespace of ["123", "true"]) {
		const resources = rendered([
			...layersFor("small"),
			{ observability: { dashboards: { namespace } } },
		]);
		const config = resources.find(
			(resource) =>
				kindOf(resource) === "ConfigMap" && get(resource, "data", "overview.json") !== undefined,
		);
		assert.equal(get(config, "metadata", "namespace"), namespace);
	}
});

await test("the bundled monitoring stack accepts its own namespace explicitly", () => {
	const resources = rendered([
		...layersFor("small"),
		stackOn,
		{
			observability: { dashboards: { namespace: "hephaestus" } },
		},
	]);
	const dashboards = resources.filter(
		(resource) =>
			kindOf(resource) === "ConfigMap" && get(resource, "data", "overview.json") !== undefined,
	);
	assert.equal(dashboards.length, 1);
	assert.equal(get(dashboards[0], "metadata", "namespace"), "hephaestus");
});

await test("the bundled monitoring stack cannot mount dashboards from another namespace", () => {
	assert.throws(
		() =>
			rendered([
				...layersFor("small"),
				stackOn,
				{
					observability: { dashboards: { namespace: "monitoring" } },
				},
			]),
		/must be the release namespace/u,
	);
});

const stackComponents = ["victoria-metrics", "vmalert", "alertmanager"] as const;
const inStack = (resource: Resource) =>
	stackComponents.some((component) => component === componentOf(resource));

await test("the monitoring stack scrapes this release, evaluates its rules and notifies, all in its namespace", () => {
	const resources = rendered([...layersFor("small"), stackOn]);
	const stack = resources.filter((resource) => inStack(resource));
	for (const component of stackComponents) {
		const entry = container(workload(resources, component));
		assert.equal(get(entry, "image"), prepared.images[component], component);
	}
	// Pod discovery in the release's namespace only; nothing cluster-wide.
	assert.deepEqual(
		resources.filter((resource) => String(kindOf(resource)).startsWith("Cluster")).map(nameOf),
		[],
	);
	const role = stack.find((resource) => kindOf(resource) === "Role");
	assert.deepEqual(get(role, "rules"), [
		{ apiGroups: [""], resources: ["pods"], verbs: ["get", "list", "watch"] },
	]);
	const metricsConfigMaps = stack.filter(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "victoria-metrics",
	);
	const scrape = String(
		get(
			metricsConfigMaps.find((resource) => get(resource, "data", "scrape.yml") !== undefined),
			"data",
			"scrape.yml",
		),
	);
	assert.match(scrape, /names: \["hephaestus"\]/u);
	assert.match(scrape, /regex: "hephaestus;heph;management"/u);
	// vmui serves each dashboard file of the release, and a panel that a person reads needs a valid file.
	const dashboards = metricsConfigMaps.find(
		(resource) => get(resource, "data", "overview.json") !== undefined,
	);
	assert.equal(
		metricsConfigMaps.filter((resource) => get(resource, "data", "overview.json") !== undefined)
			.length,
		1,
	);
	assert.equal(get(dashboards, "metadata", "namespace"), "hephaestus");
	const dashboardFiles = asRecord(get(dashboards, "data"), "dashboards");
	assert.deepEqual(Object.keys(dashboardFiles).toSorted(), [
		"database.json",
		"messaging.json",
		"model-calls.json",
		"overview.json",
		"reviews-and-feedback.json",
	]);
	for (const [name, file] of Object.entries(dashboardFiles)) {
		const rows = get(parseJson(String(file)), "rows");
		assert.ok(Array.isArray(rows) && rows.length > 0, name);
		for (const panel of rows.flatMap((row) => asArray(get(row, "panels"), name))) {
			const queries = asArray(get(panel, "expr"), name);
			assert.ok(queries.length > 0, `${name}: a panel without a query`);
			assert.ok(typeof get(panel, "title") === "string", `${name}: a panel without a title`);
			assert.equal(asArray(get(panel, "alias"), name).length, queries.length, `${name}: aliases`);
		}
	}
	const metricsArgs = get(
		workload(resources, "victoria-metrics"),
		"spec",
		"template",
		"spec",
		"containers",
		0,
		"args",
	);
	assert.ok(
		Array.isArray(metricsArgs) && metricsArgs.includes("-vmui.customDashboardsPath=/dashboards"),
	);
	// vmalert evaluates the release's rules, except the groups whose exporters the stack does not run: their series
	// never exist here, and HephaestusDeployMetricsMissing, an absent() rule, would fire for ever.
	assert.deepEqual(ruleResources(resources), []);
	const vmalert = workload(resources, "vmalert");
	const ruleMap = stack.find(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "vmalert",
	);
	assert.ok(ruleMap);
	assert.equal(get(podSpec(vmalert), "volumes", 0, "configMap", "name"), nameOf(ruleMap));
	const tested = list(get(parseAllDocuments(alertRules)[0]?.toJS(), "groups"));
	const loaded = list(
		get(
			parseAllDocuments(String(get(ruleMap, "data", "hephaestus-alerts.yml")))[0]?.toJS(),
			"groups",
		),
	);
	const postgres = loaded.find((group) => get(group, "name") === "hephaestus-postgresql");
	const deadlocks = asRecord(
		list(get(postgres, "rules")).find(
			(rule) => get(rule, "alert") === "HephaestusDatabaseDeadlocks",
		),
		"deadlock rule",
	);
	assert.equal(
		deadlocks.expr,
		'sum by (namespace, datname) (increase_prometheus(pg_stat_database_deadlocks{job="postgresql"}[15m])) > 0',
	);
	// The exported rules stay valid PromQL. The bundled engine changes only this counter rollup.
	deadlocks.expr =
		'sum by (namespace, datname) (increase(pg_stat_database_deadlocks{job="postgresql"}[15m])) > 0';
	assert.deepEqual(
		loaded,
		tested.filter(
			(group) =>
				!["hephaestus-kubernetes", "hephaestus-pull-deploy"].includes(String(get(group, "name"))),
		),
	);
	assert.ok(!JSON.stringify(loaded).includes("HephaestusDeployMetricsMissing"));
	// Only VictoriaMetrics calls the API, so only it mounts a token; every stack Pod stays restricted.
	for (const component of stackComponents) {
		const spec = podSpec(workload(resources, component));
		assert.equal(get(spec, "automountServiceAccountToken"), component === "victoria-metrics");
		assert.equal(get(spec, "securityContext", "runAsNonRoot"), true, component);
		for (const volume of list(get(spec, "volumes"))) {
			assert.equal(get(volume, "hostPath"), undefined, component);
		}
		const security = get(container(workload(resources, component)), "securityContext");
		assert.equal(get(security, "readOnlyRootFilesystem"), true, component);
		assert.deepEqual(get(security, "capabilities", "drop"), ["ALL"], component);
	}
	// Each role admits the scraper on its management port, and nothing else of the stack.
	const metricsPeer = {
		podSelector: {
			matchLabels: {
				"app.kubernetes.io/name": "hephaestus",
				"app.kubernetes.io/instance": "heph",
				"app.kubernetes.io/component": "victoria-metrics",
			},
		},
	};
	for (const component of ["server", "webhook"]) {
		const policy = resources.find(
			(resource) =>
				kindOf(resource) === "NetworkPolicy" &&
				isDeepStrictEqual(
					get(resource, "spec", "podSelector", "matchLabels", "app.kubernetes.io/component"),
					component,
				),
		);
		assert.ok(
			list(get(policy, "spec", "ingress")).some(
				(rule) =>
					isDeepStrictEqual(get(rule, "from"), [metricsPeer]) &&
					isDeepStrictEqual(get(rule, "ports"), [{ port: "management", protocol: "TCP" }]),
			),
			component,
		);
	}
});

await test("the monitoring stack is off by default and needs its Alertmanager configuration", () => {
	const resources = rendered(layersFor("small"));
	assert.deepEqual(
		resources.filter((resource) => inStack(resource)),
		[],
	);
	assert.match(
		render([...layersFor("small"), { observability: { stack: { enabled: true } } }]).stderr,
		/observability\.stack\.alertmanager\.configSecret is required/u,
	);
});

await test("a release package ships the alert rules without their promtool cases", async (t) => {
	const directory = await mkdtemp(path.join(tmpdir(), "hephaestus-release-chart-"));
	t.after(async () => rm(directory, { recursive: true, force: true }));
	const packaged = await packageReleaseChart(prepared.release.version, chart, prepared, directory);
	// A relative name: GNU tar on Windows reads the "C:" of an absolute path as a remote host.
	const listing = spawnSync("tar", ["-tzf", path.basename(packaged)], {
		cwd: path.dirname(packaged),
		encoding: "utf8",
	});
	assert.equal(listing.status, 0, listing.stderr);
	const files = listing.stdout.split("\n");
	assert.ok(files.includes("hephaestus/files/hephaestus-alerts.yml"), "the rules");
	assert.ok(
		!files.some((file) => file.endsWith("hephaestus-alerts.test.yml")),
		"no promtool cases",
	);
	const resources = rendered([profile("small"), operator, reviews, alertRulesOn], {
		source: packaged,
	});
	assert.equal(get(ruleResources(resources)[0], "data", "hephaestus-alerts.yml"), alertRules);
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
			// The server's sandbox gateway admits the sandboxes and the loop, on its own port.
			const gatewayRules = component === "server" && runsReviews(name) ? 2 : 0;
			assert.equal(ingress.length, 1 + gatewayRules, `${component}: no scraper is configured`);
			// Only the Gateway reaches the application port.
			assert.deepEqual(get(ingress[0], "from"), [gatewayPeer], component);
			assert.deepEqual(get(ingress[0], "ports"), [{ port, protocol: "TCP" }], component);
			for (const rule of ingress.slice(1)) {
				// No rule of the sandbox gateway names the Gateway, so no public route reaches port 8081.
				assert.deepEqual(get(rule, "ports"), [{ port: 8081, protocol: "TCP" }], component);
				assert.ok(!isDeepStrictEqual(get(rule, "from"), [gatewayPeer]), component);
			}
		}
		if (runsReviews(name)) {
			const [, sandboxes, loop] = list(get(policy("server"), "spec", "ingress"));
			assert.deepEqual(get(sandboxes, "from"), [
				{
					namespaceSelector: {
						matchLabels: { "kubernetes.io/metadata.name": sandboxNamespace },
					},
					podSelector: {
						matchExpressions: [
							{
								key: "hephaestus.build/sandbox-kind",
								operator: "In",
								values: ["review", "conversation"],
							},
						],
					},
				},
			]);
			assert.equal(
				get(loop, "from", 0, "podSelector", "matchLabels", "app.kubernetes.io/component"),
				"conversation-loop",
			);
		}
		const webhookEgress = list(get(policy("webhook"), "spec", "egress"));
		const serverEgress = list(get(policy("server"), "spec", "egress"));
		if (name === "external") {
			assert.ok(
				serverEgress.some(
					(rule) =>
						get(rule, "to", 0, "ipBlock", "cidr") === "10.0.12.0/24" &&
						get(rule, "ports", 0, "port") === 5432,
				),
			);
			for (const egress of [serverEgress, webhookEgress]) {
				assert.ok(
					egress.some(
						(rule) =>
							get(rule, "to", 0, "ipBlock", "cidr") === "10.0.13.0/24" &&
							get(rule, "ports", 0, "port") === 4222,
					),
				);
			}
		}
		// The receiver reaches the broker only (ADR 0066). The server adds the database and internet
		// HTTPS, which never includes private ranges.
		assert.equal(webhookEgress.length, 1);
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
		const resources = rendered([...layersFor("small"), sandboxLayer], { release, namespace: "h" });
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
		const database = /^postgresql:\/\/(?<service>[^.:/]+)\.h\.svc:5432\//u.exec(
			String(envValue(workload(resources, "server"), "DATABASE_URL")),
		)?.groups?.service;
		assert.ok(named(resources, "Service", database), `database host ${String(database)}`);
		const broker = /^nats:\/\/(?<host>[^:/]+):4222$/u.exec(
			String(envValue(workload(resources, "server"), "NATS_SERVER")),
		)?.groups?.host;
		assert.ok(named(resources, "Service", broker), `broker host ${String(broker)}`);
		// The sandboxes' gateway policy in the sandbox namespace carries the release's name.
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

for (const name of ["small", "external"] as const) {
	await test(`${name}: stream bounds and thirty-day durable expiry reach their runtime roles`, () => {
		const resources = rendered(layersFor(name));
		const receiver = workload(resources, "webhook");
		for (const [provider, ageKey, bytesKey, bytes] of [
			["github", "MAX_AGE", "MAXBYTESBYSTREAM_GITHUB", "6442450944B"],
			["gitlab", "MAX_AGE", "MAX_BYTES", "536870912B"],
			["slack", "MAX_AGE", "MAX_BYTES", "536870912B"],
			["outline", "MAX_AGE", "MAX_BYTES", "536870912B"],
		] as const) {
			assert.equal(
				envValue(receiver, `HEPHAESTUS_WEBHOOK_STREAM_${ageKey}`),
				"7d",
				`${provider} age`,
			);
			assert.equal(
				envValue(receiver, `HEPHAESTUS_WEBHOOK_STREAM_${bytesKey}`),
				bytes,
				`${provider} bytes`,
			);
		}
		assert.equal(envValue(receiver, "HEPHAESTUS_WEBHOOK_STREAM_STORAGE_BUDGET"), "8589934592");
		assert.equal(
			envValue(receiver, "HEPHAESTUS_WEBHOOK_STREAM_ALLOW_DESTRUCTIVE_LIMIT_UPDATES"),
			"false",
		);
	});
}

await test("per-stream ages accept a site override and reject an unknown provider", () => {
	const receiver = workload(
		rendered([...layersFor("small"), { webhook: { stream: { maxAgeByStream: { slack: "9d" } } } }]),
		"webhook",
	);
	assert.equal(envValue(receiver, "HEPHAESTUS_WEBHOOK_STREAM_MAXAGEBYSTREAM_SLACK"), "9d");
	assert.equal(env(receiver, "HEPHAESTUS_WEBHOOK_STREAM_MAXAGEBYSTREAM_OUTLINE"), undefined);
	assert.match(
		render([...layersFor("small"), { webhook: { stream: { maxAgeByStream: { unknown: "7d" } } } }])
			.stderr,
		/invalid propertyName 'unknown'/u,
	);
});

await test("an existing installation can keep its broker budget and volume when it adopts smaller stream bounds", () => {
	const resources = rendered([
		...layersFor("small"),
		{ nats: { jetstream: { maxFileBytes: 17_179_869_184 } } },
	]);
	assert.equal(
		envValue(workload(resources, "webhook"), "HEPHAESTUS_WEBHOOK_STREAM_STORAGE_BUDGET"),
		"17179869184",
	);
	assert.equal(
		envValue(workload(resources, "webhook"), "HEPHAESTUS_WEBHOOK_STREAM_MAXBYTESBYSTREAM_GITHUB"),
		"6442450944B",
	);
	assert.equal(
		String(
			get(
				workload(resources, "nats"),
				"spec",
				"volumeClaimTemplates",
				0,
				"spec",
				"resources",
				"requests",
				"storage",
			),
		),
		"21474836480",
	);
});

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

await test("a server with sandboxes receives its runtime settings, its sandbox namespace and only the authority its runtime uses", () => {
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	const server = workload(resources, "server");
	const serverName = nameOf(server);
	// The operator owns the sandbox namespace: the chart writes into it and creates no namespace.
	assert.equal(resources.filter((resource) => kindOf(resource) === "Namespace").length, 0);
	const sandboxes = sandboxNamespace;

	// The selected settings, the same strings that the chart's objects use.
	assert.equal(envValue(server, "SPRING_PROFILES_ACTIVE"), "prod");
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_RUNTIME"), undefined);
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE"), sandboxes);
	// A claim names its kind's warm pool, and passes the gateway Service's address, which the server resolves.
	for (const kind of ["review", "conversation"]) {
		const pool = envValue(server, `HEPHAESTUS_AGENT_SANDBOX_WARM_POOLS_${kind.toUpperCase()}`);
		assert.ok(
			resources.some(
				(resource) =>
					kindOf(resource) === "SandboxWarmPool" &&
					nameOf(resource) === pool &&
					get(resource, "metadata", "namespace") === sandboxes,
			),
			`the ${kind} warm pool that the server names exists in the sandbox namespace`,
		);
	}
	const gatewayService = resources.find(
		(resource) => kindOf(resource) === "Service" && nameOf(resource).endsWith("-sandbox-gateway"),
	);
	assert.ok(gatewayService, "the gateway Service");
	assert.equal(
		envValue(server, "HEPHAESTUS_AGENT_SANDBOX_GATEWAY_URL"),
		`http://${nameOf(gatewayService)}.hephaestus.svc:${String(get(gatewayService, "spec", "ports", 0, "port"))}`,
	);
	for (const [variable, field] of [
		["POD_NAME", "metadata.name"],
		["POD_IP", "status.podIP"],
	] as const) {
		assert.equal(
			get(
				env(server, `HEPHAESTUS_SANDBOX_KUBERNETES_${variable}`),
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
			runtimeClassName: envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_RUNTIME_CLASS_NAME"),
			seccompProfile: envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_SECCOMP_PROFILE"),
			nodeSelector: parseJson(
				String(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_NODE_SELECTOR")),
			),
			tolerations: parseJson(String(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_TOLERATIONS"))),
			probeInterval: envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_PROBE_INTERVAL"),
		},
		sandboxRuntime,
	);
	assert.equal(env(server, "HEPHAESTUS_SANDBOX_KUBERNETES_POD_PIDS_LIMIT"), undefined);
	assert.equal(envValue(server, "SANDBOX_API_PORT"), "8081");
	const service = (name: string) =>
		resources.find((resource) => kindOf(resource) === "Service" && nameOf(resource) === name);
	// Callers reach only ready servers; only the probe reaches one that is not ready yet.
	assert.equal(get(service("heph-sandbox-gateway"), "spec", "publishNotReadyAddresses"), true);
	// A review attempt lives on the worker that took its sandbox's first call.
	assert.equal(get(service("heph-sandbox-gateway"), "spec", "sessionAffinity"), "ClientIP");
	assert.equal(
		get(
			service("heph-sandbox-gateway"),
			"spec",
			"sessionAffinityConfig",
			"clientIP",
			"timeoutSeconds",
		),
		86_400,
	);
	assert.equal(
		get(service("heph-sandbox-gateway-probe"), "spec", "publishNotReadyAddresses"),
		true,
	);
	for (const name of ["heph-sandbox-gateway", "heph-sandbox-gateway-probe"]) {
		assert.equal(get(service(name), "spec", "type"), "ClusterIP", name);
		assert.equal(get(service(name), "spec", "selector", "app.kubernetes.io/component"), "server");
		assert.equal(get(service(name), "spec", "ports", 0, "port"), 8081, name);
	}
	assert.match(
		String(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_GATEWAY_PROBE_SERVICE")),
		/^heph-sandbox-gateway-probe\.[a-z0-9-]+\.svc$/u,
	);
	const serverPolicy = resources.find(
		(resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-server",
	);
	assert.ok(
		list(get(serverPolicy, "spec", "ingress")).some(
			(rule) =>
				get(rule, "from", 0, "podSelector", "matchLabels", "app.kubernetes.io/component") ===
					"conversation-loop" && get(rule, "ports", 0, "port") === 8081,
		),
		"the conversation loop reaches the gateway port",
	);
	assert.ok(
		list(get(container(server), "ports")).some(
			(port) => get(port, "name") === "sandbox-gateway" && get(port, "containerPort") === 8081,
		),
	);
	assert.equal(
		env(server, "HEPHAESTUS_WORKER_ID"),
		undefined,
		"the ledger identity stays per process",
	);
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_IMAGE_REFERENCE"), prepared.images["agent-pi"]);
	assert.equal(get(container(server), "image"), prepared.images["application-server"]);
	// A server hands a turn only to the loop that presents the release Secret's credential.
	assert.deepEqual(
		get(env(server, "HEPHAESTUS_CONVERSATION_LOOP_SECRET"), "valueFrom", "secretKeyRef"),
		{ name: secretName, key: "HEPHAESTUS_CONVERSATION_LOOP_SECRET" },
	);
	assert.equal(
		env(server, "HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX"),
		undefined,
		"no cap per server",
	);

	// The server finishes in-flight reviews before its replacement starts. HTTP stays open during the drain,
	// with two additional minutes for the remaining shutdown phases.
	assert.equal(kindOf(server), "Deployment");
	assert.equal(get(server, "spec", "replicas"), 1);
	assert.deepEqual(get(server, "spec", "strategy"), {
		type: "Recreate",
	});
	const spec = podSpec(server);
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_DRAIN_TIMEOUT"), "600s");
	assert.equal(get(spec, "terminationGracePeriodSeconds"), 600 + 120);
	assert.deepEqual(
		list(get(spec, "volumes")).find((volume) => get(volume, "name") === "git-cache"),
		{ name: "git-cache", emptyDir: { sizeLimit: "20Gi" } },
	);

	// Restricted server pods with the installation labels that their sandboxes copy, and a token.
	assert.equal(get(spec, "serviceAccountName"), serverName);
	assert.equal(get(spec, "automountServiceAccountToken"), true);
	assert.equal(get(spec, "securityContext", "runAsNonRoot"), true);
	const security = get(container(server), "securityContext");
	assert.equal(get(security, "allowPrivilegeEscalation"), false);
	assert.equal(get(security, "readOnlyRootFilesystem"), true);
	assert.deepEqual(get(security, "capabilities", "drop"), ["ALL"]);
	const labels = get(server, "spec", "template", "metadata", "labels");
	for (const [key, value] of [
		["app.kubernetes.io/name", "hephaestus"],
		["app.kubernetes.io/part-of", "hephaestus"],
		["app.kubernetes.io/instance", "heph"],
		["app.kubernetes.io/component", "server"],
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
	// One quota for each tier, each selecting its Pods by their PriorityClass, and one that owns no objects.
	const quotas = resources.filter((resource) => kindOf(resource) === "ResourceQuota");
	assert.deepEqual(
		quotas.map((quota) => get(quota, "metadata", "namespace")),
		[sandboxes, sandboxes, sandboxes],
	);
	for (const tier of ["reviews", "conversations"] as const) {
		const quota = tierQuota(resources, tier);
		assert.deepEqual(get(quota, "spec", "scopeSelector", "matchExpressions"), [
			{ scopeName: "PriorityClass", operator: "In", values: [`hephaestus-heph-${tier}`] },
		]);
		// The API server refuses any other resource in a quota scoped by PriorityClass.
		assert.deepEqual(
			Object.keys(asRecord(get(quota, "spec", "hard"), `the ${tier} quota`)).toSorted(),
			["pods", "requests.cpu", "requests.memory"],
		);
	}
	assert.match(
		String(get(sandboxQuota(resources), "spec", "hard", "requests.ephemeral-storage")),
		/^[1-9][0-9]*Mi$/u,
	);
	// One server with Recreate: its five reviews and one probe run of two Pods. The installation's tools
	// Pods: eight assigned and two warm, whatever the servers.
	assert.equal(get(tierQuota(resources, "reviews"), "spec", "hard", "pods"), "7");
	assert.equal(get(tierQuota(resources, "conversations"), "spec", "hard", "pods"), "10");

	// Exactly the calls that the runtime makes, each in one namespace, and no cluster-wide authority.
	for (const kind of ["ClusterRole", "ClusterRoleBinding"]) {
		assert.equal(resources.filter((resource) => kindOf(resource) === kind).length, 0, kind);
	}
	const expected = new Map<string | undefined, string[]>([
		[
			sandboxes,
			[
				// No pods/exec: the server runs no command in any sandbox Pod. No Pod patch, which can
				// change the image of a sandbox that runs, and no Pod delete: a stop deletes the Job.
				// The create is for the isolation probe's Pod Security dry run.
				...["create", "get", "list", "watch"].map((verb) => `/pods:${verb}`),
				"/pods/log:get",
				...["create", "delete", "get", "list", "watch"].map((verb) => `batch/jobs:${verb}`),
				// A quota refusal of a review Pod: the Job controller's event, found in a list.
				"/events:list",
				// How many reviews fit, watched as Pods and Jobs are, so a claim poll reads a cache.
				...["list", "watch"].map((verb) => `/resourcequotas:${verb}`),
				// A subject's sandbox: its claim, never the Sandbox or its Pod, which the controller owns.
				...["create", "delete", "get", "list", "watch"].map(
					(verb) => `extensions.agents.x-k8s.io/sandboxclaims:${verb}`,
				),
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
			[{ kind: "ServiceAccount", name: serverName, namespace: "hephaestus" }],
			label,
		);
	}

	// Tools Pods have no egress. Reviews reach the servers' gateway port only; the credential, not the
	// network, binds a sandbox to its own run.
	const policiesIn = (namespace: string) =>
		resources
			.filter(
				(resource) =>
					kindOf(resource) === "NetworkPolicy" &&
					get(resource, "metadata", "namespace") === namespace,
			)
			.map(nameOf)
			.toSorted(byCodePoint);
	assert.deepEqual(policiesIn(sandboxes), [
		"heph-sandbox-deny",
		"heph-sandbox-gateway",
		"heph-sandbox-tools",
	]);
	const policy = (name: string) =>
		resources.find((resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource) === name);
	// A tools Pod accepts the conversation loop of this release on the tools port, and nothing else.
	const toolsPort = [{ port: 7100, protocol: "TCP" }];
	const loop = workload(resources, "conversation-loop");
	assert.deepEqual(get(policy("heph-sandbox-tools"), "spec"), {
		podSelector: sandboxKind("tools"),
		policyTypes: ["Ingress"],
		ingress: [
			{
				from: [
					{
						namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "hephaestus" } },
						podSelector: { matchLabels: get(loop, "spec", "selector", "matchLabels") },
					},
				],
				ports: toolsPort,
			},
		],
	});
	const gateway = resources.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-sandbox-gateway",
	);
	const sources = {
		matchExpressions: [
			{
				key: "hephaestus.build/sandbox-kind",
				operator: "In",
				values: ["review", "conversation"],
			},
		],
	};
	assert.deepEqual(get(gateway, "spec", "podSelector"), sources);
	const serverSelector = get(server, "spec", "selector", "matchLabels");
	assert.deepEqual(get(gateway, "spec", "egress"), [
		{
			to: [
				{
					namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "hephaestus" } },
					podSelector: { matchLabels: serverSelector },
				},
			],
			ports: [{ port: 8081, protocol: "TCP" }],
		},
	]);
	const own = resources.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" &&
			nameOf(resource) === serverName &&
			get(resource, "metadata", "namespace") === undefined,
	);
	assert.deepEqual(get(own, "spec", "podSelector", "matchLabels"), serverSelector);
	// The first rule admits the Gateway on the application port; the sandboxes come next, on 8081 only.
	assert.deepEqual(get(own, "spec", "ingress", 1, "from"), [
		{
			namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": sandboxes } },
			podSelector: sources,
		},
	]);
	assert.deepEqual(get(own, "spec", "ingress", 1, "ports"), [{ port: 8081, protocol: "TCP" }]);
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

await test("the conversation loop runs the agent image without authority, beside the servers", () => {
	const resources = rendered(withSandboxes("dev"));
	const loop = workload(resources, "conversation-loop");
	const spec = podSpec(loop);
	const entry = container(loop);
	// Two loops where the cluster has two nodes; a new one is ready before an old one stops.
	assert.equal(get(loop, "spec", "replicas"), 2);
	assert.equal(get(loop, "spec", "minReadySeconds"), 5);
	assert.deepEqual(get(loop, "spec", "strategy"), {
		type: "RollingUpdate",
		rollingUpdate: { maxSurge: 1, maxUnavailable: 0 },
	});
	const selector = get(loop, "spec", "selector", "matchLabels");
	assert.equal(get(selector, "app.kubernetes.io/component"), "conversation-loop");
	assert.deepEqual(
		get(
			spec,
			"affinity",
			"podAntiAffinity",
			"preferredDuringSchedulingIgnoredDuringExecution",
			0,
			"podAffinityTerm",
		),
		{ topologyKey: "kubernetes.io/hostname", labelSelector: { matchLabels: selector } },
	);
	assert.equal(get(spec, "topologySpreadConstraints", 0, "whenUnsatisfiable"), "ScheduleAnyway");
	// A role, with no token and the agent image's user.
	assert.equal(
		get(spec, "priorityClassName"),
		get(podSpec(workload(resources, "server")), "priorityClassName"),
	);
	assert.equal(get(spec, "automountServiceAccountToken"), false);
	const account = resources.find(
		(resource) =>
			kindOf(resource) === "ServiceAccount" && nameOf(resource) === get(spec, "serviceAccountName"),
	);
	assert.equal(get(account, "automountServiceAccountToken"), false);
	assert.equal(get(spec, "securityContext", "runAsUser"), 1000);
	assert.equal(get(spec, "securityContext", "runAsGroup"), 1000);
	assert.equal(get(entry, "securityContext", "readOnlyRootFilesystem"), true);
	// The longest turn, its stop grace and the stop.
	assert.equal(get(spec, "terminationGracePeriodSeconds"), 900 + 30 + 30);
	assert.equal(get(entry, "image"), prepared.images["agent-pi"]);
	// Node grants no spawn, no read outside the code and the scratch directory, and no other write.
	assert.deepEqual(get(entry, "command"), [
		"node",
		"--disable-sigusr1",
		"--enable-source-maps",
		"--permission",
		"--allow-fs-read=/opt/pi-sdk",
		"--allow-fs-read=/tmp/heph-loop",
		"--allow-fs-write=/tmp/heph-loop",
		"/opt/pi-sdk/heph-host.js",
	]);
	assert.deepEqual(get(entry, "volumeMounts"), [{ name: "scratch", mountPath: "/tmp/heph-loop" }]);
	assert.deepEqual(get(spec, "volumes"), [{ name: "scratch", emptyDir: { sizeLimit: "256Mi" } }]);
	assert.deepEqual(
		list(get(entry, "env")).map((variable) => get(variable, "name")),
		[
			"LLM_PROXY_URL",
			"HEPHAESTUS_CONVERSATION_LOOP_SECRET",
			"HEPHAESTUS_MENTOR_TURN_CAPACITY",
			"HOME",
			"PI_CODING_AGENT_DIR",
		],
	);
	assert.equal(
		envValue(loop, "LLM_PROXY_URL"),
		"http://heph-sandbox-gateway.hephaestus.svc:8081/internal/llm",
	);
	assert.deepEqual(
		get(env(loop, "HEPHAESTUS_CONVERSATION_LOOP_SECRET"), "valueFrom", "secretKeyRef"),
		{
			name: secretName,
			key: "HEPHAESTUS_CONVERSATION_LOOP_SECRET",
		},
	);
	assert.equal(envValue(loop, "HEPHAESTUS_MENTOR_TURN_CAPACITY"), "8");
	assert.equal(envValue(loop, "PI_CODING_AGENT_DIR"), "/tmp/heph-loop/.pi");
	// The probes read the files that the loop writes: no port.
	assert.equal(get(entry, "ports"), undefined);
	assert.deepEqual(get(entry, "readinessProbe", "exec", "command"), [
		"test",
		"-f",
		"/tmp/heph-loop/ready",
	]);
	assert.deepEqual(get(entry, "livenessProbe", "exec", "command"), [
		"node",
		"-e",
		"process.exit(Date.now()-require('node:fs').statSync('/tmp/heph-loop/alive').mtimeMs<120000?0:1)",
	]);
	assert.equal(get(entry, "livenessProbe", "periodSeconds"), 30);
	assert.equal(get(entry, "livenessProbe", "failureThreshold"), 3);
	assert.equal(
		resources.some(
			(resource) =>
				kindOf(resource) === "Service" &&
				get(resource, "spec", "selector", "app.kubernetes.io/component") === "conversation-loop",
		),
		false,
		"no Service selects the loop",
	);

	// No ingress at all. Egress to the servers' gateway port and to the tools port of tools Pods only.
	const policy = resources.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" && nameOf(resource) === get(loop, "metadata", "name"),
	);
	assert.deepEqual(get(policy, "spec"), {
		podSelector: { matchLabels: selector },
		policyTypes: ["Ingress", "Egress"],
		egress: [
			{
				to: [
					{
						podSelector: {
							matchLabels: get(workload(resources, "server"), "spec", "selector", "matchLabels"),
						},
					},
				],
				ports: [{ port: 8081, protocol: "TCP" }],
			},
			{
				to: [
					{
						namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": sandboxNamespace } },
						podSelector: sandboxKind("tools"),
					},
				],
				ports: [{ port: 7100, protocol: "TCP" }],
			},
		],
	});

	// One node: one loop.
	assert.equal(
		get(workload(rendered(layersFor("small")), "conversation-loop"), "spec", "replicas"),
		1,
	);
});

await test("the retired worker block fails, its turn count included", () => {
	// 1.0 has no worker role: its values moved to `server` and `sandbox`, and an old values file is refused.
	for (const retired of [{ conversationTurns: 8 }, { enabled: true }]) {
		const result = render([...layersFor("small"), { worker: retired }]);
		assert.equal(result.ok, false);
		assert.match(result.stderr, /additional properties 'worker' not allowed/u);
	}
});

await test("sandboxes without the optional runtime settings use the cluster's defaults, and the quota can be left out", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			sandbox: { enabled: true, namespace: sandboxNamespace, quota: { enabled: false } },
			networkPolicy: { kubernetesApi },
		},
	]);
	const server = workload(resources, "server");
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_RUNTIME_CLASS_NAME"), "");
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_SECCOMP_PROFILE"), "");
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_NODE_SELECTOR"), "{}");
	assert.equal(envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_TOLERATIONS"), "[]");
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
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	const service = resources.find(
		(resource) => kindOf(resource) === "Service" && componentOf(resource) === "postgresql",
	);
	assert.ok(service, "the PostgreSQL Service");
	const url = `postgresql://${nameOf(service)}.hephaestus.svc:5432/hephaestus?${verifyFull}`;
	for (const component of ["server"]) {
		const role = workload(resources, component);
		assert.equal(envValue(role, "DATABASE_URL"), url, component);
		assertCaOnly(role, component);
	}

	// The image's own entrypoint starts the server with TLS, from the certificate and key alone.
	const postgresql = workload(resources, "postgresql");
	const entry = container(postgresql);
	assert.equal(get(entry, "command"), undefined);
	assert.deepEqual(list(get(entry, "args")).slice(0, 6), [
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
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
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
		assert.deepEqual(keys, ["ca.crt", "tls.crt", "tls.key"]);
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
		postgresql: {
			backup: {
				clientTlsSecret: "hephaestus-postgresql-backup-tls",
				enabled: true,
				s3: { ...s3, bucket: "other-backups" },
			},
		},
	};
	assert.equal(
		typeof checksum([
			...layersFor("small"),
			{
				postgresql: {
					backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
				},
			},
		]),
		"string",
	);
	assert.notEqual(
		checksum([
			...layersFor("small"),
			{
				postgresql: {
					backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
				},
			},
		]),
		checksum([...layersFor("small"), bucket]),
	);
});

await test("a restore stops PostgreSQL and the database roles, suspends the backups, and replays to the asked time", () => {
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const enabled = [
		...layersFor("small"),
		sandboxLayer,
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	];
	const time = "2026-10-24 19:25:31+00";
	const restoring = rendered([
		...enabled,
		{ postgresql: { backup: { restore: { enabled: true, time } } } },
	]);
	for (const component of ["postgresql", "server", "webhook"]) {
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
		sandboxLayer,
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
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

/** One review Pod's memory request in MiB, from the table that the chart sizes the quota from. */
const reviewPodMi = Number(
	get(readJsonFileSync(path.join(chart, "sandbox-pod-requests.json")), "review", "memoryMi"),
);
/** The memory that holds this many review Pods. */
const memoryFor = (reviewCount: number) => `${reviewCount * reviewPodMi}Mi`;
const readValues = (file: string): unknown =>
	parseAllDocuments(readFileSync(file, "utf8"))[0]?.toJS();
/** sandbox.reviewMemory as these layers set it, over the chart's default, in MiB. */
const reviewMemoryMi = (layers: readonly Layer[]) => {
	let value = get(readValues(path.join(chart, "values.yaml")), "sandbox", "reviewMemory");
	for (const layer of layers) {
		const values = typeof layer === "string" ? readValues(layer) : layer;
		value = get(values, "sandbox", "reviewMemory") ?? value;
	}
	return amount(value) / 2 ** 20;
};
/** The reviews that the server runs, and the Pods that the reviews quota holds. */
const reviewWidth = (layers: Layer[]) => {
	const resources = rendered(layers);
	return {
		sandboxes: envValue(workload(resources, "server"), "SANDBOX_MAX_CONCURRENT"),
		pods: get(tierQuota(resources, "reviews"), "spec", "hard", "pods"),
	};
};

const serverCapacity = (reviewCount: number) => {
	const server = workload(
		rendered([
			...layersFor("small"),
			{
				sandbox: { reviewMemory: memoryFor(reviewCount) },
				conversationLoop: { turns: 8 },
			},
		]),
		"server",
	);
	return {
		threads: envValue(server, "BPL_JVM_THREAD_COUNT"),
		sandboxes: envValue(server, "SANDBOX_MAX_CONCURRENT"),
	};
};

await test("the webhook role's heap is sized from what that role loads", () => {
	const webhook = workload(rendered(layersFor("small")), "webhook");
	assert.equal(envValue(webhook, "BPL_JVM_THREAD_COUNT"), "80");
	assert.equal(envValue(webhook, "BPL_JVM_LOADED_CLASS_COUNT"), "18000");
	assert.equal(envValue(webhook, "JAVA_TOOL_OPTIONS"), "-XX:ReservedCodeCacheSize=64M");
});

await test("what the server holds for its reviews follows the reviews that the sandbox nodes hold", () => {
	// The buildpack's 250 threads of the server, and 8 more per review.
	assert.deepEqual(serverCapacity(1), {
		threads: "258",
		sandboxes: "1",
	});
	assert.deepEqual(serverCapacity(16), {
		threads: "378",
		sandboxes: "16",
	});
	assert.deepEqual(serverCapacity(32), {
		threads: "506",
		sandboxes: "32",
	});
	assert.deepEqual(serverCapacity(64), {
		threads: "762",
		sandboxes: "64",
	});
});

await test("the server runs the reviews that the sandbox nodes hold, and the quota holds no more", () => {
	const base = [
		...layersFor("small"),
		{ sandbox: { reviewMemory: memoryFor(66) }, conversationLoop: { turns: 8 } },
	];
	// One server, which stops before its replacement starts: its reviews and one probe of two Pods.
	assert.deepEqual(reviewWidth(base), { sandboxes: "66", pods: "68" });
	// A fraction does not convert to whole MiB, so the chart refuses it.
	const fraction = render([...layersFor("small"), { sandbox: { reviewMemory: "1.5Gi" } }]);
	assert.equal(fraction.ok, false);
	assert.match(fraction.stderr, /reviewMemory/u);
	const noReview = render([...layersFor("small"), { sandbox: { reviewMemory: "383Mi" } }]);
	assert.equal(noReview.ok, false);
	assert.match(noReview.stderr, /reviewMemory holds no review: one review Pod requests 384Mi/u);
});

await test("the small profile keeps personal content inside the transport-buffer horizon", () => {
	// The application's 72 hours hold for Slack and Outline: erasure cannot reach inside a broker stream.
	const receiver = workload(rendered(layersFor("small")), "webhook");
	assert.equal(env(receiver, "HEPHAESTUS_WEBHOOK_STREAM_MAXAGEBYSTREAM_SLACK"), undefined);
	assert.equal(env(receiver, "HEPHAESTUS_WEBHOOK_STREAM_MAXAGEBYSTREAM_OUTLINE"), undefined);
});

await test("the broker consumer policy comes from the application defaults, not the chart", () => {
	for (const name of profiles) {
		const resources = rendered(withSandboxes(name));
		for (const role of ["server", "webhook"]) {
			for (const variable of [
				"NATS_DURABLE_CONSUMER_NAME",
				"HEPHAESTUS_INTEGRATION_CONSUMER_INACTIVE_THRESHOLD",
			]) {
				assert.equal(
					env(workload(resources, role), variable),
					undefined,
					`${name} ${role} ${variable}`,
				);
			}
		}
	}
});

await test("Slack callbacks use the canonical API origin in either workspace-host mode", () => {
	for (const enabled of [false, true]) {
		const resources = rendered([
			...layersFor("small"),
			{
				host: "callbacks.example.test",
				workspaceSubdomains: { enabled },
				config: { slack: { enabled: true, clientId: "slack-client" } },
			},
		]);
		const server = workload(resources, "server");
		assert.equal(envValue(server, "HEPHAESTUS_AUTH_ISSUER"), "https://callbacks.example.test");
		assert.equal(envValue(server, "SPRING_PROFILES_ACTIVE"), "prod");
		assert.equal(env(server, "HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI"), undefined);
	}
});

/** One turn count for the server and each of the loop's replicas. */
const everywhere = (count: string) => ({ loop: [count], server: count });

await test("practice reviews run exactly when the sandboxes run, with the installation's Heph turn capacity", () => {
	// Without sandboxes, a submitted review job would wait for a sandbox that never comes.
	assert.equal(envValue(workload(rendered(layersFor("dev")), "server"), "AGENT_ENABLED"), "false");
	// Every shipped profile, also the ones whose sandboxes the operator turns on.
	for (const name of profiles) {
		const resources = rendered(withSandboxes(name));
		assert.equal(envValue(workload(resources, "server"), "AGENT_ENABLED"), "true", name);
		assert.equal(env(workload(resources, "webhook"), "AGENT_ENABLED"), undefined, name);
		assert.equal(env(workload(resources, "webhook"), "GIT_CHECKOUT_ENABLED"), undefined, name);
		// Without git checkout the server reports practice reviews out of service.
		assert.equal(envValue(workload(resources, "server"), "GIT_CHECKOUT_ENABLED"), "true", name);
		// The server evicts mirrors against the volume that the kubelet bounds, so the two sizes are one value.
		for (const role of ["server"]) {
			const cache = list(get(podSpec(workload(resources, role)), "volumes")).find(
				(volume) => get(volume, "name") === "git-cache",
			);
			const size = String(get(cache, "emptyDir", "sizeLimit"));
			assert.match(size, /^\d+Gi$/u, `${name} ${role}`);
			assert.equal(
				envValue(workload(resources, role), "GIT_MAX_CACHE_BYTES"),
				String(Number.parseInt(size, 10) * 1024 ** 3),
				`${name} ${role}`,
			);
		}
	}
	// CPU-based auto is resolved once for the whole installation: the server and every loop get the same
	// count. Explicit counts remain overrides.
	const capacities = (layers: readonly Layer[]) => {
		const resources = rendered(layers);
		const budget = readJsonFileSync(path.join(chart, "turn-admission.json"));
		// No turns: the chart sets no capacity, as for the server.
		const turns = Number(
			envValue(workload(resources, "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY") ?? "0",
		);
		const loops = workloads(resources).filter(
			(resource) => componentOf(resource) === "conversation-loop",
		);
		// A loop rolls out with one more replica beside the old ones.
		const roleCpu = workloads(resources).reduce(
			(cpu, role) =>
				cpu +
				podRequests(podSpec(role)).cpu *
					(Number(get(role, "spec", "replicas")) +
						(componentOf(role) === "conversation-loop" ? surge(role) : 0)),
			0,
		);
		const quotaCpu = (["reviews", "conversations"] as const).reduce(
			(cpu, tier) => cpu + amount(get(tierQuota(resources, tier), "spec", "hard", "requests.cpu")),
			0,
		);
		// In whole millicores: the sum of fractions of a core is not exact.
		const planned =
			Math.round((roleCpu + quotaCpu) * 1000) + turns * Number(get(budget, "cpuMillisPerTurn"));
		assert.ok(
			planned <= Number(get(budget, "allocatableCpuMillis")),
			`steady CPU plan ${planned}m`,
		);
		assert.ok(turns <= Number(get(budget, "maximumTurns")));
		return {
			loop: loops.map((loop) => envValue(loop, "HEPHAESTUS_MENTOR_TURN_CAPACITY")),
			server: envValue(workload(resources, "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY"),
		};
	};
	// The loop's request, with the one more replica of its rollout, comes out of the 32.
	assert.deepEqual(capacities(layersFor("small")), everywhere("31"));
	assert.deepEqual(
		capacities([...layersFor("small"), { conversationLoop: { turns: "auto" } }]),
		everywhere("31"),
	);
	assert.deepEqual(
		capacities([...layersFor("small"), { conversationLoop: { turns: 5 } }]),
		everywhere("5"),
	);
	assert.deepEqual(
		capacities([...layersFor("small"), { sandbox: { reviewMemory: memoryFor(15) } }]),
		everywhere("21"),
	);
	// A larger server request comes out of the turns. One review keeps room for a few.
	assert.deepEqual(
		capacities([
			...layersFor("small"),
			{
				server: { resources: { requests: { cpu: "1800m" } } },
				sandbox: { reviewMemory: memoryFor(1) },
			},
		]),
		everywhere("9"),
	);
	// So does a larger loop, with the one more replica of its rollout.
	assert.deepEqual(
		capacities([
			...layersFor("small"),
			{ conversationLoop: { resources: { requests: { cpu: "500m" } } } },
		]),
		everywhere("15"),
	);
	const exhausted = render([
		...layersFor("small"),
		{ server: { resources: { requests: { cpu: "3" } } } },
	]);
	assert.equal(exhausted.ok, false);
	assert.match(exhausted.stderr, /no CPU room/u);
	assert.equal(
		env(
			workload(
				rendered([
					...layersFor("small"),
					{ sandbox: { enabled: false }, server: { resources: { requests: { cpu: "3" } } } },
				]),
				"server",
			),
			"HEPHAESTUS_MENTOR_TURN_CAPACITY",
		),
		undefined,
	);

	// No Heph slot at all: the server knows no capacity, so no question says that every slot is taken.
	// The server hands out no turn, and no loop runs.
	assert.deepEqual(capacities([...layersFor("small"), { conversationLoop: { turns: 0 } }]), {
		loop: [],
		server: undefined,
	});
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
		"HEPHAESTUS_WORKSPACE_CREATIONPOLICY",
		"HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED",
		"HEPHAESTUS_PUBLIC_ACTIVITY_REPOSITORY_VISIBILITY_MAX_AGE",
		"HEPHAESTUS_LLM_DISPLAY_CURRENCY",
		"HEPHAESTUS_AUTH_PASSKEYS_INSTANCE_ADMIN_REQUIRED",
		"HEPHAESTUS_AUTH_PASSKEYS_WORKSPACE_ADMIN_REQUIRED",
		"HEPHAESTUS_AUTH_PASSKEYS_RP_ID",
		"HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS",
		"SYNC_RUN_ON_STARTUP",
		"SYNC_TIMEFRAME_DAYS",
		"SYNC_CRON",
		"SYNC_COOLDOWN_MINUTES",
	];
	// The application's defaults under the prod profile (application.yml, application-prod.yml).
	const defaults = workload(rendered(layersFor("small")), "server");
	assert.deepEqual(
		settings.map((name) => envValue(defaults, name)),
		[
			"",
			"ADMIN_ONLY",
			"false",
			"48h",
			"",
			"true",
			"false",
			"",
			"",
			"true",
			"7",
			"0 0 * * * *",
			"60",
		],
	);
	const resources = rendered([
		...layersFor("small"),
		{
			config: {
				researchOrganization: "A research group",
				workspaceCreationPolicy: "SELF_SERVICE",
				publicActivity: { enabled: true, repositoryVisibilityMaxAge: "12h" },
				llmDisplayCurrency: "EUR",
				auth: {
					passkeys: {
						instanceAdminRequired: false,
						workspaceAdminRequired: true,
						rpId: "example.com",
						allowedOrigins: ["https://hephaestus.example.com"],
					},
				},
				sync: {
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
			"SELF_SERVICE",
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
	for (const workloadRole of ["server", "webhook"]) {
		for (const retired of [
			"MONITORING_RUN_ON_STARTUP",
			"MONITORING_TIMEFRAME",
			"MONITORING_SYNC_CRON",
			"MONITORING_SYNC_COOLDOWN_IN_MINUTES",
		]) {
			assert.equal(env(workload(resources, workloadRole), retired), undefined, workloadRole);
		}
	}
	for (const role of ["webhook"]) {
		for (const name of settings) {
			assert.equal(env(workload(resources, role), name), undefined, `${role} ${name}`);
		}
	}
});

/** A Kubernetes quantity in bytes or in cores. */
function amount(value: unknown): number {
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
}
/** What one Pod requests: its containers together, or its largest init container when that is more. */
function podRequests(spec: unknown) {
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
}
/** The Pods that a rollout adds beside the old ones: maxSurge, 25 % by default, none for Recreate. */
function surge(deployment: Resource): number {
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
}

const gib = (bytes: number) => (bytes / 2 ** 30).toFixed(1);

/** The chart's single-node budgets use the platform's configured reservation policy. */
const nodeRoom = (memory: number) => {
	const cpu =
		Number(get(readJsonFileSync(path.join(chart, "turn-admission.json")), "allocatableCpuMillis")) /
		1000;
	const policy = asRecord(
		parseAllDocuments(
			readFileSync(new URL("../deploy/platform/k3s-kubelet.yaml", import.meta.url), "utf8"),
		)[0]?.toJS(),
		"kubelet policy",
	);
	const reservations = [get(policy, "kubeReserved"), get(policy, "systemReserved")];
	assert.equal(
		cpu,
		4 - reservations.reduce<number>((sum, value) => sum + amount(get(value, "cpu")), 0),
		"declared CPU follows the kubelet reservations",
	);
	return {
		cpu,
		memory:
			memory -
			reservations.reduce<number>((sum, value) => sum + amount(get(value, "memory")), 0) -
			amount(get(policy, "evictionHard", "memory.available")),
	};
};

/** What each sandbox Pod requests: the chart's table, which SandboxPodRequestsTableTest pins to the server. */
const sandboxPods = (() => {
	const table = asRecord(
		readJsonFileSync(path.join(chart, "sandbox-pod-requests.json")),
		"sandbox-pod-requests.json",
	);
	const entry = (name: string) => {
		const pod = asRecord(table[name], name);
		const number = (key: string) => {
			const value = pod[key];
			assert.ok(typeof value === "number", `${name}.${key}`);
			return value;
		};
		return {
			pods: number("pods"),
			gib: number("memoryMi") / 1024,
			cpu: number("cpuMillis") / 1000,
		};
	};
	const count = (key: string) => {
		const value = table[key];
		assert.ok(typeof value === "number", key);
		return value;
	};
	return {
		review: entry("review"),
		turn: entry("toolsPod"),
		probe: entry("isolationProbe"),
		pooledTurns: count("pooledToolsPods"),
		activeToolsPods: count("activeToolsPods"),
	};
})();
const sandboxGib = {
	review: sandboxPods.review.gib,
	turn: sandboxPods.turn.gib,
	pooledTurns: sandboxPods.pooledTurns,
	probe: sandboxPods.probe.gib,
};

await test("the single-node profile's requests, one upgrade beside them and its sandbox quota fit the node it states", () => {
	// The node in profiles/small.yaml, less the platform reservations and hard eviction floor.
	// Images take node disk; the sandbox quotas count the sandboxes' disk requests.
	const node = { ...nodeRoom(32 * 2 ** 30), disk: (150 - 40) * 2 ** 30 };
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small"),
		sandboxLayer,
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
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
		// An upgrade runs the old Pods beside the new ones; the single-node server stops first.
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
	const residents = workloads(resources).reduce(
		(sum, resource) =>
			sum + podRequests(podSpec(resource)).memory * Number(get(resource, "spec", "replicas") ?? 1),
		0,
	);
	process.stdout.write(
		`single-node role requests: ${gib(residents)} GiB; upgrade additions: ${gib(memory - residents)} GiB\n`,
	);
	// Every sandbox Pod is inside a tier quota, which counts its requests: reviews, tools Pods and probe
	// Pods. The namespace's unscoped quota counts the disk of both tiers.
	for (const tier of ["reviews", "conversations"] as const) {
		const quota = tierQuota(resources, tier);
		cpu += amount(get(quota, "spec", "hard", "requests.cpu"));
		memory += amount(get(quota, "spec", "hard", "requests.memory"));
	}
	disk += amount(get(sandboxQuota(resources), "spec", "hard", "requests.ephemeral-storage"));
	assert.equal(get(workload(resources, "server"), "spec", "replicas"), 1);
	assert.equal(get(workload(resources, "conversation-loop"), "spec", "replicas"), 1);
	// The backup's request comes out of the turns too.
	assert.equal(
		Number(envValue(workload(resources, "server"), "HEPHAESTUS_MENTOR_TURN_CAPACITY")),
		29,
	);
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

// The quota and the Pods it admits come from one table and one review memory. Any profile, at its own memory
// and at another, renders a quota of exactly what its servers may run: a memory or a Pod request that moves
// alone fails here.
for (const name of profiles) {
	for (const reviewCount of [undefined, 3]) {
		const label = `${name}${reviewCount === undefined ? "" : `, memory for ${reviewCount} reviews`}`;
		await test(`${label}: each tier's quota holds the sandboxes that its servers may run, and no more`, () => {
			const layers = [
				...withSandboxes(name),
				...(reviewCount === undefined
					? []
					: [{ sandbox: { reviewMemory: memoryFor(reviewCount) } }]),
			];
			const resources = rendered(layers);
			const server = workload(resources, "server");
			// The reviews tier holds the reviews that the sandbox nodes hold, once: a new server waits for the old
			// one's room. Every running server, the old one during a surge included, has its probe. The
			// installation's tools Pods are one pool, whatever the servers.
			const replicas = Number(get(server, "spec", "replicas"));
			const running = replicas + surge(server);
			const perServer = Number(envValue(server, "SANDBOX_MAX_CONCURRENT"));
			// No profile states a RuntimeClass overhead; the overhead test below adds one.
			const held = Math.floor(reviewMemoryMi(layers) / reviewPodMi);
			assert.equal(perServer, held, "each server may run every review that the nodes hold");
			if (reviewCount !== undefined) {
				assert.equal(held, reviewCount);
			}
			const serverReviews = Math.min(held, replicas * perServer);
			const turns = Number(envValue(server, "HEPHAESTUS_MENTOR_TURN_CAPACITY"));
			assert.ok(Number.isInteger(turns), "the profile bounds the Heph turns");
			const turnSandboxes = sandboxPods.pooledTurns + Math.min(turns, sandboxPods.activeToolsPods);
			const sum = (parts: [typeof sandboxPods.review, number][]) => ({
				gib: parts.reduce((total, [pod, count]) => total + pod.gib * count, 0),
				cpu: parts.reduce((total, [pod, count]) => total + pod.cpu * count, 0),
				pods: parts.reduce((total, [pod, count]) => total + pod.pods * count, 0),
			});
			const expected = {
				reviews: sum([
					[sandboxPods.review, serverReviews],
					[sandboxPods.probe, running],
				]),
				conversations: sum([[sandboxPods.turn, turnSandboxes]]),
			};
			for (const tier of ["reviews", "conversations"] as const) {
				const hard = get(tierQuota(resources, tier), "spec", "hard");
				assert.equal(
					amount(get(hard, "requests.memory")),
					expected[tier].gib * 2 ** 30,
					`${tier} memory`,
				);
				assert.equal(
					Math.round(amount(get(hard, "requests.cpu")) * 1000),
					Math.round(expected[tier].cpu * 1000),
					`${tier} CPU`,
				);
				assert.equal(Number(get(hard, "pods")), expected[tier].pods, `${tier} Pods`);
			}
		});
	}
}

/** The bundled broker's soft limit, memory request and memory limit, rendered from these layers. */
const broker = (layers: Layer[]) => {
	const nats = workload(rendered(layers), "nats");
	return {
		goMemLimit: envValue(nats, "GOMEMLIMIT"),
		request: String(get(container(nats), "resources", "requests", "memory")),
		limit: String(get(container(nats), "resources", "limits", "memory")),
	};
};

// A catch-up of several streams fills the broker's limit with heap, so the Go runtime gets a soft limit below it.
await test("the bundled broker requests its whole limit and keeps its heap under 80 % of it", () => {
	for (const name of profiles.filter((each) => each !== "external")) {
		assert.deepEqual(
			broker(layersFor(name)),
			{ goMemLimit: "819MiB", request: "1Gi", limit: "1Gi" },
			name,
		);
	}
	// A site's own limit moves the soft limit with it.
	const sized = (memory: string) =>
		broker([
			...layersFor("small"),
			{ nats: { resources: { requests: { memory }, limits: { memory } } } },
		]).goMemLimit;
	assert.equal(sized("3Gi"), "2457MiB");
	assert.equal(sized("1536Mi"), "1228MiB");
	const refused = render([
		...layersFor("small"),
		{ nats: { resources: { limits: { memory: "3G" } } } },
	]);
	assert.equal(refused.ok, false);
	assert.match(
		refused.stderr,
		/nats\.resources\.limits\.memory must be a whole number of Mi or Gi/u,
	);
	// A fraction does not convert to whole MiB, so the chart refuses it.
	const fraction = render([
		...layersFor("small"),
		{ nats: { resources: { requests: { memory: "1.5Gi" }, limits: { memory: "1.5Gi" } } } },
	]);
	assert.equal(fraction.ok, false);
	assert.match(fraction.stderr, /must be a whole number of Mi or Gi/u);
	// A site that raised only the request, as an older default invited, gets a reason instead of a rejected Pod.
	const requestOnly = render([
		...layersFor("small"),
		{ nats: { resources: { requests: { memory: "3Gi" } } } },
	]);
	assert.equal(requestOnly.ok, false);
	assert.match(
		requestOnly.stderr,
		/nats\.resources\.requests\.memory \(3072Mi\) must equal nats\.resources\.limits\.memory \(1024Mi\)/u,
	);
});

/** The requests that each tier's quota holds, rendered from these layers. */
const tierHard = (layers: Layer[]) => {
	const resources = rendered(layers);
	return (tier: "reviews" | "conversations") => {
		const quota = get(tierQuota(resources, tier), "spec", "hard");
		return {
			pods: Number(get(quota, "pods")),
			millicpu: Math.round(amount(get(quota, "requests.cpu")) * 1000),
			memory: amount(get(quota, "requests.memory")),
		};
	};
};

await test("each tier's quota counts the RuntimeClass overhead of every sandbox Pod that it holds", () => {
	const overhead = { runtimeClassName: "gvisor", podOverhead: { cpuMillis: 100, memoryMi: 40 } };
	const without = tierHard([...layersFor("small"), { conversationLoop: { turns: 32 } }]);
	// The same five reviews, in memory that holds each review Pod's overhead too.
	const withOverhead = tierHard([
		...layersFor("small"),
		{
			sandbox: { runtime: overhead, reviewMemory: `${5 * (reviewPodMi + 40)}Mi` },
			conversationLoop: { turns: 32 },
		},
	]);
	for (const tier of ["reviews", "conversations"] as const) {
		const { pods, millicpu, memory } = without(tier);
		assert.deepEqual(
			withOverhead(tier),
			{ pods, millicpu: millicpu + pods * 100, memory: memory + pods * 40 * 2 ** 20 },
			tier,
		);
	}
	// In the profile's memory, the overhead leaves room for fewer reviews.
	assert.equal(reviewWidth(layersFor("small")).sandboxes, "5");
	assert.equal(
		reviewWidth([
			...layersFor("small"),
			{ sandbox: { runtime: overhead }, conversationLoop: { turns: 32 } },
		]).sandboxes,
		"4",
	);
});

const strategy = (name: (typeof profiles)[number], component: string) =>
	get(workload(rendered(layersFor(name)), component), "spec", "strategy");

await test("one server version drains before its replacement, while the webhook keeps receiving", () => {
	for (const profileName of profiles) {
		assert.deepEqual(strategy(profileName, "server"), { type: "Recreate" });
	}
	const draining = rendered([...layersFor("small"), { server: { drainTimeoutSeconds: 600 } }]);
	const server = workload(draining, "server");
	assert.equal(get(podSpec(server), "terminationGracePeriodSeconds"), 720);
	assert.equal(envValue(server, "HEPHAESTUS_AGENT_DRAIN_TIMEOUT"), "600s");
	const rolling = render([...layersFor("small"), { server: { rolloutStrategy: "RollingUpdate" } }]);
	assert.equal(rolling.ok, false);
	assert.match(rolling.stderr, /rolloutStrategy/u);
	const idle = render([...layersFor("small"), { sandbox: { enabled: false } }]);
	assert.equal(idle.ok, true, idle.stderr);
	assert.deepEqual(strategy("small", "webhook"), {
		type: "RollingUpdate",
		rollingUpdate: { maxUnavailable: 0, maxSurge: 1 },
	});
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

await test("the small profile on 16 GiB runs its reviews and Heph turns, and an upgrade, within one node of 16 GiB", () => {
	// The recorded Node capacity, less the current kubelet reservations and hard eviction floor.
	const node = nodeRoom(16_372_036 * 1024);
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const resources = rendered([
		...layersFor("small"),
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	]);
	const server = workload(resources, "server");
	assert.equal(get(server, "spec", "replicas"), 1);
	const turns = Number(envValue(server, "HEPHAESTUS_MENTOR_TURN_CAPACITY"));
	const reviewSlots = Number(envValue(server, "SANDBOX_MAX_CONCURRENT"));
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
	// Steady: every role, the conversation loop included, one backup, every review, the pooled tools Pods
	// and one for each turn up to the assigned ones.
	const steady = sum(total([...roles, [backup, 1]]), {
		cpu:
			reviewSlots * sandboxPods.review.cpu +
			(sandboxPods.pooledTurns + Math.min(turns, sandboxPods.activeToolsPods)) *
				sandboxPods.turn.cpu,
		memory:
			(reviewSlots * sandboxGib.review +
				(sandboxGib.pooledTurns + Math.min(turns, sandboxPods.activeToolsPods)) * sandboxGib.turn) *
			2 ** 30,
	});
	assert.equal(turns, 29);
	assert.equal(reviewSlots, 5);
	assert.ok(steady.cpu <= node.cpu, `steady: ${steady.cpu.toFixed(2)} CPU of ${node.cpu}`);
	assert.ok(
		steady.memory <= node.memory,
		`steady: ${gib(steady.memory)} of ${gib(node.memory)} GiB`,
	);
	// The isolation probe may wait for room then: a probe whose Pods cannot start keeps the last proof.
	const probing = sum(steady, sandboxes(sandboxGib.probe, sandboxPods.probe.pods));
	// An upgrade: the old server is gone (Recreate), the webhook, webapp and conversation loop surge, and the
	// migration and stanza Jobs run. The new server must pass its probe at its start, beside the installation's
	// pooled tools Pods.
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
		`16 GiB budget of ${gib(node.memory)} GiB: ${reviewSlots} reviews, steady ${gib(steady.memory)} GiB ${steady.cpu.toFixed(2)} CPU, with the probe ${gib(probing.memory)} GiB, upgrade ${gib(upgrade.memory)} GiB ${upgrade.cpu.toFixed(2)} CPU\n`,
	);
});

await test("roles outrank conversations, which outrank reviews, and a review never preempts", () => {
	const resources = rendered(layersFor("small"));
	const classes = new Map(
		resources
			.filter((resource) => kindOf(resource) === "PriorityClass")
			.map((resource) => [String(get(resource, "metadata", "name")), resource]),
	);
	const server = workload(resources, "server");
	const tier = (variable: string) => classes.get(String(envValue(server, variable)));
	const conversations = tier("HEPHAESTUS_SANDBOX_KUBERNETES_CONVERSATION_PRIORITY_CLASS");
	const reviewClass = tier("HEPHAESTUS_SANDBOX_KUBERNETES_REVIEW_PRIORITY_CLASS");
	assert.ok(conversations && reviewClass, "the server names classes that the chart creates");
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
	const withoutSandboxes = rendered(layersFor("dev")).filter(
		(resource) => kindOf(resource) === "PriorityClass",
	);
	assert.equal(withoutSandboxes.length, 1, "no sandbox classes without sandboxes");
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
	const server = workload(resources, "server");
	assert.equal(
		envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_CONVERSATION_PRIORITY_CLASS"),
		operators.conversations,
	);
	for (const tier of ["reviews", "conversations"] as const) {
		assert.deepEqual(
			get(tierQuota(resources, tier), "spec", "scopeSelector", "matchExpressions", 0, "values"),
			[operators[tier]],
			`the ${tier} quota selects the operator's class`,
		);
	}
	assert.equal(
		envValue(server, "HEPHAESTUS_SANDBOX_KUBERNETES_REVIEW_PRIORITY_CLASS"),
		operators.reviews,
	);
});

await test("every role that runs git writes the JGit configuration to an emptyDir, in every profile", () => {
	// The root filesystem is read-only, so JGit's default $HOME/.config cannot hold its config lock.
	for (const [name, role] of profiles.flatMap((shipped) =>
		(["server"] as const).map((candidate) => [shipped, candidate] as const),
	)) {
		const pod = workload(rendered(withSandboxes(name)), role);
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
				podTemplate([...withSandboxes(name), next], component),
				podTemplate(withSandboxes(name), component),
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

await test("every container of the application image names the release that it runs", () => {
	// The image is promoted without a rebuild, so its JAR cannot know its release. A container
	// without these values writes logs, spans and metrics with the development version.
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	const image = get(container(workload(resources, "server")), "image");
	const identities = resources.flatMap((resource) =>
		[
			...containers(resource),
			...list(get(resource, "spec", "jobTemplate", "spec", "template", "spec", "initContainers")),
			...list(get(resource, "spec", "jobTemplate", "spec", "template", "spec", "containers")),
		]
			.filter((entry) => get(entry, "image") === image)
			.map((entry) => {
				const value = (name: string) =>
					get(
						list(get(entry, "env")).find((variable) => get(variable, "name") === name),
						"value",
					);
				return {
					component: componentOf(resource),
					version: value("APP_VERSION"),
					commit: value("HEPHAESTUS_RELEASE_COMMIT"),
					environment: value("DEPLOYMENT_ENVIRONMENT"),
				};
			}),
	);
	assert.deepEqual(identities.map(({ component }) => String(component)).toSorted(byCodePoint), [
		"migration",
		"server",
		"webhook",
	]);
	for (const entry of identities) {
		assert.deepEqual(
			{ version: entry.version, commit: entry.commit },
			{ version: "1.2.3", commit: "c".repeat(40) },
			String(entry.component),
		);
		assert.equal(typeof entry.environment, "string", String(entry.component));
	}
});

await test("every revision migrates the database once, in a Job that only reaches PostgreSQL", () => {
	const first = rendered([...layersFor("small"), sandboxLayer]);
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
		[
			"APP_VERSION",
			"HEPHAESTUS_RELEASE_COMMIT",
			"DEPLOYMENT_ENVIRONMENT",
			"GITLAB_DEFAULT_SERVER_URL",
			"SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE",
			"SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE",
			"SPRING_PROFILES_ACTIVE",
			"DATABASE_URL",
			"DATABASE_USERNAME",
			"DATABASE_PASSWORD",
		],
	);
	// It starts once PostgreSQL accepts connections: on a first install, both start together.
	const [wait] = list(get(podSpec(migration), "initContainers"));
	const waitEnv = (name: string) =>
		list(get(wait, "env")).find((variable) => get(variable, "name") === name);
	assert.equal(get(waitEnv("DATABASE_HOST"), "value"), "heph-postgresql.hephaestus.svc");
	assert.equal(get(waitEnv("DATABASE_PORT"), "value"), "5432");
	// pg_isready runs as a user that the image's passwd does not name: without PGUSER it never dials.
	assert.equal(get(waitEnv("PGUSER"), "value"), "hephaestus_owner");
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
					clientTlsSecret: "hephaestus-postgresql-backup-tls",
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
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	for (const component of ["server", "webhook"]) {
		const resourcesOf = get(container(workload(resources, component)), "resources");
		assert.equal(
			get(resourcesOf, "requests", "memory"),
			get(resourcesOf, "limits", "memory"),
			component,
		);
	}
});

await test("PostgreSQL containers reserve their full memory limits in bundled profiles", () => {
	for (const name of ["small", "preview", "dev"] as const) {
		const backup = {
			enabled: true,
			clientTlsSecret: "backup-tls",
			s3: { bucket: "test-backups", endpoint: "s3.example.com", region: "eu-central-1" },
		};
		const resources = rendered([...layersFor(name), { postgresql: { backup } }]);
		const restoring = rendered([
			...layersFor(name),
			{ postgresql: { backup: { ...backup, restore: { enabled: true } } } },
		]);
		const stanza = resources.find(
			(resource) => kindOf(resource) === "Job" && nameOf(resource).includes("-stanza-"),
		);
		const scheduled = resources.find(
			(resource) => kindOf(resource) === "CronJob" && componentOf(resource) === "postgresql-backup",
		);
		const restore = restoring.find(
			(resource) => kindOf(resource) === "Job" && nameOf(resource).includes("-restore-"),
		);
		assert.ok(stanza && scheduled && restore, name);
		const specs = [
			podSpec(workload(resources, "postgresql")),
			podSpec(stanza),
			get(scheduled, "spec", "jobTemplate", "spec", "template", "spec"),
			podSpec(restore),
		];
		for (const spec of specs) {
			const entries = list(get(spec, "containers"));
			assert.ok(entries.length > 0);
			for (const entry of entries) {
				const limit = amount(get(entry, "resources", "limits", "memory"));
				assert.ok(limit > 0);
				assert.equal(
					amount(get(entry, "resources", "requests", "memory")),
					limit,
					`${name}: ${String(get(entry, "name"))}`,
				);
			}
		}
	}
});

await test("PostgreSQL has a lower kubelet OOM adjustment than review and conversation sandboxes", () => {
	// Kubelet's Burstable formula: kubernetes.io/docs/concepts/scheduling-eviction/node-pressure-eviction/.
	// Sandbox Pod totals bound each container request. Actual OOM scores also depend on process memory use.
	const nodeBytes = 16 * 2 ** 30;
	const adjustment = (requestBytes: number) =>
		Math.min(999, Math.max(2, 1000 - Math.floor((1000 * requestBytes) / nodeBytes)));
	const resources = rendered(layersFor("small"));
	const postgres = workload(resources, "postgresql");
	const limits = get(container(postgres), "resources", "limits");
	const requests = get(container(postgres), "resources", "requests");
	assert.ok(amount(get(requests, "cpu")) < amount(get(limits, "cpu")), "CPU remains burstable");
	const score = adjustment(amount(get(requests, "memory")));
	for (const sandbox of [sandboxPods.review, sandboxPods.turn]) {
		assert.ok(
			score < adjustment(sandbox.gib * 2 ** 30),
			"database precedes sandbox in OOM protection",
		);
	}
});

const databaseSetupDirectory = "/etc/hephaestus/database";
const databaseSetupFiles = [
	"database-quiet.sql",
	"database-roles.sql",
	"database-monitor.sql",
	"database-roles.sh",
	"database-monitor.sh",
	"scram-sha-256.pl",
];

/** The pod's container runs the chart's files from the ConfigMap, which holds each of them unchanged. */
function assertDatabaseSetup(resources: Resource[], pod: unknown, entry: unknown): void {
	const setup = resources.find(
		(resource) => kindOf(resource) === "ConfigMap" && componentOf(resource) === "database-setup",
	);
	assert.ok(setup, "the database-setup ConfigMap");
	const data = asRecord(get(setup, "data"), "database-setup data");
	assert.deepEqual(
		Object.keys(data).toSorted(byCodePoint),
		databaseSetupFiles.toSorted(byCodePoint),
	);
	for (const file of databaseSetupFiles) {
		const content = readFileSync(path.join(chart, "files", file), "utf8").replace(/\n$/u, "");
		assert.equal(data[file], content, `the ConfigMap holds files/${file} unchanged`);
	}
	assert.deepEqual(
		list(get(entry, "volumeMounts")).find((mount) => get(mount, "name") === "database-setup"),
		{ name: "database-setup", mountPath: databaseSetupDirectory, readOnly: true },
	);
	assert.deepEqual(
		get(
			list(get(podSpec(pod), "volumes")).find((volume) => get(volume, "name") === "database-setup"),
			"configMap",
		),
		{ name: nameOf(setup) },
	);
}

/** Every Pod template: Deployments, StatefulSets, Jobs, the Jobs of CronJobs and the sandbox templates. */
const podTemplates = (resources: Resource[]) =>
	resources.flatMap((resource) => {
		const kind = String(kindOf(resource));
		if (kind === "CronJob") {
			return [get(resource, "spec", "jobTemplate", "spec", "template", "spec")];
		}
		if (kind === "SandboxTemplate") {
			return [get(resource, "spec", "podTemplate", "spec")];
		}
		return ["Deployment", "StatefulSet", "DaemonSet", "Job"].includes(kind)
			? [get(resource, "spec", "template", "spec")]
			: [];
	});

await test("no container holds text that Kubernetes rewrites, beyond the references it means", () => {
	// Kubernetes replaces $(NAME) with the container's variable NAME and $$ with $ in a command, its arguments and
	// each variable's value. A script inlined there, such as SQL with $$ quotes, then runs changed: the chart ships
	// scripts as files.
	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const backup = { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 };
	const renders = [
		...profiles.map((name) => rendered([...layersFor(name), sandboxLayer])),
		rendered([
			...layersFor("small"),
			sandboxLayer,
			stackOn,
			{ postgresql: { metrics: { enabled: true }, backup } },
		]),
		rendered([
			...layersFor("small"),
			sandboxLayer,
			{ postgresql: { backup: { ...backup, restore: { enabled: true } } } },
		]),
	];
	// The one reference that the chart makes on purpose: the management listener binds to the Pod's address.
	const intended = new Set(["env MANAGEMENT_SERVER_ADDRESS: $(POD_IP)"]);
	const texts = renders
		.flatMap(podTemplates)
		.flatMap((spec) => [...list(get(spec, "initContainers")), ...list(get(spec, "containers"))])
		.flatMap((entry) => [
			...[...list(get(entry, "command")), ...list(get(entry, "args"))].map((text) => ({
				where: "command",
				text: String(text),
			})),
			...list(get(entry, "env"))
				.filter((variable) => typeof get(variable, "value") === "string")
				.map((variable) => ({
					where: `env ${String(get(variable, "name"))}`,
					text: String(get(variable, "value")),
				})),
		]);
	const found = texts.flatMap(({ where, text }) =>
		[...text.matchAll(/\$\$|\$\([A-Za-z_][A-Za-z0-9_]*\)/gu)].map(
			(match) => `${where}: ${match[0]}`,
		),
	);
	assert.ok(
		texts.some(({ where }) => where === "command"),
		"the renders hold commands",
	);
	assert.deepEqual(
		found.filter((reference) => !intended.has(reference)),
		[],
	);
	// A reference that is no longer made leaves the list, so that the list names only what the chart means.
	assert.deepEqual(
		[...intended].filter((reference) => !found.includes(reference)),
		[],
	);
});

/** The Secret keys that one container reads. */
const secretKeysOf = (entry: unknown): string[] =>
	list(get(entry, "env")).flatMap((variable) => {
		const key = get(variable, "valueFrom", "secretKeyRef", "key");
		return typeof key === "string" ? [key] : [];
	});

const databaseUserOf = (entry: unknown) =>
	get(
		list(get(entry, "env")).find((variable) => get(variable, "name") === "DATABASE_USERNAME"),
		"value",
	);

const migrationJobOf = (resources: Resource[]) => {
	const job = resources.find(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
	);
	assert.ok(job, "one migration Job");
	return job;
};

await test("each database purpose logs in with its own role, and no Hephaestus process as the administrator", () => {
	const bundledRelease = rendered([...layersFor("small"), sandboxLayer]);
	for (const component of ["server"]) {
		const role = workload(bundledRelease, component);
		assert.equal(databaseUserOf(container(role)), "hephaestus_app", component);
		assert.deepEqual(
			get(env(role, "DATABASE_PASSWORD"), "valueFrom", "secretKeyRef", "key"),
			"DATABASE_APP_PASSWORD",
			component,
		);
		assert.ok(
			!secretKeysOf(container(role)).some((key) => key.startsWith("DATABASE_ADMIN_")),
			component,
		);
	}
	const migration = migrationJobOf(bundledRelease);
	assert.equal(databaseUserOf(container(migration)), "hephaestus_owner");
	assert.deepEqual(
		secretKeysOf(container(migration)).filter((key) => key.startsWith("DATABASE_")),
		["DATABASE_OWNER_PASSWORD"],
	);
	// Only the init container that sets up the roles holds the administrator's login.
	const roles = list(get(podSpec(migration), "initContainers")).find(
		(entry) => get(entry, "name") === "database-roles",
	);
	assert.notEqual(roles, undefined, "the database-roles init container");
	assert.deepEqual(secretKeysOf(roles), [
		"DATABASE_ADMIN_USERNAME",
		"DATABASE_ADMIN_PASSWORD",
		"DATABASE_OWNER_PASSWORD",
		"DATABASE_APP_PASSWORD",
	]);
	// It runs the chart's script from the ConfigMap, the same script that the integration tests run: its command
	// holds no SQL, which Kubernetes would rewrite.
	assert.deepEqual(get(roles, "command"), ["sh", `${databaseSetupDirectory}/database-roles.sh`]);
	assertDatabaseSetup(bundledRelease, migration, roles);
	const postgresql = workload(bundledRelease, "postgresql");
	assert.deepEqual(secretKeysOf(container(postgresql)), [
		"DATABASE_ADMIN_USERNAME",
		"DATABASE_ADMIN_PASSWORD",
	]);
	// An operator's PostgreSQL: the chart never holds its administrator's login.
	const externalMigration = migrationJobOf(rendered(layersFor("external")));
	assert.equal(
		list(get(podSpec(externalMigration), "initContainers")).some(
			(entry) => get(entry, "name") === "database-roles",
		),
		false,
	);
	assert.ok(
		!secretKeysOf(container(externalMigration)).some((key) => key.startsWith("DATABASE_ADMIN_")),
	);
});

await test("every role verifies an external PostgreSQL with the CA alone", () => {
	const resources = rendered(layersFor("external"));
	assert.equal(
		workloads(resources).some((resource) => componentOf(resource) === "postgresql"),
		false,
	);
	const url = `postgresql://${external.postgresql.external.host}:5432/hephaestus?${verifyFull}`;
	const role = workload(resources, "server");
	assert.equal(envValue(role, "DATABASE_URL"), url);
	assertCaOnly(role, "server");
});

await test("the internet-facing webhook role holds only the secrets of its own work", () => {
	const resources = rendered([...layersFor("small"), sandboxLayer]);
	const webhook = workload(resources, "webhook");
	const secrets = list(get(container(webhook), "env"))
		.map((variable) => get(variable, "valueFrom", "secretKeyRef", "key"))
		.filter((key) => key !== undefined);
	assert.deepEqual(
		new Set(secrets),
		new Set([
			"HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET",
			"NATS_PASSWORD",
			"NATS_USERNAME",
			"WEBHOOK_ROUTING_PREVIOUS_SECRET",
			"WEBHOOK_ROUTING_SECRET",
			"WEBHOOK_SECRET",
		]),
	);
	assert.equal(env(webhook, "DATABASE_URL"), undefined);
	assert.deepEqual(
		list(get(podSpec(webhook), "volumes")).filter((volume) => get(volume, "secret") !== undefined),
		[],
	);
	// The database admits only the roles that hold its login.
	const database = resources.find(
		(resource) => kindOf(resource) === "NetworkPolicy" && nameOf(resource).endsWith("postgresql"),
	);
	assert.ok(database, "the PostgreSQL NetworkPolicy");
	const admitted = new Set(
		list(get(database, "spec", "ingress", 0, "from")).map((peer) =>
			get(peer, "podSelector", "matchLabels", "app.kubernetes.io/component"),
		),
	);
	assert.ok(admitted.has("server"));
	assert.ok(!admitted.has("webhook"));
});

await test("replica counts stop the database roles, start the server alone, and restore the release", () => {
	const roles = ["server", "webhook"] as const;
	const counts = (resources: Resource[]) =>
		roles.map((component) => get(workload(resources, component), "spec", "replicas"));
	const enabled = [...layersFor("small"), sandboxLayer];
	const running = rendered(enabled);
	assert.deepEqual(counts(running), [1, 1]);

	// Stopped: the objects stay, so the server keeps its Deployment, its policies and the sandbox namespace's.
	const stopped = rendered([...enabled, { server: { replicas: 0 }, webhook: { replicas: 0 } }]);
	assert.deepEqual(counts(stopped), [0, 0]);
	assert.deepEqual(
		stopped.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`),
		running.map((resource) => `${String(kindOf(resource))}/${nameOf(resource)}`),
	);
	assert.equal(get(workload(stopped, "server"), "spec", "strategy", "type"), "Recreate");
	for (const component of ["postgresql", "nats", "webapp"]) {
		assert.equal(get(workload(stopped, component), "spec", "replicas"), 1, component);
	}

	// The server migrates alone before the roles that read its schema start again.
	const migrating = rendered([...enabled, { webhook: { replicas: 0 } }]);
	assert.deepEqual(counts(migrating), [1, 0]);

	// Restoring the prior counts renders the release as it was.
	const restored = rendered([...enabled, { server: { replicas: 1 }, webhook: { replicas: 1 } }]);
	assert.deepEqual(restored, running);

	// Without sandboxes the chart writes nothing into the sandbox namespace.
	const disabled = rendered([...layersFor("small"), { sandbox: { enabled: false } }]);
	assert.equal(
		disabled.some((resource) => componentOf(resource) === "sandbox"),
		false,
	);
});

await test("values that the chart does not accept fail the render", () => {
	const withoutPrepared = [profile("small"), operator];
	const { host: _host, ...withoutHost } = operator;
	const { secret: _secret, ...withoutSecret } = operator;
	const { trustedProxies: _trustedProxies, ...withoutProxies } = operator;
	const withSandbox = (change: object): Layer[] => [...layersFor("small"), sandboxLayer, change];
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
		...(["pods", "cpu", "memory"] as const).map((key): [string, Layer[], RegExp] => [
			`a hand-set sandbox quota (${key})`,
			[...layersFor("small"), { sandbox: { quota: { [key]: key === "pods" ? 10 : "8" } } }],
			/quota/u,
		]),
		[
			"an operator class missing for a sandbox tier",
			[
				...layersFor("small"),
				{ priorityClasses: { create: false, roles: "a", conversations: "", reviews: "c" } },
			],
			/priorityClasses[./]conversations/u,
		],
		[
			"one operator class for both sandbox tiers",
			[
				...layersFor("small"),
				{ priorityClasses: { create: false, roles: "a", conversations: "b", reviews: "b" } },
			],
			/must differ/u,
		],
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
			"a whole trace collector namespace",
			[
				...layersFor("small"),
				{ networkPolicy: { traceCollector: { namespace: "observability", podLabels: {} } } },
			],
			/networkPolicy\.traceCollector\.podLabels needs at least one label/u,
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
			/sandbox\.namespace is required/u,
		],
		[
			"the single-node profile without the Kubernetes API endpoint",
			[profile("small"), prepared, operator, { sandbox: { namespace: sandboxNamespace } }],
			/networkPolicy\.kubernetesApi\.cidr is required/u,
		],
		[
			"a second server",
			[...layersFor("small"), { server: { replicas: 2 } }],
			/server[./]replicas/u,
		],
		[
			"a fourth webhook receiver",
			withSandbox({ webhook: { replicas: 4 } }),
			/webhook[./]replicas/u,
		],
		[
			"a negative webhook count",
			withSandbox({ webhook: { replicas: -1 } }),
			/webhook[./]replicas/u,
		],
		[
			"a server rollout of an unknown kind",
			withSandbox({ server: { rolloutStrategy: "BlueGreen" } }),
			/server[./]rolloutStrategy/u,
		],
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
			"sandboxes without the API port",
			withSandbox({ networkPolicy: { kubernetesApi: { port: 0 } } }),
			/networkPolicy\.kubernetesApi\.port is required/u,
		],
		[
			"sandboxes without their namespace",
			withSandbox({ sandbox: { namespace: "" } }),
			/sandbox\.namespace is required/u,
		],
		[
			"sandboxes in the release namespace",
			withSandbox({ sandbox: { namespace: "hephaestus" } }),
			/sandbox\.namespace must not be the release namespace/u,
		],
		[
			"a backup of an external database",
			[
				...layersFor("external"),
				{
					postgresql: {
						backup: {
							clientTlsSecret: "hephaestus-postgresql-backup-tls",
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
			[
				...layersFor("small"),
				{
					postgresql: {
						backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true },
					},
				},
			],
			/postgresql\.backup\.s3\.bucket is required/u,
		],
		[
			"a seccomp profile outside the kubelet's directory",
			withSandbox({ sandbox: { runtime: { seccompProfile: "../agent.json" } } }),
			/sandbox\.runtime\.seccompProfile must stay inside/u,
		],
	];
	for (const [label, layers, message] of cases) {
		const result = render(layers);
		assert.equal(result.ok, false, `${label} rendered`);
		assert.match(result.stderr, message, label);
	}
});

await test("a private CA reaches the roles that call a Git provider, and is off by default", () => {
	const caFile = "/etc/hephaestus/private-ca/ca.crt";
	const resources = rendered([
		...layersFor("small"),
		{ config: { privateCa: { existingSecret: "corp-ca", key: "bundle.pem" } } },
	]);
	for (const component of ["server", "webhook"]) {
		const role = workload(resources, component);
		const calls = component !== "webhook";
		assert.equal(envValue(role, "SSL_CERT_FILE"), calls ? caFile : undefined, component);
		const volumes = list(get(role, "spec", "template", "spec", "volumes"));
		const volume = volumes.find((candidate) => get(candidate, "name") === "private-ca");
		assert.equal(get(volume, "secret", "secretName"), calls ? "corp-ca" : undefined, component);
		assert.equal(
			get(list(get(volume, "secret", "items"))[0], "key"),
			calls ? "bundle.pem" : undefined,
		);
	}
	const defaults = rendered(layersFor("small"));
	for (const component of ["server", "webhook"]) {
		assert.equal(envValue(workload(defaults, component), "SSL_CERT_FILE"), undefined, component);
	}
});

await test("the webhook trusts an external TLS broker only with a configured CA bundle", () => {
	const externalBroker = { url: "tls://broker.example.test:4222", cidr: "10.0.13.0/24" };
	const bundle = { existingSecret: "broker-ca", key: "bundle.pem" };
	const expectedBundle = {
		env: "/etc/hephaestus/private-ca/ca.crt",
		volumes: [
			{
				name: "private-ca",
				secret: {
					secretName: "broker-ca",
					items: [{ key: "bundle.pem", path: "ca.crt" }],
					defaultMode: 292,
				},
			},
		],
		mounts: [{ name: "private-ca", mountPath: "/etc/hephaestus/private-ca", readOnly: true }],
	};
	const noBundle = { env: undefined, volumes: [], mounts: [] };
	for (const scenario of [
		{
			label: "external TLS",
			nats: { bundled: false, external: externalBroker },
			privateCa: bundle,
			expected: expectedBundle,
		},
		{
			label: "plain broker",
			nats: {
				bundled: false,
				external: { ...externalBroker, url: "nats://broker.example.test:4222" },
			},
			privateCa: bundle,
			expected: noBundle,
		},
		{
			label: "bundled broker",
			nats: { bundled: true, external: externalBroker },
			privateCa: bundle,
			expected: noBundle,
		},
		{
			label: "no private CA",
			nats: { bundled: false, external: externalBroker },
			privateCa: { existingSecret: "", key: "ca.crt" },
			expected: noBundle,
		},
	]) {
		const resources = rendered([
			...layersFor("small"),
			{ nats: scenario.nats, config: { privateCa: scenario.privateCa } },
		]);
		const receiver = workload(resources, "webhook");
		assert.deepEqual(
			{
				env: envValue(receiver, "SSL_CERT_FILE"),
				volumes: list(get(receiver, "spec", "template", "spec", "volumes")).filter(
					(volume) => get(volume, "name") === "private-ca",
				),
				mounts: list(get(container(receiver), "volumeMounts")).filter(
					(mount) => get(mount, "name") === "private-ca",
				),
			},
			scenario.expected,
			scenario.label,
		);
	}
});

await test("the migration knows the configured GitLab, which a connection without a URL used", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			config: {
				gitlab: { enabled: true, oauthClientId: "id", oauthBaseUrl: "https://gitlab.corp.example" },
			},
		},
	]);
	const job = resources.find(
		(resource) =>
			kindOf(resource) === "Job" && /migration/u.test(String(get(resource, "metadata", "name"))),
	);
	const variables = list(get(list(get(job, "spec", "template", "spec", "containers"))[0], "env"));
	assert.equal(
		get(
			variables.find((entry) => get(entry, "name") === "GITLAB_DEFAULT_SERVER_URL"),
			"value",
		),
		"https://gitlab.corp.example",
	);
});

await test("a Git provider on a private network reaches the server, never the webhook role", () => {
	const endpoint = { cidr: "10.30.0.0/16", ports: [443] };
	const resources = rendered([
		...layersFor("small"),
		{
			config: { scmPrivateHosts: ["gitlab.corp.example"] },
			networkPolicy: { scmEndpoints: [endpoint] },
		},
	]);
	const rule = {
		to: [{ ipBlock: { cidr: endpoint.cidr } }],
		ports: [{ port: 443, protocol: "TCP" }],
	};
	for (const component of ["server", "webhook"]) {
		const calls = component !== "webhook";
		assert.equal(
			envValue(workload(resources, component), "HEPHAESTUS_SCM_EGRESS_PRIVATE_HOSTS"),
			calls ? "gitlab.corp.example" : undefined,
			component,
		);
		const policy = resources.find(
			(resource) =>
				kindOf(resource) === "NetworkPolicy" &&
				get(resource, "metadata", "namespace") === undefined &&
				isDeepStrictEqual(
					get(resource, "spec", "podSelector", "matchLabels"),
					get(workload(resources, component), "spec", "selector", "matchLabels"),
				),
		);
		const egress = list(get(policy, "spec", "egress"));
		assert.equal(
			egress.some((candidate) => isDeepStrictEqual(candidate, rule)),
			calls,
			component,
		);
	}
});

await test("a model endpoint on a private network reaches the server, never the metadata range", () => {
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
	for (const component of ["server", "webhook"]) {
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

await test("traces reach an in-cluster collector on the endpoint's port, only while tracing is on", () => {
	const collector = {
		namespace: "observability",
		podLabels: { "app.kubernetes.io/name": "otel-collector" },
	};
	const layers = (tracing: object, traceCollector: object = collector) => [
		...layersFor("small"),
		{ config: { tracing }, networkPolicy: { traceCollector } },
	];
	/** The components whose NetworkPolicy opens the collector on `port`, across every policy. */
	const opened = (tracing: object, port: number, traceCollector?: object) => {
		const resources = rendered(layers(tracing, traceCollector));
		const rule = {
			to: [
				{
					namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "observability" } },
					podSelector: { matchLabels: collector.podLabels },
				},
			],
			ports: [{ port, protocol: "TCP" }],
		};
		return resources
			.filter((resource) => kindOf(resource) === "NetworkPolicy")
			.filter((policy) =>
				list(get(policy, "spec", "egress")).some((egress) => isDeepStrictEqual(egress, rule)),
			)
			.map((policy) =>
				["server", "webhook"].find((component) =>
					isDeepStrictEqual(
						get(policy, "spec", "podSelector", "matchLabels"),
						get(workload(resources, component), "spec", "selector", "matchLabels"),
					),
				),
			)
			.toSorted((left = "", right = "") => left.localeCompare(right));
	};
	const exporters = ["server", "webhook"];
	for (const [endpoint, port] of [
		["http://otel-collector.observability.svc:4318/v1/traces", 4318],
		["http://otel-collector.observability.svc:04318/v1/traces", 4318],
		["http://otel-collector.observability.svc/v1/traces", 80],
		["https://otel-collector.observability.svc/v1/traces", 443],
		["http://[fd00::1]:4318/v1/traces", 4318],
		["http://[fd00::1]/v1/traces", 80],
	] as const) {
		assert.deepEqual(
			opened({ otlpEnabled: true, otlpEndpoint: endpoint }, port),
			exporters,
			endpoint,
		);
	}
	const endpoint = "http://otel-collector.observability.svc:4318/v1/traces";
	assert.deepEqual(opened({ otlpEnabled: false, otlpEndpoint: endpoint }, 4318), []);
	assert.deepEqual(
		opened({ otlpEnabled: true, otlpEndpoint: endpoint }, 4318, { namespace: "", podLabels: {} }),
		[],
	);
	for (const port of ["0", "65536"]) {
		const result = render(
			layers({
				otlpEnabled: true,
				otlpEndpoint: `http://otel-collector.observability.svc:${port}/v1/traces`,
			}),
		);
		assert.equal(result.ok, false, port);
		assert.match(result.stderr, /otlpEndpoint names a port outside 1-65535/u);
	}
});

await test("one value turns browser fault reports on for the server and for the browser", () => {
	for (const browserFaultReports of [false, true]) {
		const resources = rendered([...layersFor("small"), { config: { browserFaultReports } }]);
		for (const component of ["server", "webapp"]) {
			assert.equal(
				envValue(workload(resources, component), "BROWSER_FAULT_REPORTS"),
				String(browserFaultReports),
				component,
			);
		}
	}
});

await test("workspace hosts reach only the SPA and share the apex origin settings", () => {
	const layers = [profile("small"), prepared, operator, reviews];
	const off = rendered(layers);
	assert.equal(off.filter((resource) => kindOf(resource) === "HTTPRoute").length, 1);
	const resources = rendered([...layers, { workspaceSubdomains: { enabled: true } }]);
	const routes = resources.filter((resource) => kindOf(resource) === "HTTPRoute");
	const tenant = routes.find(
		(resource) => get(resource, "spec", "hostnames", 0) === "*.hephaestus.example.com",
	);
	assert.ok(tenant);
	assert.deepEqual(
		list(get(tenant, "spec", "rules"))
			.flatMap((rule) => list(get(rule, "backendRefs")))
			.map((backend) => get(backend, "name")),
		["heph-webapp"],
	);
	for (const component of ["server", "webapp"]) {
		const deployment = resources.find(
			(resource) => kindOf(resource) === "Deployment" && componentOf(resource) === component,
		);
		const variables = list(get(podSpec(deployment), "containers", 0, "env"));
		assert.equal(
			get(
				variables.find((entry) => get(entry, "name") === "HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED"),
				"value",
			),
			"true",
		);
		assert.equal(
			get(
				variables.find(
					(entry) => get(entry, "name") === "HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN",
				),
				"value",
			),
			"hephaestus.example.com",
		);
	}
});

await test("a restore clone starts PostgreSQL alone before lockdown and migrations", () => {
	const resources = rendered([
		profile("small"),
		prepared,
		operator,
		reviews,
		{
			server: { replicas: 0 },
			webhook: { replicas: 0 },
			migration: { enabled: false },
		},
	]);
	assert.equal(
		resources.some(
			(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
		),
		false,
	);
	assert.equal(
		get(
			resources.find(
				(resource) => kindOf(resource) === "StatefulSet" && componentOf(resource) === "postgresql",
			),
			"spec",
			"replicas",
		),
		1,
	);
	for (const component of ["server", "webhook"]) {
		assert.equal(
			get(
				resources.find(
					(resource) => kindOf(resource) === "Deployment" && componentOf(resource) === component,
				),
				"spec",
				"replicas",
			),
			0,
		);
	}
});

await test("tenant hostname validation checks double hyphens only in the workspace label", () => {
	for (const host of ["hephaestus.example.com", "heph--prod.example.org"]) {
		const resources = rendered([
			profile("small"),
			prepared,
			operator,
			reviews,
			{ host, workspaceSubdomains: { enabled: true } },
		]);
		const routing = resources.find(
			(resource) =>
				kindOf(resource) === "ConfigMap" &&
				componentOf(resource) === "webapp" &&
				typeof get(resource, "data", "maps.conf") === "string",
		);
		const maps = get(routing, "data", "maps.conf");
		assert.equal(typeof maps, "string");
		const pattern = /"~(?<pattern>[^"\n]+)" 1;/u.exec(String(maps))?.groups?.pattern;
		assert.ok(pattern !== undefined);
		const accepts = new RegExp(pattern, "u");
		assert.ok(accepts.test(`team-one.${host}`));
		assert.ok(accepts.test(`abc.${host}`));
		for (const slug of ["team--one", "api", "ab", "-team", "team-", "a".repeat(52)]) {
			assert.equal(accepts.test(`${slug}.${host}`), false, slug);
		}
	}
});
await test("small PostgreSQL overrides the image calculator and gives each database role its own pool", () => {
	const resources = rendered([prepared, profile("small"), operator, reviews]);
	const postgres = workload(resources, "postgresql");
	const args = list(get(container(postgres), "args"));
	const settings = Object.fromEntries(
		args.flatMap((arg, index) => {
			if (args[index - 1] !== "-c" || typeof arg !== "string") {
				return [];
			}
			const equals = arg.indexOf("=");
			return [[arg.slice(0, equals), arg.slice(equals + 1)]];
		}),
	);
	assert.equal(get(container(postgres), "command"), undefined, "the image entrypoint still runs");
	assert.equal(settings.shared_buffers, "256MB");
	assert.equal(settings.work_mem, "2MB");
	assert.equal(settings.hash_mem_multiplier, "1");
	assert.equal(settings.max_connections, "80");
	assert.equal(settings.maintenance_work_mem, "64MB");
	assert.equal(settings.autovacuum_work_mem, "32MB");
	assert.equal(settings.autovacuum_max_workers, "3");
	assert.equal(settings.effective_cache_size, "1GB");
	assert.equal(settings.max_parallel_workers_per_gather, "0");
	assert.equal(settings.max_wal_size, "1GB");
	assert.equal(
		get(container(postgres), "resources", "limits", "memory"),
		"2Gi",
		"the lower trial ceiling is not enabled",
	);
	const migration = resources.find(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
	);
	assert.ok(migration);
	for (const [role, maximum, minimum] of [
		[workload(resources, "server"), "40", "2"],
		[migration, "2", "1"],
	] as const) {
		assert.equal(envValue(role, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"), maximum);
		assert.equal(envValue(role, "SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE"), minimum);
	}
	assert.equal(
		envValue(workload(resources, "webhook"), "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"),
		undefined,
	);
});

const maxConnections = (layers: readonly Layer[]) => {
	const args = list(get(container(workload(rendered(layers), "postgresql")), "args"));
	return args.findLast((arg) => typeof arg === "string" && arg.startsWith("max_connections="));
};

await test("without an explicit max_connections the database gets what the role pools need", () => {
	const base = [prepared, operator, reviews, { sandbox: { enabled: true } }];
	// One server, its listener, the migration pool and the reserves.
	assert.equal(maxConnections(base), "max_connections=100");
	// A larger pool is not limited by a number that the image derives from memory alone.
	assert.equal(
		maxConnections([...base, { server: { databasePool: { maximumPoolSize: 200 } } }]),
		"max_connections=212",
	);
	// An explicit value stays the operator's.
	assert.equal(
		maxConnections([...base, { postgresql: { parameters: { max_connections: 200 } } }]),
		"max_connections=200",
	);
});

await test("restore configuration preserves max_connections while application roles are paused", () => {
	const base = [prepared, operator, reviews, { sandbox: { enabled: true } }];
	const source = maxConnections(base);
	assert.ok(typeof source === "string");
	const paused = {
		migration: { enabled: false },
		server: { replicas: 0 },
		webhook: { replicas: 0 },
	};
	assert.equal(maxConnections([...base, paused]), "max_connections=100");
	const preserved = {
		postgresql: {
			parameters: { max_connections: Number(source.slice("max_connections=".length)) },
		},
	};
	assert.equal(maxConnections([...base, paused, preserved]), source);
	assert.equal(maxConnections([...base, preserved]), source);
});

await test("the small database holds its one server and rejects a surge or pools beyond the connection budget", () => {
	const base = [prepared, profile("small"), operator, reviews];
	assert.ok(render(base).ok);
	assert.ok(render([...base, { server: { databasePool: { maximumPoolSize: 68 } } }]).ok);
	for (const layer of [
		{ server: { databasePool: { maximumPoolSize: 69 } } },
		{ postgresql: { parameters: { max_connections: 40 } } },
	]) {
		const result = render([...base, layer]);
		assert.equal(result.ok, false);
		assert.match(result.stderr, /max_connections needs at least/u);
	}
});

await test("database pool and PostgreSQL settings reject invalid operator values", () => {
	for (const layer of [
		{ server: { databasePool: { maximumPoolSize: 1, minimumIdle: 2 } } },
		{ migration: { databasePool: { maximumPoolSize: 0 } } },
		{ postgresql: { parameters: { max_connections: "80" } } },
		{ postgresql: { parameters: { work_mem: "unbounded" } } },
		{ postgresql: { parameters: { fsync: "off" } } },
	]) {
		assert.equal(render([prepared, profile("small"), operator, reviews, layer]).ok, false);
	}
});

await test("every resident small-profile PostgreSQL container reserves its full memory ceiling", () => {
	const resources = rendered([
		...layersFor("small"),
		{
			postgresql: {
				backup: {
					clientTlsSecret: "hephaestus-postgresql-backup-tls",
					enabled: true,
					s3: { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" },
				},
			},
		},
	]);
	const postgres = workload(resources, "postgresql");
	const postgresContainers = list(get(podSpec(postgres), "containers"));
	assert.equal(postgresContainers.length, 2);
	for (const resident of postgresContainers) {
		assert.equal(
			get(resident, "resources", "requests", "memory"),
			get(resident, "resources", "limits", "memory"),
			String(get(resident, "name")),
		);
	}
});

await test("disruption budgets protect singleton roles and select only their own Pods", () => {
	for (const replicas of [1, 2]) {
		const resources = rendered([
			prepared,
			operator,
			sandboxLayer,
			{ conversationLoop: { replicas }, webhook: { replicas: 2 } },
		]);
		const budgets = resources.filter((resource) => kindOf(resource) === "PodDisruptionBudget");
		assert.equal(budgets.length, 5);
		// The loops leave one Pod at a time where they have more than one; the one server stays.
		const several = (component: string) => component === "conversation-loop" && replicas > 1;
		for (const component of ["postgresql", "nats", "server", "conversation-loop", "webhook"]) {
			const budget = budgets.find((resource) => componentOf(resource) === component);
			assert.ok(budget, component);
			const spec = get(budget, "spec");
			assert.equal(get(spec, "minAvailable"), several(component) ? undefined : 1, component);
			assert.equal(get(spec, "maxUnavailable"), several(component) ? 1 : undefined, component);
			const selector = get(spec, "selector", "matchLabels");
			assert.deepEqual(
				selector,
				get(workload(resources, component), "spec", "selector", "matchLabels"),
			);
			for (const resource of resources.filter((item) =>
				["Deployment", "StatefulSet", "Job"].includes(String(kindOf(item))),
			)) {
				assert.equal(
					selects(selector, get(resource, "spec", "template", "metadata", "labels")),
					componentOf(resource) === component,
					`${component} budget / ${nameOf(resource)}`,
				);
			}
		}
	}
});

await test("external services and disabled sandboxes have no disruption budget", () => {
	const resources = rendered([prepared, operator, external]);
	assert.deepEqual(
		resources.filter((resource) => kindOf(resource) === "PodDisruptionBudget").map(componentOf),
		["server"],
	);
});

/**
 * The NOTES.txt that an install prints. `helm template` leaves NOTES.txt out, so this runs a
 * client-side dry-run install, which needs no cluster.
 */
function installNotes(layers: readonly Layer[]): string {
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-chart-"));
	try {
		const result = spawnSync(
			"helm",
			[
				"install",
				"heph",
				chart,
				"--dry-run=client",
				"--namespace",
				"hephaestus",
				...valuesFiles(directory, layers).flatMap((file) => ["--values", file]),
			],
			{ encoding: "utf8", timeout: RENDER_TIMEOUT_MS },
		);
		if (result.error !== undefined) {
			throw result.error;
		}
		assert.equal(result.status, 0, result.stderr);
		const at = result.stdout.indexOf("\nNOTES:\n");
		assert.notEqual(at, -1, "the install printed no NOTES");
		return result.stdout.slice(at);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

await test("an install warns when the bundled PostgreSQL has no backup, and only then", () => {
	const warning = "WARNING: the bundled PostgreSQL has no backup.";
	const backupDocs = "https://docs.hephaestus.build/admin/backup-restore#back-up-the-database";
	const unprotected = installNotes(layersFor("small"));
	assert.ok(unprotected.includes(warning), unprotected);
	assert.ok(unprotected.includes(backupDocs), unprotected);

	const s3 = { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" };
	const backedUp = installNotes([
		...layersFor("small"),
		{
			postgresql: {
				backup: { clientTlsSecret: "hephaestus-postgresql-backup-tls", enabled: true, s3 },
			},
		},
	]);
	assert.ok(!backedUp.includes(warning), backedUp);
	// An external database is backed up where it runs, which the chart cannot see.
	assert.ok(!installNotes(layersFor("external")).includes(warning));
});

const metricsOn = { postgresql: { metrics: { enabled: true } } };
const monitorJobs = (resources: Resource[]) =>
	resources.filter(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "postgresql-monitor",
	);
const exporterParts = (resources: Resource[]) =>
	resources.filter((resource) =>
		[componentOf(resource), nameOf(resource)].some((value) =>
			String(value).includes("postgresql-exporter"),
		),
	);
/** The components that the bundled PostgreSQL admits on its port. */
const postgresqlPeers = (resources: Resource[]) =>
	list(
		get(
			resources.find(
				(resource) =>
					kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-postgresql",
			),
			"spec",
			"ingress",
			0,
			"from",
		),
	).map((peer) => get(peer, "podSelector", "matchLabels", "app.kubernetes.io/component"));

await test("the PostgreSQL exporter runs apart from the database, and its role Job only beside the bundled server", () => {
	// Off by default: no exporter, no Job, and PostgreSQL admits no monitoring client.
	const off = rendered(layersFor("small"));
	assert.deepEqual(exporterParts(off).map(nameOf), []);
	assert.deepEqual(monitorJobs(off).map(nameOf), []);
	assert.ok(!postgresqlPeers(off).includes("postgresql-exporter"));

	const on = rendered([...layersFor("small"), metricsOn]);
	// Turning the exporter on does not touch the database's Pod, so PostgreSQL does not restart.
	assert.deepEqual(workload(on, "postgresql"), workload(off, "postgresql"));
	const exporter = container(workload(on, "postgresql-exporter"));
	assert.equal(get(exporter, "image"), prepared.images["postgres-exporter"]);
	const args = list(get(exporter, "args"));
	assert.ok(args.includes("--collector.stat_statements.limit=20"), String(args));
	assert.ok(args.includes("--no-collector.stat_statements.include_query"), String(args));
	// The transaction ID age that HephaestusDatabaseTransactionIdAgeHigh reads.
	assert.ok(args.includes("--collector.database_wraparound"), String(args));
	const exporterEnv = list(get(exporter, "env"));
	const entry = (name: string) => exporterEnv.find((candidate) => get(candidate, "name") === name);
	assert.match(
		String(get(entry("DATA_SOURCE_URI"), "value")),
		/^heph-postgresql\.hephaestus\.svc:5432\/hephaestus\?sslmode=verify-full&/u,
	);
	assert.equal(get(entry("DATA_SOURCE_USER"), "value"), "hephaestus_monitor");
	assert.equal(
		get(entry("DATA_SOURCE_PASS"), "valueFrom", "secretKeyRef", "key"),
		"DATABASE_MONITOR_PASSWORD",
	);
	const [job] = monitorJobs(on);
	assert.ok(job, "one monitor Job");
	assert.equal(nameOf(job), "heph-postgresql-monitor-1");
	const script = String(
		list(get(job, "spec", "template", "spec", "containers", 0, "command")).at(-1),
	);
	// The chart's monitor script, from the ConfigMap: PostgreSQL gets only the SCRAM verifier of the password, and the
	// password is set on every revision, so a rotated key takes effect.
	assert.match(
		script,
		new RegExp(`^exec sh ${databaseSetupDirectory}/database-monitor\\.sh$`, "mu"),
	);
	assert.doesNotMatch(
		script,
		/psql/u,
		"the Job's command holds no SQL, which Kubernetes would rewrite",
	);
	assertDatabaseSetup(on, job, list(get(job, "spec", "template", "spec", "containers"))[0]);
	assert.deepEqual(postgresqlPeers(on).slice(-2), ["postgresql-exporter", "postgresql-monitor"]);

	// An external PostgreSQL is the operator's to prepare: the exporter reads it, and no Job runs.
	const externalDatabase = rendered([...layersFor("external"), metricsOn]);
	assert.notEqual(container(workload(externalDatabase, "postgresql-exporter")), undefined);
	assert.deepEqual(monitorJobs(externalDatabase).map(nameOf), []);

	// The stack scrapes the exporter under a job of its own, on the port that its policy opens.
	const stacked = rendered([...layersFor("small"), stackOn, metricsOn]);
	const scrape = stacked
		.filter((resource) => kindOf(resource) === "ConfigMap")
		.map((resource) => JSON.stringify(get(resource, "data")))
		.find((data) => data.includes("scrape_configs"));
	assert.ok(scrape !== undefined && scrape.includes("job_name: postgresql"), scrape);
	const exporterPolicy = stacked.find(
		(resource) =>
			kindOf(resource) === "NetworkPolicy" && nameOf(resource) === "heph-postgresql-exporter",
	);
	assert.deepEqual(
		list(get(exporterPolicy, "spec", "ingress")).map((rule) => get(rule, "ports", 0, "port")),
		["metrics"],
	);
});

await test("chart list inputs keep their order in environment bindings for each role", () => {
	const bootstrapAdmins = ["github:1234567", "gitlab:@example"];
	const browserExtensionIds = ["a".repeat(32), "b".repeat(32)];
	const origins = ["https://first.example", "https://second.example"];
	for (const populated of [false, true]) {
		const resources = rendered([
			...layersFor("small"),
			{
				config: {
					auth: {
						bootstrapAdmins: populated ? bootstrapAdmins : [],
						browserExtensionIds: populated ? browserExtensionIds : [],
						passkeys: { allowedOrigins: populated ? origins : [] },
					},
					outline: { allowedOrigins: populated ? origins : [] },
				},
			},
		]);
		const server = workload(resources, "server");
		assert.equal(
			envValue(server, "HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS"),
			populated ? bootstrapAdmins.join(",") : "",
		);
		assert.equal(
			envValue(server, "HEPHAESTUS_AUTH_BROWSER_EXTENSION_IDS"),
			populated ? browserExtensionIds.join(",") : "",
		);
		assert.equal(
			envValue(server, "HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS"),
			populated ? origins.join(",") : "",
		);
		for (const component of ["server", "webhook"]) {
			assert.equal(
				envValue(workload(resources, component), "HEPHAESTUS_INTEGRATION_OUTLINE_ALLOWED_ORIGINS"),
				populated ? origins.join(",") : "",
				component,
			);
		}
	}
});

const templatesOf = (resources: Resource[]) =>
	resources.filter((resource) => kindOf(resource) === "SandboxTemplate");

await test("each sandbox kind has a cold-start template of one container, behind the namespace's own policies", () => {
	const runc = rendered([...layersFor("small"), sandboxLayer]);
	const templates = templatesOf(runc);
	assert.deepEqual(templates.map(nameOf).toSorted(byCodePoint), [
		"heph-sandbox-conversation",
		"heph-sandbox-review",
	]);
	const pools = runc.filter((resource) => kindOf(resource) === "SandboxWarmPool");
	for (const template of templates) {
		assert.equal(get(template, "metadata", "namespace"), sandboxNamespace);
		// The controller's own policy allows the internet; the namespace's policies apply instead.
		assert.equal(get(template, "spec", "networkPolicyManagement"), "Unmanaged");
		// A claim adds the sandbox token and the gateway address and cannot change the template's values.
		assert.equal(get(template, "spec", "envVarsInjectionPolicy"), "Allowed");
		const kind = nameOf(template).replace("heph-sandbox-", "");
		const pod = get(template, "spec", "podTemplate", "spec");
		assert.equal(
			get(template, "spec", "podTemplate", "metadata", "labels", "hephaestus.build/sandbox-kind"),
			kind,
		);
		assert.equal(get(pod, "automountServiceAccountToken"), false);
		// The Job sandboxes' RuntimeClass (sandbox.runtime) is not the templates'; they have their own.
		assert.equal(get(pod, "runtimeClassName"), undefined);
		assert.equal(get(pod, "hostUsers"), false);
		assert.deepEqual(get(pod, "securityContext", "seccompProfile"), {
			type: "Localhost",
			localhostProfile: sandboxRuntime.seccompProfile,
		});
		assert.deepEqual(get(pod, "nodeSelector"), sandboxRuntime.nodeSelector);
		assert.equal(get(pod, "initContainers"), undefined);
		const [agent, ...others] = list(get(pod, "containers"));
		assert.equal(others.length, 0);
		assert.equal(get(agent, "securityContext", "allowPrivilegeEscalation"), false);
		assert.deepEqual(get(agent, "securityContext", "capabilities", "drop"), ["ALL"]);
		// The image is read-only: the sandbox writes only to /workspace and /tmp, each bounded by the kind's disk limit.
		assert.equal(get(agent, "securityContext", "readOnlyRootFilesystem"), true);
		const disk = get(agent, "resources", "limits", "ephemeral-storage");
		assert.deepEqual(get(pod, "volumes"), [
			{ name: "workspace", emptyDir: { sizeLimit: disk } },
			{ name: "tmp", emptyDir: { sizeLimit: disk } },
		]);
		assert.deepEqual(get(agent, "volumeMounts"), [
			{ name: "workspace", mountPath: "/workspace" },
			{ name: "tmp", mountPath: "/tmp" },
		]);
		// HOME is writable: it is on /tmp.
		const variable = (name: string) =>
			get(
				list(get(agent, "env")).find((entry) => get(entry, "name") === name),
				"value",
			);
		assert.equal(variable("HOME"), "/tmp/home");
		assert.equal(variable("XDG_CONFIG_HOME"), "/tmp/home/.config");
		const tier = kind === "review" ? "reviews" : "conversations";
		assert.equal(get(pod, "priorityClassName"), `hephaestus-heph-${tier}`);
		const pool = pools.find((candidate) => nameOf(candidate) === nameOf(template));
		assert.deepEqual(get(pool, "spec"), {
			replicas: 0,
			sandboxTemplateRef: { name: nameOf(template) },
		});
	}

	const gvisor = rendered([
		...layersFor("small"),
		sandboxLayer,
		{ sandbox: { templates: { runtimeClassName: "gvisor" } } },
	]);
	for (const template of templatesOf(gvisor)) {
		const pod = get(template, "spec", "podTemplate", "spec");
		assert.equal(get(pod, "runtimeClassName"), "gvisor");
		// gVisor does not support user namespaces (google/gvisor#13303).
		assert.equal(get(pod, "hostUsers"), undefined);
	}

	// The sandbox nodes hold the agent image before any claim needs it, on the templates' class.
	const holder = gvisor.find(
		(resource) => kindOf(resource) === "DaemonSet" && componentOf(resource) === "agent-image",
	);
	assert.equal(get(podSpec(holder), "runtimeClassName"), "gvisor");
	assert.deepEqual(get(podSpec(holder), "nodeSelector"), sandboxRuntime.nodeSelector);
	assert.deepEqual(get(podSpec(holder), "tolerations"), sandboxRuntime.tolerations);
	assert.equal(
		get(container(holder), "image"),
		get(list(get(templates[0], "spec", "podTemplate", "spec", "containers"))[0], "image"),
	);
	assert.equal(get(podSpec(holder), "automountServiceAccountToken"), false);
});

await test("each provider's byte override reaches the webhook role through the map", () => {
	const bounds = { github: "7GB", gitlab: "2GB", slack: "1GB", outline: "1GB" };
	const resources = rendered([
		...layersFor("small"),
		{
			webhook: { stream: { maxBytesByStream: bounds } },
			nats: { jetstream: { maxFileBytes: 17_179_869_184 } },
		},
	]);
	for (const [provider, bound] of Object.entries(bounds)) {
		const name = `HEPHAESTUS_WEBHOOK_STREAM_MAXBYTESBYSTREAM_${provider.toUpperCase()}`;
		assert.equal(envValue(workload(resources, "webhook"), name), bound, provider);
		for (const role of ["server"]) {
			assert.equal(env(workload(resources, role), name), undefined, `${role} ${provider}`);
		}
	}
	assert.equal(
		env(workload(resources, "webhook"), "HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES_GITHUB"),
		undefined,
	);
});

/** Kinds that have no namespace: a release without cluster-admin cannot create any of them. */
const clusterScoped = new Set([
	"PriorityClass",
	"ClusterRole",
	"ClusterRoleBinding",
	"Namespace",
	"CustomResourceDefinition",
	"RuntimeClass",
	"StorageClass",
	"ValidatingWebhookConfiguration",
	"MutatingWebhookConfiguration",
]);
const adminBundle = parseAllDocuments(
	readFileSync(
		new URL("../deploy/platform/hephaestus-cluster-admin.yaml", import.meta.url),
		"utf8",
	),
)
	.map((document) => document.toJS() as unknown)
	.filter(isRecord);

await test("the cluster profile renders only namespaced objects, and its PriorityClasses are the admin bundle's", () => {
	const resources = rendered(layersFor("cluster"));
	assert.deepEqual(
		resources.filter((resource) => clusterScoped.has(String(resource.kind))).map(nameOf),
		[],
	);
	const classNames = new Set(
		adminBundle.filter((resource) => resource.kind === "PriorityClass").map(nameOf),
	);
	assert.deepEqual([...classNames].toSorted(), [
		"hephaestus-conversations",
		"hephaestus-reviews",
		"hephaestus-roles",
	]);
	// Every Pod that the release runs, and every sandbox template, names one of the bundle's classes.
	const priorityNames = resources
		.map((resource) =>
			get(resource, "kind") === "SandboxTemplate"
				? get(resource, "spec", "podTemplate", "spec", "priorityClassName")
				: get(resource, "spec", "template", "spec", "priorityClassName"),
		)
		.filter((name) => typeof name === "string");
	assert.ok(priorityNames.length > 0);
	for (const name of priorityNames) {
		assert.ok(classNames.has(name), name);
	}
	// The default runtime with user namespaces: no RuntimeClass, no host users.
	for (const template of resources.filter((resource) => resource.kind === "SandboxTemplate")) {
		const pod = get(template, "spec", "podTemplate", "spec");
		assert.equal(get(pod, "runtimeClassName"), undefined, nameOf(template));
		assert.equal(get(pod, "hostUsers"), false, nameOf(template));
	}
});

/** The tier of a PriorityClass name: its last segment. */
const tierOf = (name: string) => name.slice(name.lastIndexOf("-") + 1);
/** PriorityClasses by tier, sorted. */
const byTier = (classList: readonly Resource[]) =>
	classList
		.map((resource) => priorityShape(resource))
		.toSorted((a, b) => a.tier.localeCompare(b.tier));
/** What a PriorityClass decides, by tier. */
const priorityShape = (resource: Resource) => ({
	tier: tierOf(nameOf(resource)),
	value: resource.value,
	globalDefault: resource.globalDefault,
	preemptionPolicy: resource.preemptionPolicy,
	description: resource.description,
});

await test("the admin bundle's PriorityClasses are the ones that the chart creates on a cluster of its own", () => {
	const created = rendered(layersFor("small")).filter(
		(resource) => resource.kind === "PriorityClass",
	);
	const classes = adminBundle.filter((resource) => resource.kind === "PriorityClass");
	assert.deepEqual(byTier(classes), byTier(created));
});

await test("the admin bundle is the generator's output, and grants the controller that the cluster profile runs", () => {
	assert.equal(
		readFileSync(
			new URL("../deploy/platform/hephaestus-cluster-admin.yaml", import.meta.url),
			"utf8",
		),
		generatedBundle(),
		"run node scripts/cluster-admin-bundle.ts",
	);
	// The release and namespace that the admin guide installs.
	const resources = rendered(layersFor("cluster"), {
		release: "hephaestus",
		namespace: "hephaestus",
	});
	const account = resources.find(
		(resource) =>
			resource.kind === "ServiceAccount" && nameOf(resource).endsWith("-agent-sandbox-controller"),
	);
	assert.ok(account);
	assert.equal(nameOf(account), CONTROLLER.name);
	const binding = adminBundle.find((resource) => resource.kind === "ClusterRoleBinding");
	assert.deepEqual(get(binding, "subjects"), [
		{ kind: "ServiceAccount", name: CONTROLLER.name, namespace: CONTROLLER.namespace },
	]);
	// The controller runs the image of the pinned platform manifest, the one that scan-platform-images checks.
	const pinned = parseAllDocuments(
		readFileSync(new URL("../deploy/platform/k3s-agent-sandbox.yaml", import.meta.url), "utf8"),
	)
		.map((document) => document.toJS() as unknown)
		.find((resource) => isRecord(resource) && resource.kind === "Deployment");
	const controller = resources.find(
		(resource) => resource.kind === "Deployment" && nameOf(resource) === CONTROLLER.name,
	);
	assert.equal(
		get(controller, "spec", "template", "spec", "containers", 0, "image"),
		get(pinned, "spec", "template", "spec", "containers", 0, "image"),
	);
	// Its writes stay in Roles: the sandbox namespace for sandboxes, the release namespace for its lease.
	const roles = resources.filter(
		(resource) => resource.kind === "Role" && nameOf(resource).startsWith(CONTROLLER.name),
	);
	assert.deepEqual(
		roles
			.map((role) => {
				const namespace = get(role, "metadata", "namespace");
				return `${nameOf(role)} ${typeof namespace === "string" ? namespace : "release"}`;
			})
			.toSorted(),
		[`${CONTROLLER.name} ${sandboxNamespace}`, `${CONTROLLER.name}-election release`],
	);
	// Elsewhere the cluster runs the controller: the small profile renders none of it.
	assert.equal(
		rendered(layersFor("small")).some((resource) =>
			nameOf(resource).includes("agent-sandbox-controller"),
		),
		false,
	);
});

await test("the controller's Roles grant exactly what the pinned controller's upstream ClusterRoles grant", () => {
	const upstream = parseAllDocuments(
		readFileSync(new URL("../deploy/platform/k3s-agent-sandbox.yaml", import.meta.url), "utf8"),
	)
		.map((document) => {
			const value: unknown = document.toJS();
			return value;
		})
		.filter((resource) => isRecord(resource) && resource.kind === "ClusterRole")
		.flatMap((role) => ruleGrants(role));
	const ours = rendered(layersFor("cluster"))
		.filter(
			(resource) =>
				resource.kind === "Role" && nameOf(resource).startsWith("heph-agent-sandbox-controller"),
		)
		.flatMap((role) => ruleGrants(role));
	assert.deepEqual([...new Set(ours)].toSorted(), [...new Set(upstream)].toSorted());
});

await test("the parallel pressure drill waits for the chart's priorities and excludes role and sandbox Pods from its node", () => {
	const resources = rendered([
		...layersFor("dev"),
		{
			sandbox: {
				enabled: true,
				namespace: sandboxNamespace,
				templates: { runtimeClassName: "gvisor" },
			},
			networkPolicy: { kubernetesApi },
		},
	]);
	assert.ok(
		resources.some(
			(resource) => kindOf(resource) === "Deployment" && nameOf(resource) === "heph-server",
		),
	);
	assert.ok(
		!resources.some(
			(resource) => kindOf(resource) === "Deployment" && nameOf(resource) === "heph-worker",
		),
	);
	const priorities = resources.filter((resource) => kindOf(resource) === "PriorityClass");
	assert.deepEqual(
		priorities.map((resource) => `priorityclass/${nameOf(resource)}`).toSorted(),
		priorityClassResources.toSorted(),
	);
	const templates = resources.filter((resource) => kindOf(resource) === "SandboxTemplate");
	assert.ok(templates.length > 0);
	for (const template of templates) {
		assert.equal(get(template, "spec", "podTemplate", "spec", "runtimeClassName"), "gvisor");
	}
	const pods = [
		...workloads(resources).map(podSpec),
		...templates.map((resource) => get(resource, "spec", "podTemplate", "spec")),
	];
	assert.ok(pods.length > 0);
	for (const pod of pods) {
		const tolerations = list(get(pod, "tolerations") ?? []);
		assert.equal(
			tolerations.some(
				(toleration) =>
					(get(toleration, "effect") === undefined ||
						get(toleration, "effect") === "" ||
						get(toleration, "effect") === "NoSchedule") &&
					(get(toleration, "key") === pressureNodeTaint ||
						((get(toleration, "key") === undefined || get(toleration, "key") === "") &&
							get(toleration, "operator") === "Exists")),
			),
			false,
			"the pressure node must not admit production role or sandbox Pods",
		);
	}
});
