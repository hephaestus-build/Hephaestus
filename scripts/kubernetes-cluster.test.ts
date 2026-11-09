import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parseAllDocuments } from "yaml";

import {
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
	forwardedPort,
	heldChecks,
	trustSteps,
	mergeSecret,
	livePod,
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
	{ name: "nats", repository: "docker.io/synadia/nats-server", digest: digest("e") },
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
		nats: `docker.io/synadia/nats-server@${digest("e")}`,
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
		"DATABASE_USERNAME",
		"DATABASE_PASSWORD",
		"NATS_USERNAME",
		"NATS_PASSWORD",
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
	assert.equal(third.data.DATABASE_PASSWORD, first.data.DATABASE_PASSWORD);
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

await test("the prepared values install two workers on the dev profile, with the bundled or an external PostgreSQL", () => {
	const api = { address: "172.18.0.3", port: 6443 };
	const bundled = render(operatorValues({ images, commit: "c".repeat(40), api }));
	const worker = bundled.find(
		(resource) =>
			resource.kind === "Deployment" &&
			isRecord(resource.metadata) &&
			resource.metadata.name === `${RELEASE}-worker`,
	);
	assert.ok(worker, "a worker Deployment");
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
		uid,
		labels: { "app.kubernetes.io/component": component },
		...(deleting ? { deletionTimestamp: "2026-10-26T18:00:00Z" } : {}),
	},
});

await test("an upgrade check names each PostgreSQL or NATS Pod that it replaced or lost", () => {
	const before = statefulPods({
		items: [
			statefulPod("heph-postgresql-0", "postgresql", "a"),
			statefulPod("heph-nats-0", "nats", "b"),
			statefulPod("heph-server-1", "server", "c"),
		],
	});
	assert.deepEqual(
		[...before],
		[
			["heph-postgresql-0", "a"],
			["heph-nats-0", "b"],
		],
	);
	assert.deepEqual(restartedPods(before, before), []);
	const after = statefulPods({
		items: [
			statefulPod("heph-postgresql-0", "postgresql", "d"),
			statefulPod("heph-nats-0", "nats", "b", true),
		],
	});
	assert.deepEqual(restartedPods(before, after), ["heph-postgresql-0", "heph-nats-0"]);
});

await test("the live Pod keeps the chart worker's UID, groups, capabilities, mounts, metadata and network labels", () => {
	const template = {
		metadata: {
			labels: { "app.kubernetes.io/component": "worker", "app.kubernetes.io/instance": "heph" },
		},
		spec: {
			serviceAccountName: "heph-worker",
			securityContext: {
				runAsUser: 1000,
				runAsGroup: 1000,
				fsGroup: 1000,
				seccompProfile: { type: "RuntimeDefault" },
			},
			containers: [
				{
					name: "worker",
					image: "server@sha256:original",
					imagePullPolicy: "IfNotPresent",
					securityContext: {
						allowPrivilegeEscalation: false,
						capabilities: { drop: ["ALL"] },
						readOnlyRootFilesystem: true,
					},
					env: [{ name: "POD_NAME", valueFrom: { fieldRef: { fieldPath: "metadata.name" } } }],
					volumeMounts: [{ name: "git-cache", mountPath: "/data/git-repos" }],
					readinessProbe: { httpGet: { path: "/actuator/health/readiness", port: "management" } },
				},
			],
			volumes: [{ name: "git-cache", emptyDir: { sizeLimit: "2Gi" } }],
		},
	};
	const deployment = { metadata: { namespace: "heph-checkout" }, spec: { template } };
	const before = structuredClone(deployment);
	const pod = livePod(deployment, "test-image:qualified", "heph-live");
	assert.deepEqual(pod, {
		apiVersion: "v1",
		kind: "Pod",
		metadata: { ...template.metadata, name: "heph-live", namespace: "heph-checkout" },
		spec: {
			...template.spec,
			restartPolicy: "Never",
			containers: [{ ...template.spec.containers[0], image: "test-image:qualified" }],
		},
	});
	assert.deepEqual(deployment, before);
});

await test("live qualification refuses a worker shape that would bypass the image entrypoint", () => {
	for (const containers of [
		[],
		[{ command: ["unexpected"] }],
		[{ imagePullPolicy: "Always" }],
		[{}, {}],
	]) {
		assert.throws(
			() =>
				livePod(
					{
						metadata: { namespace: "heph" },
						spec: { template: { metadata: {}, spec: { containers } } },
					},
					"test-image",
					"live",
				),
			/qualification/u,
		);
	}
});

await test("local live qualification requires an explicit namespace before invoking cluster tools", () => {
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
		assert.match(
			result.stderr,
			/requires --namespace to name the reserved shared qualification release/u,
		);
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

await test("the drill reads the turn's state and refuses a tools Pod name that is no DNS label", () => {
	assert.deepEqual(turnState('{"status":"RUNNING","attempt":1,"pod":"hephaestus-tools-0a1b"}'), {
		status: "RUNNING",
		attempt: 1,
		pod: "hephaestus-tools-0a1b",
	});
	assert.deepEqual(turnState('{"status":"QUEUED","attempt":0,"pod":null}'), {
		status: "QUEUED",
		attempt: 0,
		pod: null,
	});
	assert.throws(() => turnState('{"status":"RUNNING","attempt":1,"pod":"../x"}'), /valid name/u);
	assert.throws(() => turnState('{"attempt":1,"pod":null}'), /no status/u);
});

await test("a roll keeps the turn only when it still runs the recorded attempt with the recorded tools Pod", () => {
	const recorded = { attempt: 1, pod: "hephaestus-tools-a" };
	const failures = (observed: { status: string; attempt: number; pod: string | null }) =>
		heldChecks("after the roll", recorded, observed).flatMap((check) =>
			check.failure === undefined ? [] : [check.failure],
		);
	assert.deepEqual(failures({ status: "RUNNING", attempt: 1, pod: "hephaestus-tools-a" }), []);
	assert.deepEqual(failures({ status: "QUEUED", attempt: 2, pod: null }), [
		"the turn is QUEUED",
		"it runs attempt 2",
		"it holds no tools Pod",
	]);
	assert.deepEqual(failures({ status: "RUNNING", attempt: 1, pod: "hephaestus-tools-b" }), [
		"it holds hephaestus-tools-b",
	]);
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
