import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { asArray, asRecord } from "./lib/json.ts";

void test("image provenance can persist storage records through every reusable-workflow caller", async () => {
	const callers = {
		"cicd.yml": ["application-server-image", "Docker"],
		"ci-docker-build.yml": ["webapp-build", "agent-pi-build", "postgres-build"],
		"reusable-docker-build.yml": ["build", "merge"],
	};
	for (const [file, jobs] of Object.entries(callers)) {
		const workflow = parseDocument(await readFile(`.github/workflows/${file}`, "utf8"));
		const permissions = workflow.get("permissions");
		assert.ok(isMap(permissions));
		assert.deepEqual(permissions.toJSON(), {});
		for (const job of jobs) {
			for (const permission of ["id-token", "attestations", "artifact-metadata"]) {
				assert.equal(
					workflow.getIn(["jobs", job, "permissions", permission]),
					"write",
					`${file}: ${job}: ${permission}`,
				);
			}
		}
		if (file !== "reusable-docker-build.yml") continue;
		for (const job of jobs) {
			const steps = workflow.getIn(["jobs", job, "steps"]);
			assert.ok(isSeq(steps));
			const attestation = asArray(steps.toJSON(), "steps")
				.map((step) => asRecord(step, "step"))
				.find((step) => step.name === "Generate build provenance attestation");
			assert.ok(attestation);
			assert.match(String(attestation.uses), /^actions\/attest@[a-f0-9]{40}$/);
			const inputs = asRecord(attestation.with, "attestation inputs");
			assert.equal(inputs["push-to-registry"], true);
			assert.notEqual(inputs["create-storage-record"], false);
			assert.match(String(inputs["subject-digest"]), /outputs\.(manifest-digest|digest)/);
		}
	}
});

void test("artifact checks have no registry publishing permissions", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const permissions = workflow.getIn(["jobs", "Build", "permissions"]);
	assert.ok(isMap(permissions));
	assert.deepEqual(permissions.toJSON(), { contents: "read", checks: "write" });
});

const repository = "hephaestus-build/Hephaestus";
const owner = "hephaestus-build";
const digest = `sha256:${"a".repeat(64)}`;
const image = `ghcr.io/${owner}/webapp`;
const immutable = `${image}@${digest}`;
const verificationSites = [
	{ file: "reusable-docker-build.yml", job: "build", step: "Sign and verify image" },
	{ file: "reusable-docker-build.yml", job: "merge", step: "Verify signature + attestation" },
	{
		file: "ci-docker-build.yml",
		job: "tag-unchanged-images",
		step: "Verify and tag unchanged images",
	},
];

