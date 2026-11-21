/** Helpers shared by `scripts/kubernetes-chart-render*.test.ts`. */
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseAllDocuments, stringify } from "yaml";
import { parseInventory } from "./commit-image-lock.ts";
import { chartValues } from "./kubernetes-chart.ts";
import { asRecord, isRecord, readJsonFileSync } from "./lib/json.ts";
import { CAPTURE_LIMIT_BYTES } from "./lib/process.ts";
import { parseReleaseImageLock } from "./release-image-lock.ts";

export const chart = fileURLToPath(new URL("../deploy/chart/hephaestus/", import.meta.url));
export const profile = (name: string) => path.join(chart, "profiles", `${name}.yaml`);

/** A stalled helm process fails the case instead of holding the run. */
export const VERSION_TIMEOUT_MS = 10_000;
export const RENDER_TIMEOUT_MS = 30_000;

export const helm = spawnSync("helm", ["version", "--short"], {
	encoding: "utf8",
	maxBuffer: CAPTURE_LIMIT_BYTES,
	timeout: VERSION_TIMEOUT_MS,
});
if (helm.error !== undefined || helm.status !== 0) {
	throw new Error(
		`The chart render tests need the helm executable on PATH, and it could not run (${helm.error?.message ?? `exit ${String(helm.status)}`}). Install the Helm version that .github/actions/setup-helm pins, then run vp run test:kubernetes:chart again.`,
	);
}

