import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parseAllDocuments } from "yaml";

import {
	restoreFailures,
	soleManager,
	apiEndpoint,
	applicationSecret,
	chartImagesFrom,
	clusterState,
	DEV_CLUSTER,
	DEV_HOST,
	DEV_MEMORY,
	devProblems,
	devClusterArguments,
	devSignIn,
	devSummary,
	drillLayer,
	forwardedPort,
	heldChecks,
	trustSteps,
	mergeSecret,
	NAMESPACE,
	operatorValues,
	RELEASE,
	restartedPods,
	SANDBOXES,
	signInPod,
	statefulPods,
	outcomeChecks,
	turnOutcome,
	turnState,
	useKubectl,
	verify,
} from "./kubernetes-cluster.ts";
import { asArray, asRecord, isRecord } from "./lib/json.ts";

const chart = fileURLToPath(new URL("../deploy/chart/hephaestus/", import.meta.url));
const digest = (character: string) => `sha256:${character.repeat(64)}`;
const resolved = ["webapp", "application-server", "agent-pi", "postgres"].map((image, index) => ({
	image,
	repository: `ghcr.io/hephaestus-build/${image}`,
	indexDigest: digest(String(index)),
}));
const upstream = [
	{
		name: "victoria-metrics",
		repository: "docker.io/victoriametrics/victoria-metrics",
		digest: digest("f"),
	},
	{ name: "vmalert", repository: "docker.io/victoriametrics/vmalert", digest: digest("9") },
	{ name: "alertmanager", repository: "docker.io/prom/alertmanager", digest: digest("8") },
	{
		name: "postgres-exporter",
		repository: "docker.io/prometheuscommunity/postgres-exporter",
		digest: digest("7"),
	},
	{ name: "alpine", repository: "docker.io/library/alpine", digest: digest("a") },
];
const images = chartImagesFrom(resolved, upstream);

await test("the chart's images come from the resolved first-party images and the upstream pins", () => {
	assert.deepEqual(images, {
		"agent-pi": `ghcr.io/hephaestus-build/agent-pi@${digest("2")}`,
		alertmanager: `docker.io/prom/alertmanager@${digest("8")}`,
		"application-server": `ghcr.io/hephaestus-build/application-server@${digest("1")}`,
		postgres: `ghcr.io/hephaestus-build/postgres@${digest("3")}`,
		"postgres-exporter": `docker.io/prometheuscommunity/postgres-exporter@${digest("7")}`,
		"victoria-metrics": `docker.io/victoriametrics/victoria-metrics@${digest("f")}`,
		vmalert: `docker.io/victoriametrics/vmalert@${digest("9")}`,
		webapp: `ghcr.io/hephaestus-build/webapp@${digest("0")}`,
	});
	assert.throws(() => chartImagesFrom(resolved.slice(1), upstream), /no image for webapp/u);
});

await test("the API endpoint is the address and port of the kubernetes Service's endpoint", () => {
	const slices = {
		items: [{ endpoints: [{ addresses: ["172.18.0.3"] }], ports: [{ name: "https", port: 6443 }] }],
	};
	assert.deepEqual(apiEndpoint(slices), { address: "172.18.0.3", port: 6443 });
	assert.throws(() => apiEndpoint({ items: [] }), /no EndpointSlice/u);
});

await test("the generated Secret holds every key that the chart reads", () => {
	const first = applicationSecret();
	for (const key of [
		"DATABASE_ADMIN_USERNAME",
		"DATABASE_ADMIN_PASSWORD",
		"DATABASE_OWNER_PASSWORD",
		"DATABASE_APP_PASSWORD",
		"HEPHAESTUS_SECURITY_ENCRYPTION_KEY",
		"HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY",
		"HEPHAESTUS_AUTH_STATE_COOKIE_KEY",
		"HEPHAESTUS_CONVERSATION_LOOP_SECRET",
		"WEBHOOK_SECRET",
		"WEBHOOK_ROUTING_SECRET",
	]) {
		assert.ok((first[key] ?? "").length > 0, key);
	}
});

await test("a second run keeps every value of the Secret and adds only the keys it lacks", () => {
	const first = mergeSecret({}, applicationSecret());
	assert.deepEqual(first.added.toSorted(), Object.keys(applicationSecret()).toSorted());
	const second = mergeSecret(first.data, applicationSecret());
	assert.deepEqual(second, { data: first.data, added: [] });
	// A cluster from before a key existed, with a value that the generator does not make.
	const { WEBHOOK_ROUTING_SECRET: _missing, ...older } = first.data;
	const third = mergeSecret({ ...older, GITHUB_OAUTH_CLIENT_SECRET: "kept" }, applicationSecret());
	assert.deepEqual(third.added, ["WEBHOOK_ROUTING_SECRET"]);
	assert.equal(third.data.DATABASE_APP_PASSWORD, first.data.DATABASE_APP_PASSWORD);
	assert.equal(third.data.GITHUB_OAUTH_CLIENT_SECRET, "kept");
	assert.ok((third.data.WEBHOOK_ROUTING_SECRET ?? "").length > 0);
});