// These are Linux publishing jobs. Execute their actual shell bodies with tool-boundary fixtures
// so different quoting or function control flow cannot silently weaken one verification site.
for (const site of verificationSites) {
	for (const failure of ["none", "signature", "attestation"]) {
		void test(
			`${site.job}: ${failure === "none" ? "success" : `${failure} rejection`} preserves immutable-image verification`,
			{ skip: process.platform === "win32" },
			async (t) => {
				const workflow = parseDocument(await readFile(`.github/workflows/${site.file}`, "utf8"));
				const steps = workflow.getIn(["jobs", site.job, "steps"]);
				assert.ok(isSeq(steps));
				const step: unknown = steps.items.find(
					(item) => isMap(item) && item.get("name") === site.step,
				);
				assert.ok(isMap(step));
				if (site.job === "tag-unchanged-images") {
					assert.equal(step.getIn(["env", "REPOSITORY"]), `\${{ github.repository }}`);
					assert.equal(step.getIn(["env", "REPOSITORY_OWNER"]), `\${{ github.repository_owner }}`);
					assert.equal(step.getIn(["env", "ALIAS_BASE"]), `\${{ inputs.alias_base }}`);
				} else {
					const source = site.job === "build" ? "single-digest" : "digest";
					assert.equal(
						step.getIn(["env", "IMAGE_REF"]),
						`\${{ inputs.registry }}/\${{ inputs.image-name }}@\${{ steps.${source}.outputs.manifest-digest }}`,
					);
				}
				const script = String(step.get("run"))
					.replaceAll(`\${{ github.repository }}`, repository)
					.replaceAll(`\${{ github.repository_owner }}`, owner);
				assert.ok(!script.includes(`\${{`));
				const directory = await mkdtemp(join(tmpdir(), "image-verification-"));
				t.after(() => rm(directory, { recursive: true, force: true }));
				const log = join(directory, "calls");
				await writeFile(log, "");
				for (const tool of ["cosign", "gh", "docker"]) {
					const file = join(directory, tool);
					await writeFile(
						file,
						`#!/usr/bin/env node
const fs = require('node:fs');
const tool = require('node:path').basename(process.argv[1]);
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify([tool, ...args]) + '\\n');
if (tool === 'cosign' && args[0] === 'verify' && process.env.FAILURE === 'signature') process.exit(7);
if (tool === 'gh' && process.env.FAILURE === 'attestation') process.exit(9);
if (tool === 'docker' && args.includes('inspect')) process.stdout.write(process.env.DIGEST);
`,
					);
					await chmod(file, 0o755);
				}
				const result = spawnSync("bash", ["-e", "-c", script], {
					encoding: "utf8",
					env: {
						...process.env,
						PATH: `${directory}${delimiter}${process.env.PATH ?? ""}`,
						CALL_LOG: log,
						FAILURE: failure,
						DIGEST: digest,
						IMAGE_REF: immutable,
						REPOSITORY: repository,
						REPOSITORY_OWNER: owner,
						ALIAS_BASE: "b".repeat(40),
						HEAD_SHA: "c".repeat(40),
						PR_NUMBER: "42",
						WEBAPP_CHANGED: "false",
						APPLICATION_SERVER_CHANGED: "true",
						AGENT_IMAGES_CHANGED: "true",
						POSTGRES_IMAGE_CHANGED: "true",
					},
				});
				const calls = (await readFile(log, "utf8")).trim().split("\n");
				const expected = [];
				if (site.job === "build") expected.push(["cosign", "sign", "--yes", immutable]);
				if (site.job === "tag-unchanged-images")
					expected.push([
						"docker",
						"buildx",
						"imagetools",
						"inspect",
						"--format",
						"{{.Manifest.Digest}}",
						`${image}:${"b".repeat(40)}`,
					]);
				expected.push([
					"cosign",
					"verify",
					immutable,
					"--certificate-identity-regexp",
					`^https://github\\.com/${repository}/\\.github/workflows/reusable-docker-build\\.yml@`,
					"--certificate-oidc-issuer",
					"https://token.actions.githubusercontent.com",
					"--certificate-github-workflow-repository",
					repository,
				]);
				if (failure !== "signature")
					expected.push(["gh", "attestation", "verify", `oci://${immutable}`, "--owner", owner]);
				if (failure === "none" && site.job === "tag-unchanged-images")
					expected.push([
						"docker",
						"buildx",
						"imagetools",
						"create",
						"--tag",
						`${image}:${"c".repeat(40)}`,
						"--tag",
						`${image}:pr-42`,
						immutable,
					]);
				assert.deepEqual(
					calls,
					expected.map((args) => JSON.stringify(args)),
				);
				assert.equal(
					result.status,
					failure === "none" ? 0 : failure === "signature" ? 7 : 9,
					result.stderr,
				);
			},
		);
	}
}

void test("registry-only merge and alias jobs remain checkout-free and signing keeps its workflow identity", async () => {
	for (const site of verificationSites.filter((candidate) => candidate.job !== "build")) {
		const workflow = parseDocument(await readFile(`.github/workflows/${site.file}`, "utf8"));
		const steps = workflow.getIn(["jobs", site.job, "steps"]);
		assert.ok(isSeq(steps));
		for (const step of steps.items)
			if (isMap(step)) {
				assert.doesNotMatch(String(step.get("uses")), /actions\/checkout@|^\.\//);
			}
	}
	const workflow = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	for (const job of ["build", "merge"]) {
		const steps = workflow.getIn(["jobs", job, "steps"]);
		assert.ok(isSeq(steps));
		const sign: unknown = steps.items.find(
			(item) =>
				isMap(item) &&
				item.get("name") === (job === "build" ? "Sign and verify image" : "Sign image with cosign"),
		);
		assert.ok(isMap(sign));
		assert.match(
			String(sign.get("run")),
			job === "merge" ? /cosign sign --yes --recursive/ : /cosign sign --yes/,
		);
		if (job === "build") assert.doesNotMatch(String(sign.get("run")), /--recursive/);
	}
});
