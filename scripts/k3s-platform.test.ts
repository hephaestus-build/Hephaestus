import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
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
	assert.deepEqual(kubelet.kubeReserved, { cpu: "250m", memory: "1Gi" });
	assert.deepEqual(kubelet.systemReserved, { cpu: "250m", memory: "512Mi" });
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

await test("the node policy keeps only hard eviction and bounds logs, images, and swap", () => {
	const [policy] = documents(read("k3s-kubelet.yaml"));
	assert.ok(policy);
	assert.deepEqual(policy.evictionHard, {
		"memory.available": "256Mi",
		"nodefs.available": "10%",
		"nodefs.inodesFree": "5%",
		"imagefs.available": "15%",
		"imagefs.inodesFree": "5%",
	});
	assert.deepEqual(policy.evictionSoft, {});
	assert.deepEqual(policy.evictionSoftGracePeriod, {});
	assert.equal(policy.evictionMaxPodGracePeriod, undefined);
	assert.deepEqual(policy.evictionMinimumReclaim, { "memory.available": "256Mi" });
	assert.equal(policy.evictionPressureTransitionPeriod, "5m0s");
	assert.deepEqual(policy.enforceNodeAllocatable, ["pods"]);
	assert.equal(policy.failSwapOn, true);
	assert.deepEqual(policy.memorySwap, { swapBehavior: "NoSwap" });
	assert.equal(policy.containerLogMaxSize, "10Mi");
	assert.equal(policy.containerLogMaxFiles, 5);
	assert.equal(policy.imageGCHighThresholdPercent, 75);
	assert.equal(policy.imageGCLowThresholdPercent, 65);
	const journal = read("k3s-journald.conf");
	assert.match(journal, /^SystemMaxUse=512M$/mu);
	assert.match(journal, /^RuntimeMaxUse=128M$/mu);
	assert.match(journal, /^MaxRetentionSec=7day$/mu);
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
	return renderedAt(PLATFORM, ...args);
}

function renderedAt(platform: string, ...args: string[]) {
	return installerAt(platform, ...args, "--render-certificates");
}

function installerAt(platform: string, ...args: string[]) {
	const result = spawnSync(
		"sh",
		[path.join(platform, "k3s-install.sh"), "--version", "0.95.0", ...args],
		{
			encoding: "utf8",
			timeout: 30_000,
			env: {
				...process.env,
				PATH: `${path.join(platform, "bin")}${path.delimiter}${process.env.PATH ?? ""}`,
			},
		},
	);
	return { status: result.status, stderr: result.stderr, resources: documents(result.stdout) };
}

await test("workspace certificates use DNS-01, and backup Jobs get their own client identity", () => {
	const result = rendered(
		"--host",
		"heph.example.org",
		"--email",
		"ops@example.org",
		"--workspace-subdomains",
		"--dns01-issuer",
		"zone-dns",
	);
	assert.equal(result.status, 0, result.stderr);
	const { resources } = result;
	const gateway = named(resources, "Certificate", "hephaestus-tls");
	assert.ok(gateway && isRecord(gateway.spec) && isRecord(gateway.spec.issuerRef));
	assert.deepEqual(gateway.spec.dnsNames, ["heph.example.org", "*.heph.example.org"]);
	assert.equal(gateway.spec.issuerRef.name, "zone-dns");
	const backup = named(resources, "Certificate", "hephaestus-postgresql-backup-tls");
	assert.ok(
		backup &&
			isRecord(backup.spec) &&
			isRecord(backup.spec.issuerRef) &&
			Array.isArray(backup.spec.usages),
	);
	assert.equal(backup.spec.commonName, "hephaestus-backup");
	assert.equal(backup.spec.issuerRef.name, "hephaestus-postgresql-ca");
	assert.ok(backup.spec.usages.includes("client auth"));
	assert.notEqual(
		rendered("--host", "heph.example.org", "--email", "ops@example.org", "--workspace-subdomains")
			.status,
		0,
	);
});

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

const nodeStatus = {
	capacity: { memory: "16372036Ki", cpu: "4" },
	allocatable: { memory: "14537028Ki", cpu: "3500m" },
	nodeInfo: { osImage: "Ubuntu 24.04.4 LTS", kernelVersion: "6.8.0-100-generic" },
};

/** Run the installer's operator-facing check against recorded API responses, with real Helm parsing. */
function checkKubelet(
	live: Record<string, unknown>,
	status: Record<string, unknown> = nodeStatus,
	args: readonly string[] = [],
	settlesAfterRestart = false,
) {
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-kubelet-"));
	try {
		writeFileSync(path.join(directory, "configz.json"), JSON.stringify({ kubeletconfig: live }));
		writeFileSync(
			path.join(directory, "nodes.json"),
			JSON.stringify({ items: [{ metadata: { name: "one-host" }, status }] }),
		);
		if (settlesAfterRestart) {
			writeFileSync(path.join(directory, "settling"), "");
			writeFileSync(
				path.join(directory, "stale-nodes.json"),
				JSON.stringify({
					items: [
						{ metadata: { name: "one-host" }, status: { ...status, allocatable: status.capacity } },
					],
				}),
			);
		}
		writeFileSync(path.join(directory, "sleep"), "#!/bin/sh\nexit 0\n", { mode: 0o755 });
		const calls = path.join(directory, "calls");
		writeFileSync(calls, "");
		writeFileSync(
			path.join(directory, "kubectl"),
			`#!/bin/sh
set -eu
printf '%s\\n' "$*" >> "$KUBELET_FIXTURE/calls"
case "$*" in
'get nodes -o json')
if [ -f "$KUBELET_FIXTURE/settling" ]; then cat "$KUBELET_FIXTURE/stale-nodes.json"; exit 0; fi
cat "$KUBELET_FIXTURE/nodes.json" ;;
'get --raw /api/v1/nodes/one-host/proxy/configz')
if [ -f "$KUBELET_FIXTURE/settling" ]; then
 if [ ! -f "$KUBELET_FIXTURE/body-ready" ]; then touch "$KUBELET_FIXTURE/body-ready"; exit 1; fi
 rm "$KUBELET_FIXTURE/settling"
fi
cat "$KUBELET_FIXTURE/configz.json" ;;
*) echo "unexpected cluster command" >&2; exit 1 ;;
esac
`,
			{ mode: 0o755 },
		);
		const result = spawnSync(
			"sh",
			[path.join(PLATFORM, "k3s-install.sh"), "--verify-kubelet", ...args],
			{
				encoding: "utf8",
				timeout: 30_000,
				env: {
					...process.env,
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
					KUBELET_FIXTURE: directory,
				},
			},
		);
		return { ...result, calls: readFileSync(calls, "utf8").trim().split("\n").filter(Boolean) };
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
}

await test("the operator can repeat the effective-policy check without a cluster mutation", () => {
	const [wanted] = documents(read("k3s-kubelet.yaml"));
	assert.ok(wanted);
	for (let run = 0; run < 2; run += 1) {
		const result = checkKubelet({
			...wanted,
			address: "0.0.0.0",
			authentication: { anonymous: { enabled: false } },
		});
		assert.equal(result.status, 0, result.stderr);
		assert.match(result.stdout, /Effective kubelet policy and node allocatable match/u);
		assert.deepEqual(result.calls, [
			"get nodes -o json",
			"get --raw /api/v1/nodes/one-host/proxy/configz",
		]);
	}
});

await test("an overridden or absent kubelet budget stops the upgrade check", () => {
	const [wanted] = documents(read("k3s-kubelet.yaml"));
	assert.ok(wanted);
	for (const [key, value] of [
		["kubeReserved", null],
		["evictionSoft", { "memory.available": "1Gi" }],
		["failSwapOn", false],
		["enforceNodeAllocatable", ["pods", "system-reserved"]],
		["imageGCHighThresholdPercent", 85],
		["containerLogMaxFiles", 20],
	] as const) {
		const result = checkKubelet({ ...wanted, [key]: value });
		assert.notEqual(result.status, 0, key);
		assert.match(result.stderr, /effective kubelet policy differs/u);
		assert.ok(result.stderr.includes(key), result.stderr);
		assert.match(result.stderr, /did not converge after 31 attempts/u);
		assert.equal(result.calls.filter((call) => call === "get nodes -o json").length, 31);
	}
});

await test("allocatable matches measured capacity less reserves and the hard floor", () => {
	const [wanted] = documents(read("k3s-kubelet.yaml"));
	assert.ok(wanted);
	const capacity = { memory: "16Gi", cpu: "4000m" };
	const correct = { memory: "14592Mi", cpu: "3.5" };
	assert.equal(checkKubelet(wanted, { ...nodeStatus, capacity, allocatable: correct }).status, 0);
	for (const allocatable of [
		{ ...correct, memory: "16Gi" },
		{ ...correct, memory: "13568Mi" },
		{ ...correct, cpu: "4" },
		{ cpu: "3500m" },
	]) {
		const result = checkKubelet(wanted, { ...nodeStatus, capacity, allocatable });
		assert.notEqual(result.status, 0);
		assert.match(result.stderr, /node allocatable does not enforce/u);
	}
});

await test("soft eviction cannot be re-enabled through a startup override", () => {
	const [wanted] = documents(read("k3s-kubelet.yaml"));
	assert.ok(wanted);
	for (const key of ["evictionSoft", "evictionSoftGracePeriod"]) {
		for (const value of [null, {}]) {
			const allowed = checkKubelet({ ...wanted, [key]: value });
			assert.equal(allowed.status, 0, allowed.stderr);
		}
		const refused = checkKubelet({ ...wanted, [key]: { "memory.available": "1Gi" } });
		assert.notEqual(refused.status, 0);
		assert.match(refused.stderr, /effective kubelet policy differs/u);
	}
});

await test("an unknown host requires explicit qualification override without skipping resource checks", () => {
	const [wanted] = documents(read("k3s-kubelet.yaml"));
	assert.ok(wanted);
	for (const nodeInfo of [
		{ osImage: "Ubuntu 26.04.1 LTS", kernelVersion: "7.0.0-34-generic" },
		{ osImage: "Ubuntu 24.04.4 LTS", kernelVersion: "7.0.0-34-generic" },
		{ osImage: "Debian GNU/Linux 13", kernelVersion: "6.8.0-100-generic" },
		{},
	]) {
		const status = { ...nodeStatus, nodeInfo };
		const refused = checkKubelet(wanted, status);
		assert.notEqual(refused.status, 0);
		assert.match(refused.stderr, /unqualified host/u);
		const allowed = checkKubelet(wanted, status, ["--allow-unqualified-host"]);
		assert.equal(allowed.status, 0, allowed.stderr);
		assert.match(allowed.stderr, /explicit host qualification override/u);
		const badPolicy = checkKubelet({ ...wanted, kubeReserved: null }, status, [
			"--allow-unqualified-host",
		]);
		assert.notEqual(badPolicy.status, 0);
		assert.match(badPolicy.stderr, /effective kubelet policy differs/u);
	}
});

await test("verification waits for configz and refreshed allocatable after restart", () => {
	const [wanted] = documents(read("k3s-kubelet.yaml"));
	assert.ok(wanted);
	const result = checkKubelet(wanted, nodeStatus, [], true);
	assert.equal(result.status, 0, result.stderr);
	assert.match(result.stdout, /Effective kubelet policy and node allocatable match/u);
	assert.equal(result.calls.filter((call) => call === "get nodes -o json").length, 3);
	assert.equal(
		result.calls.filter((call) => call === "get --raw /api/v1/nodes/one-host/proxy/configz").length,
		3,
	);
});

await test("repeat installs require an explicit certificate choice before changing the host", () => {
	const directory = mkdtempSync(path.join(tmpdir(), "hephaestus-repeat-install-"));
	try {
		cpSync(PLATFORM, directory, { recursive: true });
		mkdirSync(path.join(directory, "bin"));
		// A regressed guard must never reach a real sudo during a unit test.
		writeFileSync(path.join(directory, "bin", "sudo"), "#!/bin/sh\nexit 97\n", { mode: 0o755 });
		for (const enabled of [true, false]) {
			writeFileSync(
				path.join(directory, "site.yaml"),
				`workspaceSubdomains:\n  enabled: ${String(enabled)}\n`,
			);
			const args = ["--host", "heph.example.org", "--email", "ops@example.org"];
			const implicit = installerAt(directory, ...args);
			assert.notEqual(implicit.status, 0);
			assert.match(implicit.stderr, /site.yaml exists: choose/u);
			assert.deepEqual(implicit.resources, []);
			const wildcard = renderedAt(
				directory,
				...args,
				"--workspace-subdomains",
				"--dns01-issuer",
				"zone-dns",
			);
			assert.equal(wildcard.status, 0, wildcard.stderr);
			assert.deepEqual(
				named(wildcard.resources, "Certificate", "hephaestus-tls")?.spec,
				named(
					rendered(...args, "--workspace-subdomains", "--dns01-issuer", "zone-dns").resources,
					"Certificate",
					"hephaestus-tls",
				)?.spec,
			);
			const customFile = path.join(directory, "saved-certificates.yaml");
			writeFileSync(
				customFile,
				wildcard.resources.map((resource) => JSON.stringify(resource)).join("\n---\n"),
				{ mode: 0o600 },
			);
			const custom = renderedAt(directory, ...args, "--certificates", customFile);
			assert.equal(custom.status, 0, custom.stderr);
			assert.deepEqual(custom.resources, wildcard.resources);
			const standard = renderedAt(directory, ...args, "--standard-certificates");
			assert.equal(standard.status, 0, standard.stderr);
			const issuer = named(standard.resources, "ClusterIssuer", "gateway");
			assert.ok(issuer && isRecord(issuer.spec) && isRecord(issuer.spec.acme));
			assert.equal(issuer.spec.acme.server, "https://acme-v02.api.letsencrypt.org/directory");
			const preview = renderedAt(directory, ...args);
			assert.equal(preview.status, 0, preview.stderr);
			assert.deepEqual(preview.resources, standard.resources);
			assert.notEqual(
				renderedAt(directory, ...args, "--standard-certificates", "--certificates", customFile)
					.status,
				0,
			);
		}
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
});
