import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { asRecord } from "./lib/json.ts";

import {
	agentSandboxPin,
	bundledSubjects,
	platformPins,
	renderedSubjects,
	runningBundledSubjects,
	sarifForCategory,
	verifyAgentSandbox,
	verifyGvisorSums,
	verifyIndexDigest,
	verifyInstaller,
} from "./scan-platform-images.ts";

const source = await readFile("deploy/platform/k3s-platform.yaml", "utf8");
const agentSandbox = await readFile("deploy/platform/k3s-agent-sandbox.yaml", "utf8");

void test("the platform rescan reads every add-on digest and the installed k3s version", () => {
	const { subjects, version } = platformPins(source);
	assert.deepEqual(
		subjects.map((subject) => subject.image),
		[
			"cert-manager-controller",
			"cert-manager-webhook",
			"cert-manager-cainjector",
			"cert-manager-acmesolver",
			"cert-manager-startupapicheck",
			"traefik",
			"k3s",
		],
	);
	for (const subject of subjects.filter((candidate) => candidate.image !== "k3s")) {
		assert.match(subject.reference, /@sha256:[a-f0-9]{64}$/u);
		assert.ok(source.includes(subject.reference.split("@")[1] ?? "missing"));
	}
	assert.equal(subjects.at(-1)?.reference, `docker.io/rancher/k3s:${version.replace("+", "-")}`);
	assert.throws(() => platformPins(source.replace(/^# pin: k3s .*$/mu, "")), /pin k3s/u);
	assert.throws(
		() => platformPins(source.replace(/sha256:[a-f0-9]{64}/u, "latest")),
		/immutable digest/u,
	);
});

void test("an installer change requires its independently verified checksum", () => {
	const installer = Buffer.from("reviewed installer");
	verifyInstaller(installer, createHash("sha256").update(installer).digest("hex"));
	assert.throws(
		() =>
			verifyInstaller(
				Buffer.from("different installer"),
				createHash("sha256").update(installer).digest("hex"),
			),
		/checksum does not match/u,
	);
});

void test("every SARIF subject has a distinct stable category and retains its findings", () => {
	const findings = {
		runs: [{ tool: { driver: { name: "Trivy" } }, results: [{ ruleId: "example" }] }],
	};
	const first = sarifForCategory(findings, "dependencies");
	const other = sarifForCategory(findings, "traefik-linux-arm64");
	assert.notEqual(first.runs[0]?.automationDetails.id, other.runs[0]?.automationDetails.id);
	assert.deepEqual(first, sarifForCategory(findings, "dependencies"));
	assert.deepEqual(asRecord(first.runs[0], "SARIF run").results, findings.runs[0]?.results);
	assert.throws(() => sarifForCategory({ runs: [] }, "dependencies"), /no SARIF runs/u);
});

void test("k3s runtime images come from its release inventory, with validated registry references", () => {
	assert.deepEqual(bundledSubjects("docker.io/rancher/mirrored-coredns-coredns:1.2.3\n"), [
		{
			image: "k3s-mirrored-coredns-coredns",
			repository: "docker.io/rancher/mirrored-coredns-coredns",
			reference: "docker.io/rancher/mirrored-coredns-coredns:1.2.3",
		},
	]);
	assert.throws(
		() => bundledSubjects("untrusted.example/image:latest"),
		/Invalid k3s image reference/u,
	);
});

void test("the rescan leaves out the bundled images of the add-ons that the host disables", () => {
	const bundled = bundledSubjects(
		[
			"docker.io/rancher/klipper-helm:v1",
			"docker.io/rancher/klipper-lb:v1",
			"docker.io/rancher/mirrored-coredns-coredns:1",
			"docker.io/rancher/mirrored-library-traefik:3",
		].join("\n"),
	);
	assert.deepEqual(
		runningBundledSubjects(bundled, "disable:\n  - traefik\n  - servicelb\n").map(
			(subject) => subject.image,
		),
		["k3s-klipper-helm", "k3s-mirrored-coredns-coredns"],
	);
	assert.throws(() => runningBundledSubjects(bundled, "disable:\n  - unknown\n"), /does not know/u);
});

void test("an added Pod image without a digest cannot escape the chart-to-scan check", () => {
	const digest = `sha256:${"a".repeat(64)}`;
	const render = (image: string) =>
		JSON.stringify({
			apiVersion: "v1",
			kind: "Pod",
			spec: {
				containers: [
					{
						name: "controller",
						image,
						args: [`--acme-http01-solver-image=registry.example/solver@${digest}`],
					},
				],
			},
		});
	assert.deepEqual(renderedSubjects(render(`registry.example/controller:v1@${digest}`)), [
		`registry.example/controller@${digest}`,
		`registry.example/solver@${digest}`,
	]);
	assert.throws(
		() => renderedSubjects(render("registry.example/controller:v1")),
		/without an immutable digest/u,
	);
});

void test("the agent-sandbox pin names its release, its manifest checksum and one immutable image", () => {
	const { version, manifestSha256, subject } = agentSandboxPin(agentSandbox);
	assert.match(version, /^v\d+\.\d+\.\d+$/u);
	assert.match(manifestSha256, /^[a-f0-9]{64}$/u);
	assert.equal(subject.image, "agent-sandbox-controller");
	assert.match(
		subject.reference,
		/^registry\.k8s\.io\/agent-sandbox\/agent-sandbox-controller@sha256:[a-f0-9]{64}$/u,
	);
	assert.throws(
		() => agentSandboxPin(agentSandbox.replace(/^# pin: agent-sandbox .*$/mu, "")),
		/pin agent-sandbox/u,
	);
	assert.throws(
		() => agentSandboxPin(agentSandbox.replace(/@sha256:[a-f0-9]{64}/u, "")),
		/immutable digest/u,
	);
});

/** A controller Deployment with extra pod spec lines, for the manifest comparison. */
const deployment = (extra: string, image: string) =>
	[
		"apiVersion: apps/v1",
		"kind: Deployment",
		"metadata: { name: agent-sandbox-controller }",
		"spec:",
		"  template:",
		"    spec:",
		extra,
		"      containers:",
		`      - { name: c, image: ${image}, args: [--extensions] }`,
		"",
	].join("\n");

void test("the platform's agent-sandbox manifest may differ from its release only in the stated fields", () => {
	const upstream = Buffer.from(deployment("", "registry.k8s.io/x:v1"));
	const pin = `# pin: agent-sandbox v1.0.0 ${createHash("sha256").update(upstream).digest("hex")}\n`;
	const local = (extra: string) =>
		pin + deployment(extra, `registry.k8s.io/x:v1@sha256:${"a".repeat(64)}`);
	verifyAgentSandbox(upstream, local("      priorityClassName: system-cluster-critical"));
	assert.throws(
		() => verifyAgentSandbox(upstream, local("      hostNetwork: true")),
		assert.AssertionError,
	);
	assert.throws(
		() => verifyAgentSandbox(Buffer.from(`${upstream.toString()}#`), local("")),
		/checksum does not match/u,
	);
});

void test("the controller digest must be the index that the release tag names", () => {
	const index = { manifests: [{ platform: { os: "linux", architecture: "arm64" }, digest: "x" }] };
	const pinned = `sha256:${"a".repeat(64)}`;
	verifyIndexDigest(index, pinned, pinned);
	assert.throws(
		() => verifyIndexDigest(index, `sha256:${"b".repeat(64)}`, pinned),
		/not the index of its release tag/u,
	);
	assert.throws(() => verifyIndexDigest({ layers: [] }, pinned, pinned), /no multi-platform/u);
});

const gvisorTemplate = (x64: string, arm64: string) => `# pin: gvisor 20261102.0 ${x64} ${arm64}\n`;

void test("the gVisor pin must name the checksums that its release publishes", () => {
	const [a, b] = ["a".repeat(128), "b".repeat(128)];
	const sums = `${a}  gvisor-x86_64.tar.bz2\n${b}  gvisor-aarch64.tar.bz2\n`;
	verifyGvisorSums(sums, gvisorTemplate(a, b));
	assert.throws(() => verifyGvisorSums(sums, gvisorTemplate(b, a)), /not those of its release/u);
	assert.throws(() => verifyGvisorSums(sums, "no pin"), /no gVisor pin/u);
});