/** `helm template` of the dev profile with the values that `prepare` writes. */
function render(values: object) {
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-cluster-"));
	try {
		const file = path.join(directory, "values.yaml");
		writeFileSync(file, JSON.stringify(values));
		const result = spawnSync(
			"helm",
			[
				"template",
				RELEASE,
				chart,
				"--namespace",
				NAMESPACE,
				"--values",
				path.join(chart, "profiles", "dev.yaml"),
				"--values",
				file,
			],
			{ encoding: "utf8", timeout: 30_000 },
		);
		assert.equal(result.status, 0, result.stderr);
		return parseAllDocuments(result.stdout)
			.map((document) => document.toJS() as unknown)
			.filter(isRecord);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

/** `helm template` of the dev profile with the prepared values, then each of `layers`. */
function renderLayers(values: object, layers: readonly string[]) {
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-cluster-"));
	try {
		const file = path.join(directory, "values.yaml");
		writeFileSync(file, JSON.stringify(values));
		const result = spawnSync(
			"helm",
			[
				"template",
				RELEASE,
				chart,
				"--namespace",
				NAMESPACE,
				"--values",
				path.join(chart, "profiles", "dev.yaml"),
				"--values",
				file,
				...layers,
			],
			{ encoding: "utf8", timeout: 30_000 },
		);
		return {
			status: result.status,
			stderr: result.stderr,
			documents: parseAllDocuments(result.stdout)
				.map((document) => document.toJS() as unknown)
				.filter(isRecord),
		};
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

function serverEnv(documents: readonly Record<string, unknown>[], name: string): string {
	const server = documents.find(
		(resource) =>
			resource.kind === "Deployment" &&
			isRecord(resource.metadata) &&
			resource.metadata.name === `${RELEASE}-server`,
	);
	const text = JSON.stringify(server);
	const match = new RegExp(`"name":"${name}","value":"(?<value>[^"]*)"`, "u").exec(text);
	assert.ok(match?.groups?.value !== undefined, name);
	return match.groups.value;
}

function postgresSetting(documents: readonly Record<string, unknown>[], name: string): number {
	const text = JSON.stringify(
		documents.find(
			(resource) =>
				resource.kind === "StatefulSet" &&
				isRecord(resource.metadata) &&
				resource.metadata.name === `${RELEASE}-postgresql`,
		),
	);
	const values = [...text.matchAll(new RegExp(`"${name}=(\\d+)"`, "gu"))].map((match) =>
		Number(match[1]),
	);
	assert.ok(values.length > 0, name);
	return values.at(-1) ?? Number.NaN;
}

await test("a backup drill install keeps the installed release's capacity and the pinned max_connections", async () => {
	const prepared = operatorValues({
		images,
		commit: "c".repeat(40),
		api: { address: "172.18.0.3", port: 6443 },
	});
	// What CI adds on the command line of its install: the gVisor runtime class and its pod overhead.
	const gvisor = {
		sandbox: {
			templates: { runtimeClassName: "gvisor", podOverhead: { memoryMi: 48, cpuMillis: 10 } },
		},
	};
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-drill-"));
	try {
		const gvisorFile = path.join(directory, "gvisor.yaml");
		writeFileSync(gvisorFile, JSON.stringify(gvisor));
		const installed = renderLayers(prepared, ["--values", gvisorFile]);
		const plain = renderLayers(prepared, []);
		assert.equal(installed.status, 0, installed.stderr);
		assert.equal(plain.status, 0, plain.stderr);
		const capacity = (documents: readonly Record<string, unknown>[]) => ({
			reviews: serverEnv(documents, "SANDBOX_MAX_CONCURRENT"),
			pool: serverEnv(documents, "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE"),
			maxConnections: postgresSetting(documents, "max_connections"),
		});
		// The two installs differ, so a drill install that forgets the first one can pin a limit that the second exceeds.
		const source = capacity(installed.documents);
		assert.notDeepEqual(capacity(plain.documents), source);
		assert.ok(source.maxConnections < capacity(plain.documents).maxConnections);

		const pinned = (restore: object) => ({
			postgresql: {
				parameters: { max_connections: source.maxConnections },
				backup: { restore },
			},
		});
		const pinnedFile = path.join(directory, "pinned.yaml");
		writeFileSync(pinnedFile, JSON.stringify(pinned({ enabled: false, time: "" })));
		const forgotten = renderLayers(prepared, ["--values", pinnedFile]);
		assert.notEqual(forgotten.status, 0);
		assert.match(forgotten.stderr, /PostgreSQL max_connections needs at least/u);

		// The release's own values, as `helm get values` reports them.
		const merge = (base: unknown, change: unknown): unknown =>
			isRecord(base) && isRecord(change)
				? Object.fromEntries(
						[...new Set([...Object.keys(base), ...Object.keys(change)])].map((key) => [
							key,
							key in change ? merge(base[key], change[key]) : base[key],
						]),
					)
				: change;
		const releaseValues = asRecord(merge(prepared, gvisor), "release values");
		const paused = ["--set", "migration.enabled=false,server.replicas=0"];
		for (const [name, restore, layers] of [
			["backup", { enabled: false, time: "" }, []],
			["backup", { enabled: false, time: "" }, paused],
			["restore", { enabled: true, time: "2026-01-01 00:00:00.000000+00" }, paused],
		] as const) {
			const work = path.join(directory, `work-${name}-${layers.length}`);
			mkdirSync(work);
			const kept = renderLayers(prepared, [
				...(await drillLayer(work, releaseValues, name, pinned(restore))),
				...layers,
			]);
			assert.equal(kept.status, 0, kept.stderr);
			assert.deepEqual(capacity(kept.documents), source);
			assert.match(JSON.stringify(kept.documents), /"runtimeClassName":"gvisor"/u);
			assert.equal(
				kept.documents.some(
					(resource) =>
						resource.kind === "Job" &&
						isRecord(resource.metadata) &&
						isRecord(resource.metadata.labels) &&
						resource.metadata.labels["app.kubernetes.io/component"] === "migration",
				),
				layers.length === 0,
			);
			if (process.platform !== "win32") {
				assert.equal(statSync(path.join(work, "source.yaml")).mode.toString(8).slice(-3), "600");
			}
		}
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
});

await test("the prepared values run the sandboxes on the dev profile's server, with the bundled or an external PostgreSQL", () => {
	const api = { address: "172.18.0.3", port: 6443 };
	const bundled = render(operatorValues({ images, commit: "c".repeat(40), api }));
	const server = bundled.find(
		(resource) =>
			resource.kind === "Deployment" &&
			isRecord(resource.metadata) &&
			resource.metadata.name === `${RELEASE}-server`,
	);
	assert.ok(server, "a server Deployment");
	assert.match(JSON.stringify(server), /"name":"AGENT_ENABLED","value":"true"/u);
	assert.ok(
		bundled.some(
			(resource) =>
				resource.kind === "NetworkPolicy" &&
				isRecord(resource.metadata) &&
				resource.metadata.namespace === SANDBOXES,
		),
	);
	const external = render(
		operatorValues({
			images,
			commit: "c".repeat(40),
			api,
			externalPostgresql: { host: "postgresql.external-postgresql.svc", cidr: "192.168.0.0/16" },
		}),
	);
	assert.equal(
		external.some(
			(resource) =>
				isRecord(resource.metadata) && resource.metadata.name === `${RELEASE}-postgresql`,
		),
		false,
	);
});

await test("the dev values route the dev host through Traefik, and pass the GitHub OAuth app and first admins", () => {
	const resources = render(
		operatorValues({
			images,
			commit: "c".repeat(40),
			api: { address: "172.18.0.3", port: 6443 },
			gateway: {
				host: DEV_HOST,
				namespace: "kube-system",
				podLabels: { "app.kubernetes.io/name": "traefik" },
			},
			signIn: { clientId: "Iv1.client", bootstrapAdmins: "github:@octocat,gitlab:@example" },
		}),
	);
	const spec = (kind: string, name: string) => {
		const resource = resources.find(
			(candidate) =>
				candidate.kind === kind && isRecord(candidate.metadata) && candidate.metadata.name === name,
		);
		assert.ok(isRecord(resource) && isRecord(resource.spec), `${kind} ${name}`);
		return resource.spec;
	};
	assert.deepEqual(spec("HTTPRoute", `${RELEASE}-route`).hostnames, [DEV_HOST]);
	const { ingress } = spec("NetworkPolicy", `${RELEASE}-server`);
	assert.ok(Array.isArray(ingress) && isRecord(ingress[0]) && Array.isArray(ingress[0].from));
	assert.deepEqual(ingress[0].from[0], {
		namespaceSelector: { matchLabels: { "kubernetes.io/metadata.name": "kube-system" } },
		podSelector: { matchLabels: { "app.kubernetes.io/name": "traefik" } },
	});
	const { template } = spec("Deployment", `${RELEASE}-server`);
	assert.ok(
		isRecord(template) && isRecord(template.spec) && Array.isArray(template.spec.containers),
	);
	const server: unknown = template.spec.containers[0];
	assert.ok(isRecord(server) && Array.isArray(server.env));
	const environment = new Map(
		server.env.filter(isRecord).map((variable) => [variable.name, variable.value]),
	);
	assert.equal(environment.get("GITHUB_OAUTH_CLIENT_ID"), "Iv1.client");
	assert.equal(
		environment.get("HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS"),
		"github:@octocat,gitlab:@example",
	);
	assert.equal(environment.get("HEPHAESTUS_AUTH_ISSUER"), `https://${DEV_HOST}`);
});

await test("dev reports one problem of each kind, and none when all is in place", () => {
	const ready = { missing: [], dockerMemory: DEV_MEMORY, helmVersion: "v4.3.1" };
	const kinds = (check: Partial<Parameters<typeof devProblems>[0]>) =>
		devProblems({ ...ready, ...check }).map((problem) => problem.kind);
	assert.deepEqual(kinds({}), []);
	assert.deepEqual(kinds({ missing: ["pack", "k3d"] }), ["tool", "tool"]);
	// No Docker: no question about its memory, networks or port.
	assert.deepEqual(kinds({ missing: ["docker"], dockerMemory: undefined }), ["tool"]);
	assert.deepEqual(kinds({ dockerMemory: undefined }), ["docker"]);
	assert.deepEqual(kinds({ dockerMemory: Number.NaN }), ["docker"]);
	// Docker Desktop reports about 97 % of its setting.
	assert.deepEqual(kinds({ dockerMemory: 0.97 * 10 * 1024 ** 3 }), []);
	assert.deepEqual(kinds({ dockerMemory: 0.97 * 8 * 1024 ** 3 }), ["memory"]);
	assert.deepEqual(kinds({ unusedNetworks: 25 }), ["network"]);
	assert.deepEqual(kinds({ portBusy: true }), ["port"]);
	assert.deepEqual(kinds({ portBusy: false }), []);
	assert.deepEqual(kinds({ helmVersion: "v3.19.0" }), ["helm"]);
	const [tool] = devProblems({ ...ready, missing: ["k3d"] });
	assert.equal(tool?.message.includes("https://k3d.io"), true, "names where to get the tool");
});

/** A `k3d cluster list -o json` with another cluster and the dev cluster. */
const listed = (
	serversRunning: number,
	ports: Record<string, unknown> | null,
	api: unknown = [{ HostIp: "127.0.0.1" }],
) => [
	{ name: "other", serversRunning: 1, nodes: [] },
	{
		name: DEV_CLUSTER,
		serversRunning,
		nodes: [
			{ role: "loadbalancer", portMappings: { "6443/tcp": api, ...ports } },
			{ role: "server", portMappings: null },
		],
	},
];

await test("the dev cluster is absent, stopped, running, or from a run that published no HTTPS port", () => {
	assert.equal(clusterState([]), "absent");
	assert.equal(clusterState(listed(1, null)), "without-port");
	assert.equal(clusterState(listed(0, { "443/tcp": [{}] })), "stopped");
	assert.equal(clusterState(listed(1, { "443/tcp": [{}] })), "running");
});

await test("the created cluster publishes the API and HTTPS on loopback", () => {
	const args = devClusterArguments("/tmp/registries.yaml");
	assert.equal(args[args.indexOf("--api-port") + 1], "127.0.0.1:random");
	assert.equal(args[args.indexOf("--port") + 1], "127.0.0.1:443:443@loadbalancer");
});

await test("an existing wildcard, non-loopback or unknown API binding needs reset even while stopped", () => {
	for (const running of [0, 1]) {
		for (const host of [
			"0.0.0.0",
			"::",
			"",
			"192.168.1.2",
			"localhost",
			"127.example.invalid",
			null,
		]) {
			assert.equal(
				clusterState(listed(running, { "443/tcp": [{}] }, [{ HostIp: host }])),
				"needs-reset",
			);
		}
		for (const api of [{}, null, [], [{}], [{ HostIp: "127.0.0.1" }, { HostIp: "0.0.0.0" }]]) {
			assert.equal(clusterState(listed(running, { "443/tcp": [{}] }, api)), "needs-reset");
		}
	}
	for (const host of ["127.0.0.1", "127.2.3.4", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"]) {
		assert.equal(clusterState(listed(1, { "443/tcp": [{}] }, [{ HostIp: host }])), "running");
	}
});

await test("an extra API publication on a server node also requires reset", () => {
	const clusters = [
		{
			name: DEV_CLUSTER,
			serversRunning: 1,
			nodes: [
				{
					role: "loadbalancer",
					portMappings: { "443/tcp": [{}], "6443/tcp": [{ HostIp: "127.0.0.1" }] },
				},
				{ role: "server", portMappings: { "6443/tcp": [{ HostIp: "0.0.0.0" }] } },
			],
		},
	];
	assert.equal(clusterState(clusters), "needs-reset");
	assert.equal(
		clusterState([
			{
				name: DEV_CLUSTER,
				serversRunning: 1,
				nodes: [{ role: "loadbalancer", portMappings: { "443/tcp": [{}] } }],
			},
		]),
		"needs-reset",
	);
});

await test("sign-in takes the OAuth app from the shell, else keeps an earlier run's, and refuses half an app", () => {
	const app = { GITHUB_OAUTH_CLIENT_ID: "Iv1.client", GITHUB_OAUTH_CLIENT_SECRET: "secret" };
	assert.equal(devSignIn({}, undefined), undefined);
	assert.deepEqual(devSignIn(app, undefined), {
		clientId: "Iv1.client",
		clientSecret: "secret",
		bootstrapAdmins: "",
	});
	const saved = { clientId: "Iv1.saved", bootstrapAdmins: "github:@octocat" };
	assert.deepEqual(devSignIn({}, saved), saved);
	assert.deepEqual(devSignIn({ HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS: "github:@hubot" }, saved), {
		clientId: "Iv1.saved",
		bootstrapAdmins: "github:@hubot",
	});
	assert.deepEqual(devSignIn(app, saved), {
		clientId: "Iv1.client",
		clientSecret: "secret",
		bootstrapAdmins: "github:@octocat",
	});
	assert.throws(() => devSignIn({ GITHUB_OAUTH_CLIENT_ID: "Iv1.client" }, saved), /set both/u);
	assert.throws(() => devSignIn({ GITHUB_OAUTH_CLIENT_SECRET: "secret" }, undefined), /set both/u);
});

await test("the summary gives the address, the trust steps for the system, and the OAuth app steps until the app is set", () => {
	const options = { host: DEV_HOST, ca: "/work/ca.crt", platform: "darwin" as const };
	const without = devSummary({ ...options, signIn: false });
	assert.ok(without.includes(`https://${DEV_HOST}`));
	assert.ok(without.includes(`https://${DEV_HOST}/api/login/oauth2/code/github`));
	for (const step of trustSteps("darwin", "/work/ca.crt")) {
		assert.ok(without.includes(step), step);
	}
	assert.equal(devSummary({ ...options, signIn: true }).includes("/api/login/oauth2"), false);
	assert.equal(
		trustSteps("darwin", "/work/ca.crt")[0]?.startsWith("security add-trusted-cert"),
		true,
	);
	assert.ok(trustSteps("linux", "/work/ca.crt").includes("sudo update-ca-certificates"));
	// Every system gets the mkcert route, which also covers the browsers' own stores.
	for (const platform of ["darwin", "linux", "win32"] as const) {
		assert.equal(
			trustSteps(platform, "/work/ca.crt").at(-1)?.includes('CAROOT="/work/mkcert"'),
			true,
		);
	}
});

const statefulPod = (name: string, component: string, uid: string, deleting = false) => ({
	metadata: {
		name,
		namespace: "hephaestus",
		uid,
		labels: { "app.kubernetes.io/component": component },
		...(deleting ? { deletionTimestamp: "2026-10-26T18:00:00Z" } : {}),
	},
});

await test("an upgrade check names each PostgreSQL Pod that it replaced or lost", () => {
	const before = statefulPods({
		items: [
			statefulPod("heph-postgresql-0", "postgresql", "a"),
			statefulPod("heph-server-1", "server", "c"),
		],
	});
	assert.deepEqual([...before], [["hephaestus/heph-postgresql-0", "a"]]);
	assert.deepEqual(restartedPods(before, before), []);
	const after = statefulPods({
		items: [statefulPod("heph-postgresql-0", "postgresql", "d")],
	});
	assert.deepEqual(restartedPods(before, after), ["hephaestus/heph-postgresql-0"]);
});

const externalPostgresqlPod = (uid: string) => ({
	metadata: {
		namespace: "external-postgresql",
		name: "postgresql-0",
		uid,
		labels: { app: "postgresql" },
	},
});

await test("the external-database upgrade checks the PostgreSQL Pod outside the release namespace", () => {
	const database = externalPostgresqlPod("database-before");
	const before = statefulPods({
		items: [
			database,
			statefulPod("heph-server-1", "server", "server"),
			statefulPod("heph-postgresql-0", "postgresql", "terminating", true),
			{ metadata: { ...database.metadata, namespace: "unrelated" } },
		],
	});
	assert.deepEqual([...before], [["external-postgresql/postgresql-0", "database-before"]]);
	assert.deepEqual(restartedPods(before, statefulPods({ items: [database] })), []);
	assert.deepEqual(
		restartedPods(before, statefulPods({ items: [externalPostgresqlPod("database-after")] })),
		["external-postgresql/postgresql-0"],
	);
	assert.deepEqual(restartedPods(before, statefulPods({ items: [] })), [
		"external-postgresql/postgresql-0",
	]);
});

await test("the retired live qualifier invokes no cluster or build tool", () => {
	const emptyPath = mkdtempSync(path.join(tmpdir(), "heph-live-no-tools-"));
	try {
		const result = spawnSync(
			process.execPath,
			[fileURLToPath(new URL("kubernetes-cluster.ts", import.meta.url)), "live"],
			{
				encoding: "utf8",
				env: { ...process.env, CI: "false", PATH: emptyPath },
			},
		);
		assert.equal(result.status, 1);
		assert.match(result.stderr, /usage: kubernetes-cluster/u);
	} finally {
		rmSync(emptyPath, { recursive: true, force: true });
	}
});

await test("replacement-storage drill refuses local execution before invoking cluster tools", () => {
	const emptyPath = mkdtempSync(path.join(tmpdir(), "heph-restore-no-tools-"));
	try {
		const result = spawnSync(
			process.execPath,
			[
				fileURLToPath(new URL("kubernetes-cluster.ts", import.meta.url)),
				"backup-drill",
				"--pod-cidr",
				"10.42.0.0/16",
			],
			{ encoding: "utf8", env: { ...process.env, CI: "false", PATH: emptyPath } },
		);
		assert.equal(result.status, 1);
		assert.match(result.stderr, /requires the disposable CI cluster/u);
	} finally {
		rmSync(emptyPath, { recursive: true, force: true });
	}
});

await test("the sign-in Pod runs the server template with the e2e sign-in, on loopback and outside every Service", () => {
	const template = {
		metadata: { labels: { "app.kubernetes.io/component": "server" } },
		spec: {
			serviceAccountName: "heph-server",
			containers: [
				{
					name: "server",
					image: "server@sha256:original",
					env: [
						{ name: "DATABASE_URL", value: "postgresql://heph-postgresql:5432/hephaestus" },
						{ name: "SPRING_PROFILES_ACTIVE", value: "prod" },
						{ name: "HEPHAESTUS_AUTH_ASSURANCE_ENFORCED", value: "true" },
					],
					readinessProbe: { httpGet: { path: "/actuator/health/readiness", port: "management" } },
				},
			],
		},
	};
	const deployment = { metadata: { namespace: "hephaestus" }, spec: { template } };
	const before = structuredClone(deployment);
	const pod = signInPod(deployment, "heph-turn-roll-sign-in");
	assert.deepEqual(deployment, before);
	assert.deepEqual(pod.metadata, {
		...template.metadata,
		name: "heph-turn-roll-sign-in",
		namespace: "hephaestus",
	});
	const spec = asRecord(pod.spec, "Pod spec");
	assert.equal(spec.restartPolicy, "Never");
	assert.equal(spec.serviceAccountName, "heph-server");
	assert.deepEqual(spec.readinessGates, [{ conditionType: "hephaestus.build/never-ready" }]);
	const container = asRecord(asArray(spec.containers, "containers")[0], "container");
	assert.equal(container.image, "server@sha256:original");
	assert.deepEqual(container.readinessProbe, template.spec.containers[0]?.readinessProbe);
	const variables = asArray(container.env, "env").map((variable) => asRecord(variable, "variable"));
	const env = new Map(variables.map((variable) => [variable.name, variable.value]));
	assert.equal(env.get("SPRING_PROFILES_ACTIVE"), "e2e");
	assert.equal(env.get("HEPHAESTUS_AUTH_ASSURANCE_ENFORCED"), "false");
	assert.equal(env.get("SERVER_ADDRESS"), "127.0.0.1");
	assert.equal(env.get("HEPHAESTUS_AUTH_ISSUER"), undefined);
	assert.equal(env.get("HEPHAESTUS_AUTH_COOKIE_SECURE"), "true");
	assert.equal(env.get("SPRING_DATASOURCE_URL"), "jdbc:$(DATABASE_URL)");
	// Kubernetes expands a variable only from one that an earlier entry defines.
	const names = variables.map((variable) => variable.name);
	assert.ok(names.indexOf("DATABASE_URL") < names.indexOf("SPRING_DATASOURCE_URL"));
	assert.equal(new Set(names).size, names.length);
	assert.throws(
		() => signInPod({ metadata: {}, spec: { template: { spec: { containers: [] } } } }, "x"),
		/single-container server template/u,
	);
});

await test("the drill reads the local port that kubectl port-forward reports", () => {
	assert.equal(forwardedPort("Forwarding from 127.0.0.1:43127 -> 8080\n"), 43_127);
	assert.equal(
		forwardedPort("Forwarding from 127.0.0.1:43127 -> 8080\nForwarding from [::1]:43127 -> 8080\n"),
		43_127,
	);
	assert.equal(forwardedPort("Forwarding from [::1]:43127 -> 8080\n"), undefined);
	assert.equal(forwardedPort(""), undefined);
});

const DRILL_RUN = "11111111-1111-4111-8111-111111111111";
const DRILL_SANDBOX = "22222222-2222-4222-8222-222222222222";
const OTHER_SANDBOX = "33333333-3333-4333-8333-333333333333";

await test("the drill reads the turn's run fence and physical sandbox, and refuses malformed identities", () => {
	const running = {
		status: "RUNNING",
		attempt: 1,
		runId: DRILL_RUN,
		epoch: 2,
		sandboxId: DRILL_SANDBOX,
		claim: "heph-sandbox-a",
	};
	assert.deepEqual(turnState(JSON.stringify(running)), running);
	const queued = {
		...running,
		status: "QUEUED",
		attempt: 0,
		epoch: 0,
		sandboxId: null,
		claim: null,
	};
	assert.deepEqual(turnState(JSON.stringify(queued)), queued);
	for (const changed of [
		{ claim: "../x" },
		{ sandboxId: "../x" },
		{ epoch: -1 },
		{ epoch: 0.5 },
		{ runId: "../x" },
		{ claim: null },
	]) {
		assert.throws(
			() => turnState(JSON.stringify({ ...running, ...changed })),
			/valid|fence|disagree/u,
		);
	}
	assert.throws(() => turnState(JSON.stringify({ ...queued, status: undefined })), /no status/u);
});

await test("a server roll keeps the exact TURN fence and physical conversation sandbox", () => {
	const recorded = {
		attempt: 1,
		runId: DRILL_RUN,
		epoch: 2,
		sandboxId: DRILL_SANDBOX,
		claim: "heph-sandbox-a",
	};
	const failures = (changed: Partial<ReturnType<typeof turnState>>) =>
		heldChecks("after the roll", recorded, { status: "RUNNING", ...recorded, ...changed }).flatMap(
			(check) => (check.failure === undefined ? [] : [check.failure]),
		);
	assert.deepEqual(failures({}), []);
	assert.deepEqual(
		failures({ status: "QUEUED", attempt: 2, epoch: 3, sandboxId: null, claim: null }),
		[
			"the turn is QUEUED",
			"it runs attempt 2",
			"it holds epoch 3",
			"it holds sandbox none",
			"it holds claim none",
		],
	);
	assert.deepEqual(failures({ sandboxId: OTHER_SANDBOX, claim: "heph-sandbox-b" }), [
		`it holds sandbox ${OTHER_SANDBOX}`,
		"it holds claim heph-sandbox-b",
	]);
	assert.deepEqual(failures({ runId: OTHER_SANDBOX }), [`it holds run ${OTHER_SANDBOX}`]);
});

await test("the ended turn passes only DONE on attempt 1, with every bash output in its results and its answer", () => {
	const outcome = turnOutcome(
		JSON.stringify({
			status: "DONE",
			attempt: 1,
			answer: "The commands printed: rolled-1 rolled-2.",
			bash: [
				{ isError: false, text: "rolled-1\n" },
				{ isError: false, text: "rolled-2\n" },
			],
		}),
	);
	const failures = (changed: Partial<typeof outcome>) =>
		outcomeChecks({ ...outcome, ...changed }).flatMap((check) =>
			check.failure === undefined ? [] : [check.failure],
		);
	assert.deepEqual(failures({}), []);
	assert.deepEqual(failures({ status: "FAILED", attempt: 2 }), [
		"it ended FAILED",
		"it ran 2 attempts",
	]);
	assert.deepEqual(failures({ bash: [] }), ["the turn stored no bash result"]);
	assert.deepEqual(
		failures({
			bash: [
				{ isError: false, text: "rolled-1\n" },
				{ isError: true, text: "Command timed out" },
			],
		}),
		['bash call 2 returned "Command timed out"'],
	);
	assert.deepEqual(failures({ answer: "The commands printed: rolled-1." }), [
		"the answer lacks rolled-2: The commands printed: rolled-1.",
	]);
});

const policy = (...managers: [manager: string, operation: string][]) => ({
	metadata: {
		name: "heph-sandbox-deny",
		managedFields: managers.map(([manager, operation]) => ({ manager, operation })),
	},
});

void test("the negative control restores the sandbox policies as the one field manager that owned them", () => {
	// Helm records an install as an update and an upgrade as a server-side apply, under one manager.
	assert.equal(soleManager([policy(["helm", "Update"]), policy(["helm", "Apply"])]), "helm");
	assert.throws(
		() => soleManager([policy(["helm", "Apply"]), policy(["kubectl-client-side-apply", "Update"])]),
		/2 field managers/u,
	);
	// A client-side apply would own the fields that Helm's next upgrade applies, and that upgrade then conflicts.
	assert.deepEqual(
		restoreFailures([policy(["helm", "Apply"], ["kubectl-client-side-apply", "Update"])], "helm"),
		["kubectl-client-side-apply owns fields beside helm"],
	);
	assert.deepEqual(restoreFailures([policy(["helm", "Apply"])], "helm"), []);
});

void test("a policy that names no field manager fails the control, as a read without managedFields would show it", () => {
	const stripped = { metadata: { name: "heph-sandbox-gateway" } };
	// A read without --show-managed-fields shows no owner, which proves nothing: it must not pass as owned.
	assert.deepEqual(restoreFailures([policy(["helm", "Apply"]), stripped], "helm"), [
		"heph-sandbox-gateway names no field manager",
	]);
	assert.deepEqual(restoreFailures([policy()], "helm"), [
		"heph-sandbox-deny names no field manager",
	]);
	assert.throws(
		() => soleManager([policy(["helm", "Update"]), stripped]),
		/heph-sandbox-gateway name no field manager/u,
	);
});

/**
 * A kubectl stand-in that records each call. It lists no workloads, one server Pod, and the sandbox gateway
 * Service only under `release`; every later call fails, so verify() stops right after the Service lookup.
 */
function fakeKubectl(release: string): { calls: () => string; restore: () => void } {
	const directory = mkdtempSync(path.join(tmpdir(), "fake-kubectl-"));
	const log = path.join(directory, "calls");
	const script = path.join(directory, "kubectl.mjs");
	writeFileSync(log, "");
	writeFileSync(
		script,
		`import { appendFileSync } from "node:fs";
const call = process.argv.slice(2).join(" ");
appendFileSync(${JSON.stringify(log)}, call + "\\n");
const answers = [
	[/get (deployment|statefulset|daemonset) -o name$/u, ""],
	[/get pods -l app\\.kubernetes\\.io\\/component=server -o json$/u, '{"items":[{"metadata":{"name":"server-0"}}]}'],
	[new RegExp(${JSON.stringify(`get service ${release}-sandbox-gateway -o json$`)}, "u"), '{"spec":{"clusterIP":"10.43.0.12"}}'],
];
const answer = answers.find(([pattern]) => pattern.test(call));
if (answer === undefined) process.exit(1);
process.stdout.write(answer[1]);
`,
	);
	const restore = useKubectl([process.execPath, script]);
	return {
		calls: () => readFileSync(log, "utf8"),
		restore: () => {
			restore();
			rmSync(directory, { recursive: true, force: true });
		},
	};
}

await test("verify queries the release that it is given, and the dev cluster's release by default", async () => {
	for (const [release, run] of [
		["hephaestus", async () => verify("hephaestus")],
		[RELEASE, async () => verify()],
	] as const) {
		const fake = fakeKubectl(release);
		try {
			// The fake serves only the given release's gateway, so a wrong name fails at that lookup.
			await assert.rejects(run());
			const calls = fake.calls();
			assert.match(calls, new RegExp(`get service ${release}-sandbox-gateway -o json`, "u"));
			assert.match(calls, /get pod\/server-0 -o json/u);
		} finally {
			fake.restore();
		}
	}
});
