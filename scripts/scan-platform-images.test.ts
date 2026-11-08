import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { asRecord } from "./lib/json.ts";

import {
	bundledSubjects,
	platformPins,
	renderedSubjects,
	sarifForCategory,
	verifyInstaller,
} from "./scan-platform-images.ts";

const source = await readFile("deploy/platform/k3s-platform.yaml", "utf8");

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
