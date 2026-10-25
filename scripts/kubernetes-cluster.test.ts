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
