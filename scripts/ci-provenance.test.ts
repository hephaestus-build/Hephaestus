import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { asArray, asRecord } from "./lib/json.ts";

void test("image provenance can persist storage records through every reusable-workflow caller", async () => {
	const callers = {
		"cicd.yml": ["application-server-image", "Docker"],
		"ci-docker-build.yml": ["webapp-build", "agent-pi-build", "postgres-build"],
		"reusable-docker-build.yml": ["merge"],
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
		if (file !== "reusable-docker-build.yml") {
			continue;
		}
		// A platform build only pushes by digest; the merge job alone signs and attests.
		const build = workflow.getIn(["jobs", "build", "permissions"]);
		assert.ok(isMap(build));
		assert.deepEqual(build.toJSON(), { contents: "read", packages: "write" });
		for (const job of jobs) {
			const steps = workflow.getIn(["jobs", job, "steps"]);
			assert.ok(isSeq(steps));
			const attestation = asArray(steps.toJSON(), "steps")
				.map((step) => asRecord(step, "step"))
				.find((step) => step.name === "Generate build provenance attestation");
			assert.ok(attestation);
			assert.match(String(attestation.uses), /^actions\/attest@[a-f0-9]{40}$/u);
			const inputs = asRecord(attestation.with, "attestation inputs");
			assert.equal(inputs["push-to-registry"], true);
			assert.notEqual(inputs["create-storage-record"], false);
			assert.match(String(inputs["subject-digest"]), /outputs\.(?:manifest-digest|digest)/u);
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
	{ file: "reusable-docker-build.yml", job: "merge", step: "Verify signature + attestation" },
	{
		file: "ci-docker-build.yml",
		job: "tag-unchanged-images",
		step: "Verify and tag unchanged images",
	},
];

/** The status the script must end with when a fixture tool rejects the image, by failing tool. */
const exitCodes = { none: 0, signature: 7, attestation: 9 };

// These are Linux publishing jobs. Execute their actual shell bodies with tool-boundary fixtures
// so different quoting or function control flow cannot silently weaken one verification site.
for (const site of verificationSites) {
	const failures =
		site.job === "tag-unchanged-images"
			? exitCodes
			: {
					...exitCodes,
					publication: 11,
					changedDigest: 1,
					emptyTags: 4,
					invalidMetadata: 5,
				};
	for (const [failure, exitCode] of Object.entries(failures)) {
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
					assert.equal(
						step.getIn(["env", "IMAGE_REF"]),
						`\${{ inputs.registry }}/\${{ inputs.image-name }}@\${{ steps.digest.outputs.manifest-digest }}`,
					);
				}
				const publication: unknown = steps.items.find(
					(item) => isMap(item) && item.get("name") === "Publish verified image tags",
				);
				if (site.job !== "tag-unchanged-images") {
					assert.ok(isMap(publication));
					assert.ok(steps.items.indexOf(publication) > steps.items.indexOf(step));
					assert.equal(publication.getIn(["env", "IMAGE_REF"]), step.getIn(["env", "IMAGE_REF"]));
				}
				const script = (
					String(step.get("run")) + (isMap(publication) ? String(publication.get("run")) : "")
				)
					.replaceAll(`\${{ github.repository }}`, repository)
					.replaceAll(`\${{ github.repository_owner }}`, owner);
				assert.ok(!script.includes(`\${{`));
				const directory = await mkdtemp(path.join(tmpdir(), "image-verification-"));
				t.after(async () => rm(directory, { recursive: true, force: true }));
				const log = path.join(directory, "calls");
				await writeFile(log, "");
				for (const tool of ["cosign", "gh", "docker"]) {
					const file = path.join(directory, tool);
					await writeFile(
						file,
						`#!/usr/bin/env node
const fs = require('node:fs');
const tool = require('node:path').basename(process.argv[1]);
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify([tool, ...args]) + '\\n');
if (tool === 'cosign' && args[0] === 'verify' && process.env.FAILURE === 'signature') process.exit(7);
if (tool === 'gh' && process.env.FAILURE === 'attestation') process.exit(9);
if (tool === 'docker' && args.includes('create')) {
  if (process.env.FAILURE === 'publication') process.exit(11);
  const index = args.indexOf('--metadata-file');
  if (index !== -1) fs.writeFileSync(args[index + 1], JSON.stringify({
    'containerimage.descriptor': { digest: process.env.FAILURE === 'changedDigest' ? 'sha256:' + 'd'.repeat(64) : process.env.DIGEST }
  }));
}
if (tool === 'docker' && args.includes('inspect')) process.stdout.write(process.env.DIGEST);
`,
					);
					await chmod(file, 0o755);
				}
				const result = spawnSync("bash", ["-e", "-c", script], {
					encoding: "utf8",
					env: {
						...process.env,
						PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
						CALL_LOG: log,
						FAILURE: failure,
						DIGEST: digest,
						RUNNER_TEMP: directory,
						DOCKER_METADATA_OUTPUT_JSON:
							failure === "invalidMetadata"
								? "{"
								: JSON.stringify({
										tags: failure === "emptyTags" ? [] : [`${image}:commit`, `${image}:ci`],
									}),
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
				const recorded = await readFile(log, "utf8");
				const calls = recorded.trim().split("\n");
				const expected = [];
				if (site.job === "tag-unchanged-images") {
					expected.push([
						"docker",
						"buildx",
						"imagetools",
						"inspect",
						"--format",
						"{{.Manifest.Digest}}",
						`${image}:${"b".repeat(40)}`,
					]);
				}
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
				if (failure !== "signature") {
					expected.push([
						"gh",
						"attestation",
						"verify",
						`oci://${immutable}`,
						"--bundle-from-oci",
						"--owner",
						owner,
					]);
				}
				if (
					site.job !== "tag-unchanged-images" &&
					failure !== "signature" &&
					failure !== "attestation" &&
					failure !== "emptyTags" &&
					failure !== "invalidMetadata"
				) {
					expected.push([
						"docker",
						"buildx",
						"imagetools",
						"create",
						"--prefer-index=false",
						"--tag",
						`${image}:commit`,
						"--tag",
						`${image}:ci`,
						"--metadata-file",
						path.join(directory, "published-image.json"),
						immutable,
					]);
				}
				if (failure === "none" && site.job === "tag-unchanged-images") {
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
				}
				assert.deepEqual(
					calls,
					expected.map((args) => JSON.stringify(args)),
				);
				assert.equal(result.status, exitCode, result.stderr);
			},
		);
	}
}

void test("registry-only merge and alias jobs remain checkout-free and signing keeps its workflow identity", async () => {
	for (const site of verificationSites) {
		const workflow = parseDocument(await readFile(`.github/workflows/${site.file}`, "utf8"));
		const steps = workflow.getIn(["jobs", site.job, "steps"]);
		assert.ok(isSeq(steps));
		for (const step of steps.items) {
			if (isMap(step)) {
				assert.doesNotMatch(String(step.get("uses")), /actions\/checkout@|^\.\//u);
			}
		}
	}
	const workflow = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	const steps = workflow.getIn(["jobs", "merge", "steps"]);
	assert.ok(isSeq(steps));
	const sign: unknown = steps.items.find(
		(item) => isMap(item) && item.get("name") === "Sign image with cosign",
	);
	assert.ok(isMap(sign));
	// --recursive signs each platform leaf as well as the index.
	assert.match(String(sign.get("run")), /cosign sign --yes --recursive/u);
});

for (const [captured, reused] of [
	[digest, ""],
	["invalid", ""],
	[null, ""],
	[null, digest],
	["invalid", digest],
	[digest, "invalid"],
] as const) {
	const valid = (reused || captured) === digest;
	void test(`${reused === "" ? "merged" : "reused"} ${valid ? "valid" : "invalid or missing"} publication subject`, async (t) => {
		const workflow = parseDocument(
			await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
		);
		const steps = workflow.getIn(["jobs", "merge", "steps"]);
		assert.ok(isSeq(steps));
		const capture: unknown = steps.items.find(
			(item) => isMap(item) && item.get("name") === "Capture manifest digest",
		);
		assert.ok(isMap(capture));
		const directory = await mkdtemp(path.join(tmpdir(), "publication-subject-"));
		t.after(async () => rm(directory, { recursive: true, force: true }));
		const output = path.join(directory, "output");
		await writeFile(output, "");
		const descriptor = { "containerimage.descriptor": { digest: captured } };
		await writeFile(path.join(directory, "merged-image.json"), JSON.stringify(descriptor));
		const result = spawnSync("bash", ["-e", "-c", String(capture.get("run"))], {
			encoding: "utf8",
			env: { ...process.env, RUNNER_TEMP: directory, GITHUB_OUTPUT: output, REUSED_DIGEST: reused },
		});
		// A reused digest is the subject on its own; a build's merge output cannot replace it.
		assert.equal(result.status === 0, valid, result.stderr);
		assert.equal(await readFile(output, "utf8"), valid ? `manifest-digest=${digest}\n` : "");
	});
}

void test("canonical tags are withheld until verification for both image builders", async () => {
	const workflow = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	// A platform build pushes by digest alone; only the verified index is ever tagged.
	const buildSteps = workflow.getIn(["jobs", "build", "steps"]);
	assert.ok(isSeq(buildSteps));
	const build = asArray(buildSteps.toJSON(), "steps").map((step) => asRecord(step, "step"));
	assert.equal(
		build.some((step) => step.name === "Publish verified image tags"),
		false,
	);
	const dockerfile = build.find((step) => step.name === "Build and push (Dockerfile)");
	assert.ok(dockerfile);
	const inputs = asRecord(dockerfile.with, "build inputs");
	assert.equal(inputs.tags, undefined);
	assert.match(String(inputs.outputs), /inputs\.publish && format/u);
	assert.match(String(inputs.outputs), /push-by-digest=true/u);

	const mergeSteps = workflow.getIn(["jobs", "merge", "steps"]);
	assert.ok(isSeq(mergeSteps));
	const values = asArray(mergeSteps.toJSON(), "steps").map((step) => asRecord(step, "step"));
	const publication = values.findIndex((step) => step.name === "Publish verified image tags");
	const verification = values.findIndex((step) => step.name === "Verify signature + attestation");
	assert.ok(publication > verification && verification !== -1);
	const staging = values.find((step) => step.name === "Create manifest and push");
	assert.ok(staging);
	assert.ok(values.indexOf(staging) < publication);
	const env = asRecord(staging.env, "staging environment");
	assert.match(String(env.STAGING_IMAGE), /github\.run_id.*github\.run_attempt/u);
	assert.doesNotMatch(String(staging.run), /DOCKER_METADATA_OUTPUT_JSON/u);
});

const armLeaf = `sha256:${"b".repeat(64)}`;
const buildIndex = `sha256:${"c".repeat(64)}`;
const mergedIndex = `sha256:${"d".repeat(64)}`;
const platformLeaf = (architecture: string, selected: string) => ({
	platform: { os: "linux", architecture },
	digest: selected,
});
const finalLeaves = [platformLeaf("arm64", armLeaf), platformLeaf("amd64", digest)];
for (const scenario of [
	{ name: "Dockerfile index and Buildpacks leaf match the final index", final: finalLeaves },
	{
		name: "changed final leaf",
		final: [platformLeaf("amd64", buildIndex), platformLeaf("arm64", armLeaf)],
		refused: true,
	},
	{ name: "missing final platform", final: [platformLeaf("amd64", digest)], refused: true },
	{
		name: "duplicate final platform",
		final: [...finalLeaves, platformLeaf("amd64", digest)],
		refused: true,
	},
	{
		name: "unexpected real platform",
		final: [...finalLeaves, platformLeaf("386", digest)],
		refused: true,
	},
	{
		name: "source index missing its platform",
		final: finalLeaves,
		source: [platformLeaf("arm64", digest)],
		refused: true,
		beforeCreate: true,
	},
	{
		name: "source index duplicates its platform",
		final: finalLeaves,
		source: [platformLeaf("amd64", digest), platformLeaf("amd64", digest)],
		refused: true,
		beforeCreate: true,
	},
	{
		name: "source leaf has malformed digest",
		final: finalLeaves,
		source: [platformLeaf("amd64", "bad")],
		refused: true,
		beforeCreate: true,
	},
	{
		name: "missing build artifact",
		final: finalLeaves,
		artifact: "missing",
		refused: true,
		beforeCreate: true,
	},
	{
		name: "multiple build digest files",
		final: finalLeaves,
		artifact: "multiple",
		refused: true,
		beforeCreate: true,
	},
	{
		name: "malformed build digest filename",
		final: finalLeaves,
		artifact: "malformed",
		refused: true,
		beforeCreate: true,
	},
	{
		name: "extra artifact directory",
		final: finalLeaves,
		artifact: "extra",
		refused: true,
		beforeCreate: true,
	},
	{
		name: "a pull request's amd64-only index",
		platforms: ["linux/amd64"],
		artifactArchitectures: ["amd64"],
		final: [platformLeaf("amd64", digest)],
	},
	{
		name: "an arm64 build the run did not request",
		platforms: ["linux/amd64"],
		final: [platformLeaf("amd64", digest)],
		refused: true,
		beforeCreate: true,
	},
	{
		name: "an amd64-only index where both platforms were requested",
		artifactArchitectures: ["amd64"],
		final: [platformLeaf("amd64", digest)],
		refused: true,
		beforeCreate: true,
	},
	{
		name: "an unsupported requested platform",
		platforms: ["linux/386"],
		artifactArchitectures: ["386"],
		final: [platformLeaf("386", digest)],
		refused: true,
		beforeCreate: true,
	},
	{
		name: "an empty platform list",
		platforms: [],
		artifactArchitectures: [],
		final: [],
		refused: true,
		beforeCreate: true,
	},
] as const) {
	const platforms: readonly string[] = ("platforms" in scenario
		? scenario.platforms
		: undefined) ?? ["linux/amd64", "linux/arm64"];
	const architectures: readonly string[] = ("artifactArchitectures" in scenario
		? scenario.artifactArchitectures
		: undefined) ?? ["amd64", "arm64"];
	void test(
		`manifest leaf custody: ${scenario.name}`,
		{ skip: process.platform === "win32" },
		async (t) => {
			const workflow = parseDocument(
				await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
			);
			const steps = workflow.getIn(["jobs", "merge", "steps"]);
			assert.ok(isSeq(steps));
			const create = steps.items.find(
				(step) => isMap(step) && step.get("name") === "Create manifest and push",
			);
			assert.ok(isMap(create));
			const directory = await mkdtemp(path.join(tmpdir(), "manifest-leaf-custody-"));
			t.after(async () => rm(directory, { recursive: true, force: true }));
			const artifactRoot = path.join(directory, "digests");
			await mkdir(artifactRoot);
			for (const arch of architectures) {
				const sourceDigest = arch === "arm64" ? armLeaf : buildIndex;
				const artifactDirectory = path.join(artifactRoot, `linux-${arch}`);
				await mkdir(artifactDirectory);
				if ("artifact" in scenario && scenario.artifact === "missing" && arch === "amd64") {
					continue;
				}
				const file =
					"artifact" in scenario && scenario.artifact === "malformed" && arch === "amd64"
						? "bad"
						: sourceDigest.slice(7);
				await writeFile(path.join(artifactDirectory, file), "");
				if ("artifact" in scenario && scenario.artifact === "multiple" && arch === "amd64") {
					await writeFile(path.join(artifactDirectory, digest.slice(7)), "");
				}
			}
			if ("artifact" in scenario && scenario.artifact === "extra") {
				await mkdir(path.join(artifactRoot, "unexpected"));
			}
			const docker = path.join(directory, "docker");
			await writeFile(
				docker,
				String.raw`#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify(args) + '\n');
if (args[2] === 'create') {
  const at = args.indexOf('--metadata-file');
  fs.writeFileSync(args[at + 1], JSON.stringify({'containerimage.descriptor': {digest: process.env.MERGED_DIGEST}}));
  process.exit(0);
}
const ref = args[3];
process.stdout.write(ref.endsWith(process.env.MERGED_DIGEST) ? process.env.FINAL_RAW : ref.endsWith(process.env.BUILD_INDEX) ? process.env.SOURCE_RAW : JSON.stringify({schemaVersion: 2, config: {}, layers: []}));
`,
			);
			await chmod(docker, 0o755);
			const callLog = path.join(directory, "calls");
			await writeFile(callLog, "");
			const result = spawnSync("bash", ["-e", "-c", String(create.get("run"))], {
				cwd: artifactRoot,
				encoding: "utf8",
				env: {
					...process.env,
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
					RUNNER_TEMP: directory,
					INPUTS_REGISTRY: "ghcr.io",
					INPUTS_IMAGE_NAME: "hephaestus-build/webapp",
					PLATFORMS: JSON.stringify(platforms),
					STAGING_IMAGE: `${image}:ci-test-index`,
					CALL_LOG: callLog,
					BUILD_INDEX: buildIndex,
					MERGED_DIGEST: mergedIndex,
					SOURCE_RAW: JSON.stringify({
						manifests:
							"source" in scenario
								? scenario.source
								: [platformLeaf("amd64", digest), platformLeaf("unknown", buildIndex)],
					}),
					FINAL_RAW: JSON.stringify({
						manifests: [
							...scenario.final,
							{ platform: { os: "unknown", architecture: "unknown" }, digest: buildIndex },
						],
					}),
				},
			});
			assert.equal(result.status === 0, !("refused" in scenario), result.stderr);
			const callText = await readFile(callLog, "utf8");
			const calls = callText
				.trim()
				.split("\n")
				.filter(Boolean)
				.map((line) => asArray(JSON.parse(line), "Docker arguments"));
			const created = calls.find((args) => args[2] === "create");
			assert.equal(created === undefined, "beforeCreate" in scenario);
			if (created !== undefined) {
				const sources = architectures.map(
					(arch) => `${image}@${arch === "arm64" ? armLeaf : buildIndex}`,
				);
				assert.deepEqual(created.slice(-sources.length), sources);
				assert.notEqual(created.at(-sources.length - 1), `${image}@${armLeaf}`);
				assert.deepEqual(calls.at(-1), [
					"buildx",
					"imagetools",
					"inspect",
					`${image}@${mergedIndex}`,
					"--raw",
				]);
			}
			assert.ok(
				steps.items.indexOf(create) <
					steps.items.findIndex(
						(step) => isMap(step) && step.get("name") === "Generate build provenance attestation",
					),
			);
		},
	);
}

const queueRun = "4242";
const pushCommit = "e".repeat(40);
for (const scenario of [
	{ name: "reuses the image its successful queue run built for this commit" },
	{ name: "builds when the commit has no queue image", missingTag: true },
	{ name: "builds when the queue image lacks a requested platform", leaves: ["amd64"] },
	{
		name: "builds when the queue image carries an unrequested platform",
		leaves: ["amd64", "arm64", "386"],
	},
	{ name: "builds when the signature is not the queue's", signature: false },
	{
		name: "builds when another run attested the image",
		run: "https://github.com/hephaestus-build/Hephaestus/actions/runs/1/attempts/1",
	},
	{ name: "builds when no attestation names the commit", attestations: [] },
	{ name: "builds when the run is not an identifier", queue: "4242/../1" },
] as const) {
	void test(
		`merge-queue reuse: ${scenario.name}`,
		{ skip: process.platform === "win32" },
		async (t) => {
			const workflow = parseDocument(
				await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
			);
			const reuseSteps = workflow.getIn(["jobs", "reuse", "steps"]);
			assert.ok(isSeq(reuseSteps));
			const step: unknown = reuseSteps.items.find(
				(item) => isMap(item) && item.get("name") === "Verify the merge-queue image",
			);
			assert.ok(isMap(step));
			const verify = String(step.get("run"))
				.replaceAll(`\${{ github.repository }}`, repository)
				.replaceAll(`\${{ github.repository_owner }}`, owner)
				.replaceAll(`\${{ github.server_url }}`, "https://github.com");
			assert.ok(!verify.includes(`\${{`));
			const directory = await mkdtemp(path.join(tmpdir(), "queue-reuse-"));
			t.after(async () => rm(directory, { recursive: true, force: true }));
			const log = path.join(directory, "calls");
			await writeFile(log, "");
			const leaves = ("leaves" in scenario ? scenario.leaves : undefined) ?? ["amd64", "arm64"];
			const attestations = ("attestations" in scenario ? scenario.attestations : undefined) ?? [
				{
					verificationResult: {
						signature: {
							certificate: {
								runInvocationURI:
									("run" in scenario ? scenario.run : undefined) ??
									`https://github.com/${repository}/actions/runs/${queueRun}/attempts/1`,
							},
						},
					},
				},
			];
			for (const tool of ["cosign", "gh", "docker"]) {
				const file = path.join(directory, tool);
				await writeFile(
					file,
					`#!/usr/bin/env node
const fs = require('node:fs');
const tool = require('node:path').basename(process.argv[1]);
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALL_LOG, JSON.stringify([tool, ...args]) + '\\n');
if (tool === 'docker' && args.includes('--format')) {
  if (process.env.MISSING_TAG === 'true') process.exit(1);
  process.stdout.write(JSON.stringify({ digest: process.env.DIGEST }));
} else if (tool === 'docker') {
  process.stdout.write(process.env.RAW);
} else if (tool === 'cosign' && process.env.SIGNATURE === 'false') {
  process.exit(7);
} else if (tool === 'gh') {
  process.stdout.write(process.env.ATTESTATIONS);
}
`,
				);
				await chmod(file, 0o755);
			}
			const output = path.join(directory, "output");
			await writeFile(output, "");
			const result = spawnSync("bash", ["-e", "-c", verify], {
				encoding: "utf8",
				env: {
					...process.env,
					PATH: `${directory}${path.delimiter}${process.env.PATH ?? ""}`,
					CALL_LOG: log,
					GITHUB_OUTPUT: output,
					IMAGE: image,
					COMMIT: pushCommit,
					QUEUE_RUN: "queue" in scenario ? scenario.queue : queueRun,
					PLATFORMS: JSON.stringify(["linux/amd64", "linux/arm64"]),
					DEFAULT_BRANCH: "main",
					DIGEST: digest,
					MISSING_TAG: String("missingTag" in scenario),
					SIGNATURE: String(!("signature" in scenario)),
					ATTESTATIONS: JSON.stringify(attestations),
					RAW: JSON.stringify({
						manifests: [
							...leaves.map((architecture) => platformLeaf(architecture, armLeaf)),
							{ platform: { os: "unknown", architecture: "unknown" }, digest: buildIndex },
						],
					}),
				},
			});
			const reused = Object.keys(scenario).length === 1;
			assert.equal(result.status === 0, reused, result.stderr);
			assert.equal(await readFile(output, "utf8"), reused ? `digest=${digest}\n` : "");
			if (reused) {
				const recorded = await readFile(log, "utf8");
				const calls = recorded
					.trim()
					.split("\n")
					.map((line) => asArray(JSON.parse(line), "call"));
				const cosign = calls.find((call) => call[0] === "cosign");
				assert.deepEqual(cosign, [
					"cosign",
					"verify",
					`${image}@${digest}`,
					"--certificate-identity-regexp",
					`^https://github\\.com/${repository}/\\.github/workflows/reusable-docker-build\\.yml@refs/heads/gh-readonly-queue/main/`,
					"--certificate-oidc-issuer",
					"https://token.actions.githubusercontent.com",
					"--certificate-github-workflow-repository",
					repository,
					"--certificate-github-workflow-trigger",
					"merge_group",
					"--certificate-github-workflow-sha",
					pushCommit,
				]);
				const attestation = calls.find((call) => call[0] === "gh");
				assert.ok(attestation !== undefined);
				assert.ok(attestation.includes("--source-digest") && attestation.includes(pushCommit));
			}
		},
	);
}

void test("a reused image skips only building, merging and attesting", async () => {
	const workflow = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	assert.equal(workflow.getIn(["jobs", "reuse", "if"]), "inputs.publish && inputs.reuse-run != ''");
	// Any unverified answer leaves the digest empty, and the image is built instead.
	const reuse = workflow.getIn(["jobs", "reuse", "steps"]);
	assert.ok(isSeq(reuse));
	const verify = reuse.items.find(
		(item) => isMap(item) && item.get("name") === "Verify the merge-queue image",
	);
	assert.ok(isMap(verify));
	assert.equal(verify.get("continue-on-error"), true);
	assert.equal(
		workflow.getIn(["jobs", "reuse", "outputs", "digest"]),
		`\${{ steps.verify.outputs.digest }}`,
	);
	assert.equal(workflow.getIn(["jobs", "build", "needs"]), "reuse");
	assert.equal(
		workflow.getIn(["jobs", "build", "if"]),
		`\${{ !cancelled() && needs.reuse.outputs.digest == '' }}`,
	);
	assert.equal(
		workflow.getIn(["jobs", "merge", "if"]),
		`\${{ inputs.publish && !cancelled() && (needs.reuse.outputs.digest != '' || needs.build.result == 'success') }}`,
	);
	// The queue run already scanned the image it built, in the build this skips, and its verdict
	// gated the merge.
	assert.equal(workflow.hasIn(["jobs", "scan"]), false);
	const steps = workflow.getIn(["jobs", "merge", "steps"]);
	assert.ok(isSeq(steps));
	const conditions = Object.fromEntries(
		asArray(steps.toJSON(), "steps")
			.map((step) => asRecord(step, "step"))
			.map((step) => [String(step.name), step.if]),
	);
	for (const skipped of [
		"Download digests",
		"Create manifest and push",
		"Generate build provenance attestation",
	]) {
		assert.equal(conditions[skipped], "needs.reuse.outputs.digest == ''", skipped);
	}
	// The release accepts only the default-branch identity, so a reused image is signed again.
	for (const always of [
		"Capture manifest digest",
		"Sign image with cosign",
		"Verify signature + attestation",
		"Publish verified image tags",
	]) {
		assert.equal(conditions[always], undefined, always);
	}
});
