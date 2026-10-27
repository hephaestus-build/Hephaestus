import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { parseAllDocuments } from "yaml";

import { durationSeconds, gatewayCertificate } from "./kubernetes-cluster.ts";
import { isRecord } from "./lib/json.ts";

const PLATFORM = fileURLToPath(new URL("../deploy/platform/", import.meta.url));
const read = (file: string) => readFileSync(path.join(PLATFORM, file), "utf8");
const documents = (source: string) =>
	parseAllDocuments(source)
		.map((document) => document.toJS() as unknown)
		.filter(isRecord);
const digest = (character: string) => `@sha256:${character.repeat(64)}`;
const named = (resources: Record<string, unknown>[], kind: string, name: string) =>
	resources.find(
		(resource) =>
			resource.kind === kind && isRecord(resource.metadata) && resource.metadata.name === name,
	);

await test("the kubelet reserves memory for k3s and the host, and states every hard eviction signal", () => {
	const [kubelet] = documents(read("k3s-kubelet.yaml"));
	assert.ok(kubelet);
	for (const key of ["kubeReserved", "systemReserved"]) {
		const reserved = kubelet[key];
		assert.ok(isRecord(reserved) && typeof reserved.memory === "string", `${key} reserves memory`);
	}
	// A partial map sets the signals that it leaves out to zero, so each Linux signal is stated.
	assert.deepEqual(
		Object.keys(isRecord(kubelet.evictionHard) ? kubelet.evictionHard : {}).toSorted(),
		[
			"imagefs.available",
			"imagefs.inodesFree",
			"memory.available",
			"nodefs.available",
			"nodefs.inodesFree",
		],
	);
});

await test("a Go duration reads in seconds, and anything else is refused", () => {
	assert.equal(durationSeconds("5m0s"), durationSeconds("5m"));
	assert.equal(durationSeconds("1h2m3s"), 3723);
	assert.throws(() => durationSeconds("5 minutes"), /not a duration/u);
});