export const inventory = parseInventory(
	readJsonFileSync(fileURLToPath(new URL("../security/release-images.json", import.meta.url))),
);
export const digest = (character: string) => `sha256:${character.repeat(64)}`;
export const platforms = { "linux/amd64": digest("a"), "linux/arm64": digest("b") };
export const prepared = chartValues(
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

export const secretName = "hephaestus-secrets";
export const gatewayPods = { "gateway.networking.k8s.io/gateway-name": "edge" };
export const parentRefs = [{ name: "edge", namespace: "gateway-system", sectionName: "https" }];
export const operator = {
	host: "hephaestus.example.com",
	trustedProxies: String.raw`10\.42\.\d{1,3}\.\d{1,3}`,
	secret: { name: secretName },
	route: { parentRefs },
	networkPolicy: { gateway: { namespace: "gateway-system", podLabels: gatewayPods } },
	postgresql: { tls: { existingSecret: "hephaestus-postgresql-tls" } },
};

export const external = {
	postgresql: { bundled: false, external: { host: "db.internal", cidr: "10.0.12.0/24" } },
};
/** Synthetic sandbox nodes: every optional setting named. */
export const sandboxRuntime = {
	nodeSelector: { "hephaestus.build/pool": "sandboxes" },
	tolerations: [{ key: "hephaestus.build/sandboxes", operator: "Exists", effect: "NoSchedule" }],
};
/** The namespace that the operator created for sandboxes. */
export const sandboxNamespace = "hephaestus-sandboxes";
/** The API server as the network plugin sees it after the Service address is translated. */
export const kubernetesApi = { cidr: "192.168.5.15/32", port: 6443 };
export const sandboxLayer = {
	sandbox: { enabled: true, runtime: sandboxRuntime, namespace: sandboxNamespace },
	networkPolicy: { kubernetesApi },
};
/** What the operator states for the single-node profile's sandboxes (docs/admin/kubernetes.mdx § Install). */
export const reviews = {
	sandbox: { namespace: sandboxNamespace },
	networkPolicy: { kubernetesApi },
};

export type Resource = Record<string, unknown>;
/** A values file path, or values to write to a file. */
export type Layer = string | object;
export interface Rendered {
	ok: boolean;
	stderr: string;
	resources: Resource[];
}

/** The values files of `layers`: a string layer is a path, and an object is written to `directory`. */
export function valuesFiles(directory: string, layers: readonly Layer[]): string[] {
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
export function render(
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
			{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES, timeout: RENDER_TIMEOUT_MS },
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

export function rendered(layers: readonly Layer[], options = {}): Resource[] {
	const result = render(layers, options);
	assert.ok(result.ok, result.stderr);
	return result.resources;
}

export const get = (value: unknown, ...keys: (string | number)[]): unknown => {
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
export const list = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
export const kindOf = (resource: Resource) => get(resource, "kind");
export const nameOf = (resource: Resource) => String(get(resource, "metadata", "name"));
export const componentOf = (resource: Resource) =>
	get(resource, "metadata", "labels", "app.kubernetes.io/component");

export const workloads = (resources: Resource[]) =>
	resources.filter((resource) => ["Deployment", "StatefulSet"].includes(String(kindOf(resource))));
export const workload = (resources: Resource[], component: string) => {
	const [found, ...others] = workloads(resources).filter(
		(resource) => componentOf(resource) === component,
	);
	assert.ok(found !== undefined && others.length === 0, `one ${component} workload`);
	return found;
};
export const podSpec = (resource: unknown) => get(resource, "spec", "template", "spec");
export const containers = (resource: unknown) => [
	...list(get(podSpec(resource), "initContainers")),
	...list(get(podSpec(resource), "containers")),
];
export const container = (resource: unknown) => {
	const found = list(get(podSpec(resource), "containers"));
	assert.equal(found.length, 1);
	return found[0];
};
export const env = (resource: unknown, name: string) =>
	list(get(container(resource), "env")).find((entry) => get(entry, "name") === name);
export const envValue = (resource: unknown, name: string) => get(env(resource, name), "value");
/** Code-point order, the same on every platform and locale. */
export const byCodePoint = (left: string, right: string) =>
	left < right ? -1 : Number(left > right);
/** A non-empty selector whose every label the given label set carries. */
export const selects = (selector: unknown, labels: unknown) =>
	isRecord(selector) &&
	isRecord(labels) &&
	Object.keys(selector).length > 0 &&
	Object.entries(selector).every(([key, value]) => labels[key] === value);
export const named = (resources: Resource[], kind: string, name: unknown) =>
	resources.some((resource) => kindOf(resource) === kind && nameOf(resource) === name);

/** The name and every label value fit Kubernetes' limits, and the full release stays a label. */
export function assertBounded(resource: Resource, release: string, label: string): void {
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
export function assertWorkloadReferences(
	resources: Resource[],
	resource: Resource,
	label: string,
): void {
	assert.ok(
		selects(
			get(resource, "spec", "selector", "matchLabels"),
			get(resource, "spec", "template", "metadata", "labels"),
		),
		`${label} selector`,
	);
	const spec = podSpec(resource);
	// PostgreSQL run under the namespace default account, without its token.
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
export function assertReferencesResolve(
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

/** The three profiles, and the small one with an operator's external PostgreSQL. */
export const profiles = ["dev", "small", "cluster", "external"] as const;
export const layersFor = (name: (typeof profiles)[number]) => {
	switch (name) {
		case "external": {
			return [profile("small"), prepared, operator, reviews, external];
		}
		case "small":
		case "cluster": {
			return [profile(name), prepared, operator, reviews];
		}
		case "dev": {
			return [profile(name), prepared, operator];
		}
	}
};
export const bundled = (name: (typeof profiles)[number]) => name !== "external";
export const runsReviews = (name: (typeof profiles)[number]) =>
	name === "small" || name === "cluster" || name === "external";
/** A profile as an operator installs it with reviews: CI turns on the sandboxes of the dev profile this way. */
export const withSandboxes = (name: (typeof profiles)[number]) =>
	runsReviews(name) ? layersFor(name) : [...layersFor(name), sandboxLayer];
/** What the server writes into the sandbox namespace. */
export const sandboxOwned = (resource: Resource) => componentOf(resource) === "sandbox";
/** The managed-cluster controller has its own Kubernetes API authority. */
export const controllerOwned = (resource: Resource) =>
	componentOf(resource) === "agent-sandbox-controller";
export const imageOf = {
	server: "server",
	postgresql: "postgres",
} as const;

/** The sandbox quota of one PriorityClass tier. */
export const tierQuota = (resources: Resource[], tier: "reviews" | "conversations") => {
	const quota = resources.find(
		(resource) =>
			kindOf(resource) === "ResourceQuota" &&
			String(get(resource, "metadata", "name")).endsWith(`sandbox-quota-${tier}`),
	);
	assert.ok(quota, `the ${tier} quota`);
	return quota;
};

/** The sandbox namespace's quota without a scope. */
export const sandboxQuota = (resources: Resource[]) => {
	const quota = resources.find(
		(resource) =>
			kindOf(resource) === "ResourceQuota" &&
			String(get(resource, "metadata", "name")).endsWith("sandbox-quota"),
	);
	assert.ok(quota, "the unscoped sandbox quota");
	return quota;
};

export const alertRules = readFileSync(path.join(chart, "files", "hephaestus-alerts.yml"), "utf8");
export const alertRulesOn = {
	observability: {
		alertRules: { configMap: true, prometheusRule: true, labels: { release: "monitoring" } },
	},
};
/** The bundled PostgreSQL's backups, to an S3 repository. */
export const backupsOn = {
	postgresql: {
		backup: {
			clientTlsSecret: "hephaestus-postgresql-backup-tls",
			enabled: true,
			s3: { bucket: "hephaestus-backups", endpoint: "s3.example.com", region: "eu-central-1" },
		},
	},
};
/** The rule groups of a rendered rules file. */
export const ruleGroups = (rules: unknown) =>
	list(get(parseAllDocuments(String(rules))[0]?.toJS(), "groups"));
/** The groups of files/hephaestus-alerts.yml, without the backup group, which renders only with backups. */
export const ruleGroupsWithoutBackups = () =>
	ruleGroups(alertRules).filter((group) => get(group, "name") !== "hephaestus-backups");
export const ruleResources = (resources: Resource[]) =>
	resources.filter((resource) => componentOf(resource) === "alert-rules");

export const stackOn = {
	observability: { stack: { enabled: true, alertmanager: { configSecret: "alertmanager" } } },
};
export const stackComponents = ["victoria-metrics", "vmalert", "alertmanager"] as const;
export const inStack = (resource: Resource) =>
	stackComponents.some((component) => component === componentOf(resource));

export const serverOf = (layers: readonly Layer[]) => workload(rendered(layers), "server");

/** Each grant of a Role, as `apiGroup/resource[names]:verb`, in code-point order. */
export const grants = (role: unknown) =>
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

export const caDirectory = "/etc/hephaestus/postgresql-ca";
export const tlsSecret = operator.postgresql.tls.existingSecret;
export const verifyFull = `sslmode=verify-full&sslrootcert=${caDirectory}/ca.crt`;

/** The role's only Secret volume holds ca.crt alone, mounted read-only where its URL names it. */
export function assertCaOnly(role: Resource, label: string): void {
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

export const mountsS3Ca = (spec: unknown) =>
	list(get(spec, "volumes")).some((volume) =>
		list(get(volume, "secret", "items")).some((item) => get(item, "key") === "s3-ca.crt"),
	);

export const readValues = (file: string): unknown =>
	parseAllDocuments(readFileSync(file, "utf8"))[0]?.toJS();
/** One review Pod's memory request in MiB, from the template that also sizes its quota. */
export const reviewPodMi =
	amount(
		get(
			readValues(path.join(chart, "values.yaml")),
			"sandbox",
			"templates",
			"review",
			"resources",
			"requests",
			"memory",
		),
	) /
	2 ** 20;
/** The memory that holds this many review Pods. */
export const memoryFor = (reviewCount: number) => `${reviewCount * reviewPodMi}Mi`;
/** Actual requests in the rendered SandboxTemplate, independent of the quota's calculations. */
export const sandboxRequests = (resources: Resource[], kind: string) => {
	const template = resources.find(
		(resource) =>
			kindOf(resource) === "SandboxTemplate" && nameOf(resource) === `heph-sandbox-${kind}`,
	);
	assert.ok(template, `the ${kind} template exists`);
	const requests = get(
		template,
		"spec",
		"podTemplate",
		"spec",
		"containers",
		0,
		"resources",
		"requests",
	);
	return {
		pods: 1,
		gib: amount(get(requests, "memory")) / 2 ** 30,
		cpu: amount(get(requests, "cpu")),
		disk: amount(get(requests, "ephemeral-storage")),
	};
};
/** sandbox.reviewMemory as these layers set it, over the chart's default, in MiB. */
export const reviewMemoryMi = (layers: readonly Layer[]) => {
	let value = get(readValues(path.join(chart, "values.yaml")), "sandbox", "reviewMemory");
	for (const layer of layers) {
		const values = typeof layer === "string" ? readValues(layer) : layer;
		value = get(values, "sandbox", "reviewMemory") ?? value;
	}
	return amount(value) / 2 ** 20;
};
/** The reviews that the server runs, and the Pods that the reviews quota holds. */
export const reviewWidth = (layers: Layer[]) => {
	const resources = rendered(layers);
	return {
		sandboxes: envValue(workload(resources, "server"), "SANDBOX_MAX_CONCURRENT"),
		pods: get(tierQuota(resources, "reviews"), "spec", "hard", "pods"),
	};
};

/** The buildpack memory calculator's inputs on the server container. */
export const memoryCalculator = (layers: Layer[]) => {
	const server = workload(rendered(layers), "server");
	return {
		headRoom: envValue(server, "BPL_JVM_HEAD_ROOM"),
		threads: envValue(server, "BPL_JVM_THREAD_COUNT"),
	};
};

/** The server's SMTP authentication and TLS settings for an email configuration. */
export const mail = (email: object) => {
	const server = workload(rendered([...layersFor("small"), { config: { email } }]), "server");
	return [
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH",
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE",
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE",
		"SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED",
	].map((name) => envValue(server, name));
};

/** A Kubernetes quantity in bytes or in cores. */
export function amount(value: unknown): number {
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
export function podRequests(spec: unknown) {
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
/** The Pods that a rollout adds beside the old ones: the old draining server set, or maxSurge for other workloads. */
export function surge(deployment: Resource): number {
	const replicas = Number(get(deployment, "spec", "replicas") ?? 1);
	// Every old server can remain in its long drain after all replacements become ready.
	if (componentOf(deployment) === "server") {
		return replicas;
	}
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

export const gib = (bytes: number) => (bytes / 2 ** 30).toFixed(1);

/**
 * The room that the single-node profile gives the release: its node's allocatable, less 5 % headroom and the requests
 * of the workloads outside the release (docs/admin/sizing.mdx § Node reserve).
 */
export const nodeRoom = (override?: unknown) => {
	const declared = get(readValues(profile("small")), "sandbox", "budget");
	const budget = {
		cpu: get(override, "cpu") ?? get(declared, "cpu"),
		memory: get(override, "memory") ?? get(declared, "memory"),
		outsideRelease: get(override, "outsideRelease") ?? get(declared, "outsideRelease"),
	};
	const room = (resource: "cpu" | "memory", unit: number) => {
		const allocatable = Math.floor(amount(get(budget, resource)) / unit);
		const outside = Math.ceil(amount(get(budget, "outsideRelease", resource) ?? "0") / unit);
		return (allocatable - Math.ceil((allocatable * 5) / 100) - outside) * unit;
	};
	return { cpu: room("cpu", 0.001), memory: room("memory", 2 ** 20) };
};

/**
 * A scheduled backup. With sandboxes, the upgrade budget leaves it out: it fits in the reserve's headroom, or it waits,
 * because it never preempts (docs/admin/sizing.mdx § Node reserve). The stanza Job of each upgrade counts.
 */
export const scheduledBackup = (resource: Resource) =>
	componentOf(resource) === "postgresql-backup" && kindOf(resource) === "CronJob";

/** Independently total the rendered requests at the two supported operating phases. */
export const fitTotals = (
	resources: Resource[],
	overhead: { cpuMillis: number; memoryMi: number },
) => {
	const steady = { cpu: 0, memory: 0 };
	const upgrade = { cpu: 0, memory: 0 };
	const add = (budget: typeof steady, spec: unknown, count: number) => {
		const requests = podRequests(spec);
		budget.cpu += count * requests.cpu;
		budget.memory += count * requests.memory;
		if (get(spec, "runtimeClassName") !== undefined) {
			budget.cpu += (count * overhead.cpuMillis) / 1000;
			budget.memory += count * overhead.memoryMi * 2 ** 20;
		}
	};
	for (const resource of workloads(resources)) {
		const replicas = Number(get(resource, "spec", "replicas") ?? 1);
		add(steady, podSpec(resource), replicas);
		add(
			upgrade,
			podSpec(resource),
			replicas + (kindOf(resource) === "Deployment" && replicas > 0 ? surge(resource) : 0),
		);
	}
	for (const resource of resources.filter((candidate) => kindOf(candidate) === "DaemonSet")) {
		add(steady, podSpec(resource), 1);
		add(upgrade, podSpec(resource), 1);
	}
	const sandboxes = resources.some((resource) => kindOf(resource) === "SandboxTemplate");
	for (const resource of resources.filter((candidate) => kindOf(candidate) === "Job")) {
		add(upgrade, podSpec(resource), 1);
	}
	const backup = resources.find(
		(resource) => kindOf(resource) === "CronJob" && get(resource, "spec", "suspend") !== true,
	);
	if (backup) {
		const spec = get(backup, "spec", "jobTemplate", "spec", "template", "spec");
		add(steady, spec, 1);
		if (!sandboxes) {
			add(upgrade, spec, 1);
		}
	}
	for (const tier of ["reviews", "conversations"] as const) {
		const hard = get(tierQuota(resources, tier), "spec", "hard");
		const requests = {
			cpu: amount(get(hard, "requests.cpu")),
			memory: amount(get(hard, "requests.memory")),
		};
		steady.cpu += requests.cpu;
		steady.memory += requests.memory;
		if (tier === "conversations") {
			upgrade.cpu += requests.cpu;
			upgrade.memory += requests.memory;
		}
	}
	return { steady, upgrade };
};

/** The rendered template requests that size the quota and the node-fit checks. */
export const sandboxPods = (() => {
	const resources = rendered(layersFor("small"));
	return {
		review: sandboxRequests(resources, "review"),
		conversation: sandboxRequests(resources, "conversation"),
	};
})();
export const sandboxGib = {
	review: sandboxPods.review.gib,
	conversation: sandboxPods.conversation.gib,
};

/** The requests that each tier's quota holds, rendered from these layers. */
export const tierHard = (layers: Layer[]) => {
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

export const strategy = (name: (typeof profiles)[number], component: string) =>
	get(workload(rendered(layersFor(name)), component), "spec", "strategy");

export interface Budget {
	cpu: number;
	memory: number;
}
/** The requests of Pods, each with its count. */
export const total = (pods: [unknown, number][]): Budget => {
	const budget = { cpu: 0, memory: 0 };
	for (const [spec, count] of pods) {
		const requests = podRequests(spec);
		budget.cpu += requests.cpu * count;
		budget.memory += requests.memory * count;
	}
	return budget;
};
/** Sandboxes request their memory limit and a tenth of their CPU limit of one core. */
export const sum = (...parts: Budget[]): Budget => {
	const budget = { cpu: 0, memory: 0 };
	for (const part of parts) {
		budget.cpu += part.cpu;
		budget.memory += part.memory;
	}
	return budget;
};

export const templatePriority = (resources: Resource[], kind: string) =>
	get(
		resources.find(
			(resource) =>
				kindOf(resource) === "SandboxTemplate" && nameOf(resource) === `heph-sandbox-${kind}`,
		),
		"spec",
		"podTemplate",
		"spec",
		"priorityClassName",
	);

export const podTemplate = (layers: readonly Layer[], component: string) =>
	get(workload(rendered(layers), component), "spec", "template");

export const databaseSetupDirectory = "/etc/hephaestus/database";
export const databaseSetupFiles = [
	"database-quiet.sql",
	"database-roles.sql",
	"database-monitor.sql",
	"database-roles.sh",
	"database-monitor.sh",
	"scram-sha-256.pl",
];

/** The pod's container runs the chart's files from the ConfigMap, which holds each of them unchanged. */
export function assertDatabaseSetup(resources: Resource[], pod: unknown, entry: unknown): void {
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
export const podTemplates = (resources: Resource[]) =>
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

/** The Secret keys that one container reads. */
export const secretKeysOf = (entry: unknown): string[] =>
	list(get(entry, "env")).flatMap((variable) => {
		const key = get(variable, "valueFrom", "secretKeyRef", "key");
		return typeof key === "string" ? [key] : [];
	});

export const databaseUserOf = (entry: unknown) =>
	get(
		list(get(entry, "env")).find((variable) => get(variable, "name") === "DATABASE_USERNAME"),
		"value",
	);

export const migrationJobOf = (resources: Resource[]) => {
	const job = resources.find(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "migration",
	);
	assert.ok(job, "one migration Job");
	return job;
};

export const maxConnections = (layers: readonly Layer[]) => {
	const args = list(get(container(workload(rendered(layers), "postgresql")), "args"));
	return args.findLast((arg) => typeof arg === "string" && arg.startsWith("max_connections="));
};

export function javaCapacityConstant(file: string, name: string): number {
	const source = readFileSync(
		new URL(
			`../server/application/src/main/java/de/tum/cit/aet/hephaestus/${file}`,
			import.meta.url,
		),
		"utf8",
	);
	const match = new RegExp(`${name} = (?<count>\\d+);`, "u").exec(source)?.groups?.count;
	assert.ok(match !== undefined, name);
	return Number(match);
}

/**
 * The NOTES.txt that an install prints. `helm template` leaves NOTES.txt out, so this runs a
 * client-side dry-run install, which needs no cluster.
 */
export function installNotes(layers: readonly Layer[]): string {
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
			{ encoding: "utf8", maxBuffer: CAPTURE_LIMIT_BYTES, timeout: RENDER_TIMEOUT_MS },
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

export const metricsOn = { postgresql: { metrics: { enabled: true } } };
export const monitorJobs = (resources: Resource[]) =>
	resources.filter(
		(resource) => kindOf(resource) === "Job" && componentOf(resource) === "postgresql-monitor",
	);
export const exporterParts = (resources: Resource[]) =>
	resources.filter((resource) =>
		[componentOf(resource), nameOf(resource)].some((value) =>
			String(value).includes("postgresql-exporter"),
		),
	);
/** The components that the bundled PostgreSQL admits on its port. */
export const postgresqlPeers = (resources: Resource[]) =>
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

export const templatesOf = (resources: Resource[]) =>
	resources.filter((resource) => kindOf(resource) === "SandboxTemplate");

export const byPath = (left: { path: unknown }, right: { path: unknown }) =>
	byCodePoint(String(left.path), String(right.path));

/** Kinds that have no namespace: a release without cluster-admin cannot create any of them. */
export const clusterScoped = new Set([
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
export const adminBundle = parseAllDocuments(
	readFileSync(
		new URL("../deploy/platform/hephaestus-cluster-admin.yaml", import.meta.url),
		"utf8",
	),
)
	.map((document) => document.toJS() as unknown)
	.filter(isRecord);

/** The tier of a PriorityClass name: its last segment. */
export const tierOf = (name: string) => name.slice(name.lastIndexOf("-") + 1);
/** PriorityClasses by tier, sorted. */
export const byTier = (classList: readonly Resource[]) =>
	classList
		.map((resource) => priorityShape(resource))
		.toSorted((a, b) => a.tier.localeCompare(b.tier));
/** What a PriorityClass decides, by tier. */
export const priorityShape = (resource: Resource) => ({
	tier: tierOf(nameOf(resource)),
	value: resource.value,
	globalDefault: resource.globalDefault,
	preemptionPolicy: resource.preemptionPolicy,
	description: resource.description,
});
