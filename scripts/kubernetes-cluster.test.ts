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
	devSignIn,
	devSummary,
	NAMESPACE,
	operatorValues,
	RELEASE,
	SANDBOXES,
} from "./kubernetes-cluster.ts";
import { isRecord } from "./lib/json.ts";

const chart = fileURLToPath(new URL("../deploy/chart/hephaestus/", import.meta.url));
const digest = (character: string) => `sha256:${character.repeat(64)}`;
const resolved = ["webapp", "application-server", "agent-pi", "postgres"].map((image, index) => ({
	image,
	repository: `ghcr.io/hephaestus-build/${image}`,
	indexDigest: digest(String(index)),
}));
const upstream = [
	{ name: "nats", repository: "docker.io/synadia/nats-server", digest: digest("e") },
	{ name: "alpine", repository: "docker.io/library/alpine", digest: digest("a") },
];
const images = chartImagesFrom(resolved, upstream);

await test("the chart's images come from the resolved first-party images and the upstream pins", () => {
	assert.deepEqual(images, {
		"agent-pi": `ghcr.io/hephaestus-build/agent-pi@${digest("2")}`,
		"application-server": `ghcr.io/hephaestus-build/application-server@${digest("1")}`,
		nats: `docker.io/synadia/nats-server@${digest("e")}`,
		postgres: `ghcr.io/hephaestus-build/postgres@${digest("3")}`,
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

await test("the generated Secret holds every key that the chart reads, and two runs share no value", () => {
	const first = applicationSecret();
	const second = applicationSecret();
	for (const key of [
		"DATABASE_USERNAME",
		"DATABASE_PASSWORD",
		"NATS_USERNAME",
		"NATS_PASSWORD",
		"HEPHAESTUS_SECURITY_ENCRYPTION_KEY",
		"HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY",
		"HEPHAESTUS_AUTH_STATE_COOKIE_KEY",
		"WEBHOOK_SECRET",
		"WEBHOOK_ROUTING_SECRET",
		"HEPHAESTUS_WORKER_REGISTRATION_TOKEN",
	]) {
		assert.ok((first[key] ?? "").length > 0, key);
	}
	assert.notEqual(first.DATABASE_PASSWORD, second.DATABASE_PASSWORD);
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
			signIn: { clientId: "Iv1.client", bootstrapAdmins: "github:@octocat" },
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
	assert.equal(environment.get("HEPHAESTUS_AUTH_BOOTSTRAP_ADMINS"), "github:@octocat");
	assert.equal(environment.get("HEPHAESTUS_AUTH_ISSUER"), `https://${DEV_HOST}`);
});

await test("dev names every missing tool and each Docker problem, and nothing when all is in place", () => {
	const ready = { missing: [], dockerMemory: DEV_MEMORY, helmVersion: "v4.3.1" };
	assert.deepEqual(devProblems(ready), []);
	const missing = devProblems({ ...ready, missing: ["pack", "k3d"] });
	assert.deepEqual(
		missing.map((line) => line.split(" ")[0]).toSorted((a, b) => (a ?? "").localeCompare(b ?? "")),
		["k3d", "pack"],
	);
	assert.ok(missing.some((line) => line.includes("Install it from https://k3d.io")));
	assert.deepEqual(
		devProblems({ ...ready, missing: ["docker"], dockerMemory: undefined }).map(
			(line) => line.split(" ")[0],
		),
		["docker"],
	);
	for (const dockerMemory of [undefined, Number.NaN]) {
		assert.match(devProblems({ ...ready, dockerMemory })[0] ?? "", /Docker does not answer/u);
	}
	// Docker Desktop reports about 97 % of its setting.
	assert.deepEqual(devProblems({ ...ready, dockerMemory: 0.97 * 10 * 1024 ** 3 }), []);
	assert.match(
		devProblems({ ...ready, dockerMemory: 0.97 * 8 * 1024 ** 3 })[0] ?? "",
		/Docker has 7\.8 GiB of memory; the cluster and a practice review need 10 GiB/u,
	);
	assert.match(devProblems({ ...ready, helmVersion: "v3.19.0" })[0] ?? "", /needs Helm 4/u);
	assert.match(
		devProblems({ ...ready, unusedNetworks: 25 })[0] ?? "",
		/no address pool left .* 25 networks have no container, .* Run docker network prune/u,
	);
});

/** A `k3d cluster list -o json` with another cluster and the dev cluster. */
const listed = (serversRunning: number, ports: Record<string, unknown> | null) => [
	{ name: "other", serversRunning: 1, nodes: [] },
	{
		name: DEV_CLUSTER,
		serversRunning,
		nodes: [
			{ role: "loadbalancer", portMappings: { "6443/tcp": [{}], ...ports } },
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

await test("the summary gives the address, the CA, and the OAuth app steps until the app is set", () => {
	const without = devSummary({ host: DEV_HOST, ca: "/work/ca.crt", signIn: false });
	assert.match(without, new RegExp(`Hephaestus runs at https://${DEV_HOST}\\n`, "u"));
	assert.match(
		without,
		new RegExp(`callback https://${DEV_HOST}/api/login/oauth2/code/github`, "u"),
	);
	assert.match(without, /\/work\/ca\.crt/u);
	const signedIn = devSummary({ host: DEV_HOST, ca: "/work/ca.crt", signIn: true });
	assert.match(signedIn, /Sign in with GitHub\./u);
	assert.doesNotMatch(signedIn, /callback/u);
});