/** `helm template` of the small profile, the one-host values and a site.yaml, as k3s-install.sh installs them. */
function renderOneHost(extra: Record<string, unknown> = {}) {
	const chart = fileURLToPath(new URL("../deploy/chart/hephaestus/", import.meta.url));
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-k3s-"));
	try {
		// The release chart carries these values; the source chart names no image.
		const release = {
			release: { version: "v0.95.0", commit: "c".repeat(40) },
			images: {
				"agent-pi": `ghcr.io/hephaestus-build/agent-pi${digest("1")}`,
				"application-server": `ghcr.io/hephaestus-build/application-server${digest("2")}`,
				nats: `docker.io/library/nats${digest("3")}`,
				postgres: `ghcr.io/hephaestus-build/postgres${digest("4")}`,
				webapp: `ghcr.io/hephaestus-build/webapp${digest("5")}`,
			},
		};
		const site = {
			host: "hephaestus.example.com",
			networkPolicy: { kubernetesApi: { cidr: "192.168.5.15/32", port: 6443 }, ...extra },
		};
		const releaseFile = path.join(directory, "release.yaml");
		const siteFile = path.join(directory, "site.yaml");
		writeFileSync(releaseFile, JSON.stringify(release));
		writeFileSync(siteFile, JSON.stringify(site));
		const result = spawnSync(
			"helm",
			[
				"template",
				"hephaestus",
				chart,
				"--namespace",
				"hephaestus",
				"--values",
				path.join(chart, "profiles", "small.yaml"),
				"--values",
				releaseFile,
				"--values",
				path.join(PLATFORM, "k3s-values.yaml"),
				"--values",
				siteFile,
			],
			{ encoding: "utf8", timeout: 30_000 },
		);
		assert.equal(result.status, 0, result.stderr);
		return { text: result.stdout, resources: documents(result.stdout) };
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

/** The egress rules of the worker's own NetworkPolicy. */
const workerEgress = (resources: Record<string, unknown>[]) => {
	const policy = named(resources, "NetworkPolicy", "hephaestus-worker");
	assert.ok(policy && isRecord(policy.spec) && Array.isArray(policy.spec.egress));
	return policy.spec.egress.filter(isRecord);
};

await test("the one-host values bind the release to the platform, with reviews on", () => {
	const { text, resources } = renderOneHost();
	const route = named(resources, "HTTPRoute", "hephaestus-route");
	assert.ok(route && isRecord(route.spec));
	assert.deepEqual(route.spec.parentRefs, [
		{ name: "traefik-gateway", namespace: "traefik", sectionName: "websecure" },
	]);
	// The certificate that k3s-certificates.yaml issues names the host the roles connect to.
	assert.match(
		text,
		/postgresql:\/\/hephaestus-postgresql\.hephaestus\.svc:5432\/hephaestus\?sslmode=verify-full/u,
	);
	assert.match(read("k3s-certificates.yaml"), /- hephaestus-postgresql\.hephaestus\.svc\n/u);
	const worker = named(resources, "Deployment", "hephaestus-worker");
	assert.ok(worker, "the worker runs");
	assert.match(JSON.stringify(worker), /"name":"GIT_CHECKOUT_ENABLED","value":"true"/u);
	assert.ok(!JSON.stringify(workerEgress(resources)).includes('"traefik"'));
});

await test("workerGatewayPorts lets the worker reach the Gateway Pods on those ports only", () => {
	const { resources } = renderOneHost({ workerGatewayPorts: [8443] });
	const rules = workerEgress(resources).filter((rule) =>
		JSON.stringify(rule).includes('"traefik"'),
	);
	assert.deepEqual(
		rules.map((rule) => rule.ports),
		[[{ port: 8443, protocol: "TCP" }]],
	);
});

await test("the Gateway, its certificate, the installer and the smoke name one namespace", async () => {
	const platform = documents(read("k3s-platform.yaml"));
	const traefik = named(platform, "HelmChart", "traefik");
	assert.ok(traefik && isRecord(traefik.spec));
	const namespace = String(traefik.spec.targetNamespace);
	// The smoke reads the certificate's Secret where this returns it.
	assert.deepEqual(await gatewayCertificate(read("k3s-certificates.yaml")), {
		namespace,
		secretName: "hephaestus-tls",
	});
	assert.match(read("k3s-install.sh"), new RegExp(`^GATEWAY_NAMESPACE=${namespace}$`, "mu"));
	const issuer = named(documents(read("k3s-certificates.yaml")), "ClusterIssuer", "gateway");
	assert.match(JSON.stringify(issuer), new RegExp(`"namespace":"${namespace}"`, "u"));
	assert.match(
		read("k3s-values.yaml"),
		new RegExp(`namespace: ${namespace}\\n\\s+sectionName`, "u"),
	);
});

/** The certificates file that k3s-install.sh would install for these arguments. */
function rendered(...args: string[]) {
	const result = spawnSync(
		"sh",
		[
			path.join(PLATFORM, "k3s-install.sh"),
			"--version",
			"0.95.0",
			...args,
			"--render-certificates",
		],
		{ encoding: "utf8", timeout: 30_000 },
	);
	return { status: result.status, stderr: result.stderr, resources: documents(result.stdout) };
}

await test(
	"the installer writes the operator's host and address, and the staging service on request",
	{ skip: process.platform === "win32" },
	() => {
		const acme = (resources: Record<string, unknown>[]) => {
			const issuer = named(resources, "ClusterIssuer", "gateway");
			assert.ok(issuer && isRecord(issuer.spec) && isRecord(issuer.spec.acme));
			return issuer.spec.acme;
		};
		const production = rendered("--host", "hephaestus.example.org", "--email", "ops@example.org");
		assert.equal(production.status, 0, production.stderr);
		assert.equal(
			acme(production.resources).server,
			"https://acme-v02.api.letsencrypt.org/directory",
		);
		assert.equal(acme(production.resources).email, "ops@example.org");
		const certificate = named(production.resources, "Certificate", "hephaestus-tls");
		assert.ok(certificate && isRecord(certificate.spec));
		assert.deepEqual(certificate.spec.dnsNames, ["hephaestus.example.org"]);
		const staging = rendered("--host", "h.example.org", "--email", "a@example.org", "--staging");
		assert.equal(staging.status, 0, staging.stderr);
		assert.equal(
			acme(staging.resources).server,
			"https://acme-staging-v02.api.letsencrypt.org/directory",
		);
		// A sed metacharacter never reaches sed.
		assert.notEqual(rendered("--host", "a.example.org", "--email", "a|b@example.org").status, 0);
		assert.notEqual(rendered("--host", "a&b.example.org", "--email", "a@example.org").status, 0);
	},
);
