import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import {
	imageAttestations,
	imageManifestJob,
	resolveQueueRun,
	resolveServerBuild,
} from "./resolve-server-build.ts";

const repository = "hephaestus-build/Hephaestus";
const commit = "a".repeat(40);
const requiredJobs = [
	"App Server: Package",
	"Build / App Server: Generated artifacts",
	"Build / App Server: Database",
	"Build / App Server: PostgreSQL drills",
	"Build / Webapp: E2E",
	"Docker / Images / Build linux/amd64 images",
	"Docker / Images / Build linux/arm64 images",
	"Test / App Server: Unit and architecture",
	"Test / App Server: Integration (application)",
	"Test / App Server: Integration (providers)",
	"Test / App Server: Integration (runtime-and-startup)",
	"Test / App Server: Integration (practices-and-agent)",
	"Security / Dependencies, secrets, and policy",
	"CI Status Gate",
];
/** A successful manifest job whose attestation steps for `attested` succeeded. */
function manifests(attested: readonly string[], conclusion = "success") {
	return {
		name: imageManifestJob,
		conclusion,
		steps: attested.map((name) => ({ name, conclusion: "success" })),
	};
}
function fixture() {
	return {
		run: {
			id: 101,
			event: "merge_group",
			status: "completed",
			conclusion: "success",
			head_sha: commit,
			path: ".github/workflows/cicd.yml",
			head_branch: "gh-readonly-queue/main/pr-123-example",
			repository: { id: 7, full_name: repository },
			head_repository: { id: 7, full_name: repository },
		},
		jobs: [
			...[...requiredJobs, "Release evidence preflight"].map((name) => ({
				name,
				conclusion: "success",
			})),
			manifests([imageAttestations["application-server"]]),
		],
		artifact: {
			id: 202,
			name: "server-build-101",
			expired: false,
			digest: `sha256:${"b".repeat(64)}`,
			workflow_run: { id: 101, head_sha: commit, repository_id: 7, head_repository_id: 7 },
		},
	};
}
async function resolve(data = fixture()) {
	return resolveServerBuild(repository, commit, "main", async (path) => {
		if (path.includes("/workflows/")) {
			return { workflow_runs: [data.run] };
		}
		if (path.includes("/jobs?")) {
			assert.match(path, /filter=latest/u);
			return [{ jobs: data.jobs.slice(0, 4) }, { jobs: data.jobs.slice(4) }];
		}
		return [{ artifacts: [] }, { artifacts: [data.artifact] }];
	});
}

await test("selects the immutable artifact of an exact-commit successful release-candidate merge group", async () => {
	assert.deepEqual(await resolve(), { runId: 101, artifactId: 202 });
});

await test("reuses an ordinary merge group without the release-only evidence job", async () => {
	const data = fixture();
	data.jobs = data.jobs.map((job) =>
		job.name === "Release evidence preflight" ? { ...job, conclusion: "skipped" } : job,
	);
	assert.deepEqual(await resolve(data), { runId: 101, artifactId: 202 });
});

await test("rejects a different commit, repository, event, workflow, branch or incomplete run", async () => {
	for (const patch of [
		{ head_sha: "c".repeat(40) },
		{ event: "pull_request" },
		{ event: "push" },
		{ status: "in_progress" },
		{ conclusion: "failure" },
		{ path: ".github/workflows/other.yml" },
		{ head_branch: "gh-readonly-queue/other/pr-123" },
		{ repository: { id: 8, full_name: "fork/Hephaestus" } },
		{ head_repository: { id: 8, full_name: "fork/Hephaestus" } },
	]) {
		const data = fixture();
		Object.assign(data.run, patch);
		assert.equal(await resolve(data), undefined, JSON.stringify(patch));
	}
});

await test("every required artifact, test, security and final verdict must succeed", async () => {
	for (const missing of requiredJobs) {
		for (const conclusion of ["skipped", "failure", "cancelled", ""]) {
			const data = fixture();
			data.jobs = data.jobs.map((job) => (job.name === missing ? { ...job, conclusion } : job));
			assert.equal(await resolve(data), undefined, `${missing}: ${conclusion}`);
		}
		const absent = fixture();
		absent.jobs = absent.jobs.filter((job) => job.name !== missing);
		assert.equal(await resolve(absent), undefined, `${missing}: absent`);
	}
});

await test("a run from an earlier integration shard layout cannot approve reuse", async () => {
	for (const shards of [
		["providers-and-startup", "application"],
		["providers", "runtime-and-startup", "application"],
	]) {
		const data = fixture();
		data.jobs = [
			...data.jobs.filter((job) => !job.name.startsWith("Test / App Server: Integration (")),
			...shards.map((shard) => ({
				name: `Test / App Server: Integration (${shard})`,
				conclusion: "success",
			})),
		];
		assert.equal(await resolve(data), undefined, shards.join(","));
	}
});

await test("rejects expired, unhashed and foreign artifacts even when the run passed", async () => {
	for (const patch of [
		{ expired: true },
		{ digest: "" },
		{ name: "server-build-100" },
		{ workflow_run: { id: 100, head_sha: commit, repository_id: 7, head_repository_id: 7 } },
		{
			workflow_run: { id: 101, head_sha: "c".repeat(40), repository_id: 7, head_repository_id: 7 },
		},
		{ workflow_run: { id: 101, head_sha: commit, repository_id: 8, head_repository_id: 7 } },
		{ workflow_run: { id: 101, head_sha: commit, repository_id: 7, head_repository_id: 8 } },
	]) {
		const data = fixture();
		Object.assign(data.artifact, patch);
		assert.equal(await resolve(data), undefined, JSON.stringify(patch));
	}
});

await test("absence falls back and malformed metadata or an unavailable API cannot approve reuse", async () => {
	assert.equal(
		await resolveServerBuild(repository, commit, "main", async () => ({ workflow_runs: [] })),
		undefined,
	);
	assert.equal(
		await resolveServerBuild(repository, commit, "main", async () => ({ workflow_runs: [{}] })),
		undefined,
	);
	await assert.rejects(
		resolveServerBuild(repository, commit, "main", async () => {
			throw new Error("unavailable");
		}),
		/unavailable/u,
	);
	const data = fixture();
	data.artifact.id = 0;
	await assert.rejects(resolve(data), /invalid identifier/u);
});

await test("main reuses immutable verified artifacts without bypassing validation or the packaging fallback", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const steps = workflow.getIn(["jobs", "server-package", "steps"]);
	assert.ok(isSeq(steps));
	const step = (name: string) => {
		const found = steps.items.find((item) => isMap(item) && item.get("name") === name);
		assert.ok(isMap(found), `Missing package step: ${name}`);
		return found;
	};
	const lookup = step("Find the exact-commit merge-queue build");
	assert.equal(lookup.get("id"), "reuse");
	assert.equal(
		lookup.get("if"),
		"github.event_name == 'push' && github.ref == format('refs/heads/{0}', github.event.repository.default_branch)",
	);
	assert.equal(lookup.get("continue-on-error"), true);
	const download = step("Download the validated server build");
	assert.match(String(download.get("uses")), /^actions\/download-artifact@/u);
	assert.equal(download.get("if"), "steps.reuse.outputs.artifact-id != ''");
	assert.notEqual(download.get("continue-on-error"), true);
	assert.equal(download.getIn(["with", "artifact-ids"]), `\${{ steps.reuse.outputs.artifact-id }}`);
	assert.equal(download.getIn(["with", "run-id"]), `\${{ steps.reuse.outputs.run-id }}`);
	assert.equal(download.getIn(["with", "github-token"]), `\${{ github.token }}`);
	assert.equal(download.getIn(["with", "digest-mismatch"]), "error");
	assert.equal(download.getIn(["with", "path"]), "server");
	assert.equal(download.getIn(["with", "merge-multiple"]), true);
	assert.equal(download.hasIn(["with", "name"]), false);
	assert.equal(step("Package the server").get("if"), "steps.reuse.outputs.artifact-id == ''");
	const validate = step("Validate the packaged server");
	const upload = step("Upload the packaged server");
	for (const required of [validate, upload]) {
		assert.equal(required.has("if"), false);
		assert.notEqual(required.get("continue-on-error"), true);
	}
	assert.ok(steps.items.indexOf(validate) > steps.items.indexOf(download));
	assert.ok(steps.items.indexOf(upload) > steps.items.indexOf(validate));
	assert.match(String(upload.get("uses")), /^actions\/upload-artifact@/u);
	assert.equal(upload.getIn(["with", "name"]), `\${{ env.SERVER_BUILD_ARTIFACT }}`);
});

await test("a push reuses the images of the successful queue run of its exact commit", async () => {
	const lookup = async (data: { run: unknown; jobs: unknown[] } = fixture()) =>
		resolveQueueRun(repository, commit, "main", async (path) => {
			if (path.includes("/workflows/")) {
				return { workflow_runs: [data.run] };
			}
			return [{ jobs: data.jobs }];
		});
	assert.deepEqual(await lookup(), { runId: 101, images: ["application-server"] });
	// Its verdict covers every image job and scan, so the final gate is all it needs.
	const gateOnly = fixture();
	gateOnly.jobs = [{ name: "CI Status Gate", conclusion: "success" }];
	assert.deepEqual(await lookup(gateOnly), { runId: 101, images: [] });
	// Only the images whose index the run attested are reusable; it did not build the rest.
	const steps = [
		{ name: imageAttestations.webapp, conclusion: "success" },
		{ name: imageAttestations.postgres, conclusion: "skipped" },
		{ name: imageAttestations["agent-pi"], conclusion: "success" },
	];
	const published = fixture();
	const others = published.jobs.filter((job) => job.name !== imageManifestJob);
	assert.deepEqual(
		await lookup({
			run: published.run,
			jobs: [...others, { name: imageManifestJob, conclusion: "success", steps }],
		}),
		{ runId: 101, images: ["webapp", "agent-pi"] },
	);
	// A manifest job that did not succeed published nothing reusable, whatever its steps did.
	for (const conclusion of ["failure", "cancelled", "skipped"]) {
		assert.deepEqual(
			await lookup({
				run: published.run,
				jobs: [...others, { name: imageManifestJob, conclusion, steps }],
			}),
			{ runId: 101, images: [] },
			conclusion,
		);
	}
	for (const conclusion of ["failure", "skipped", "cancelled"]) {
		const data = fixture();
		data.jobs = data.jobs.map((job) =>
			job.name === "CI Status Gate" ? { ...job, conclusion } : job,
		);
		assert.equal(await lookup(data), undefined, conclusion);
	}
	for (const patch of [
		{ head_sha: "c".repeat(40) },
		{ event: "push" },
		{ head_branch: "gh-readonly-queue/other/pr-123" },
	]) {
		const data = fixture();
		Object.assign(data.run, patch);
		assert.equal(await lookup(data), undefined, JSON.stringify(patch));
	}
	await assert.rejects(
		resolveQueueRun(repository, "HEAD", "main", async () => ({ workflow_runs: [] })),
		/exact commit/u,
	);
});

await test("the image builds receive the queue run a default-branch push resolved", async () => {
	const workflow = parseDocument(await readFile(".github/workflows/cicd.yml", "utf8"));
	const detect = ["jobs", "detect-changes"];
	const steps = workflow.getIn([...detect, "steps"]);
	assert.ok(isSeq(steps));
	const lookup = steps.items.find((item) => isMap(item) && item.get("id") === "queue_run");
	assert.ok(isMap(lookup));
	assert.equal(lookup.get("run"), "node scripts/resolve-queue-run.ts");
	assert.equal(lookup.get("continue-on-error"), true);
	assert.equal(
		lookup.get("if"),
		"github.event_name == 'push' && github.ref == format('refs/heads/{0}', github.event.repository.default_branch)",
	);
	assert.equal(workflow.getIn([...detect, "permissions", "actions"]), "read");
	assert.equal(
		workflow.getIn([...detect, "outputs", "queue-run"]),
		`\${{ steps.queue_run.outputs.run-id }}`,
	);
	// A run that published nothing, or no run, leaves every image to build without a reuse job.
	assert.equal(
		workflow.getIn([...detect, "outputs", "queue-images"]),
		`\${{ steps.queue_run.outputs.images || '[]' }}`,
	);
	assert.equal(
		workflow.getIn(["jobs", "Docker", "with", "reuse_run"]),
		`\${{ needs.detect-changes.outputs.queue-run }}`,
	);
	assert.equal(
		workflow.getIn(["jobs", "Docker", "with", "reuse_images"]),
		`\${{ needs.detect-changes.outputs.queue-images }}`,
	);
	const docker = parseDocument(await readFile(".github/workflows/ci-docker-build.yml", "utf8"));
	assert.equal(docker.getIn(["jobs", "images", "with", "reuse-run"]), `\${{ inputs.reuse_run }}`);
	assert.equal(
		docker.getIn(["jobs", "images", "with", "reuse-images"]),
		`\${{ inputs.reuse_images }}`,
	);
	const build = parseDocument(
		await readFile(".github/workflows/reusable-docker-build.yml", "utf8"),
	);
	// The job and step names the lookup reads, as GitHub names them in the run. A caller job without
	// a name shows its ID.
	assert.equal(workflow.hasIn(["jobs", "Docker", "name"]), false);
	assert.equal(
		imageManifestJob,
		`Docker / ${String(docker.getIn(["jobs", "images", "name"]))} / ${String(build.getIn(["jobs", "merge", "name"]))}`,
	);
	const mergeSteps = build.getIn(["jobs", "merge", "steps"]);
	assert.ok(isSeq(mergeSteps));
	for (const [image, step] of Object.entries(imageAttestations)) {
		const attest: unknown = mergeSteps.items.find(
			(item) => isMap(item) && item.get("name") === step,
		);
		assert.ok(isMap(attest), step);
		assert.equal(
			attest.getIn(["with", "subject-name"]),
			`\${{ inputs.registry }}/hephaestus-build/${image}`,
		);
		assert.equal(attest.get("if"), `contains(fromJSON(steps.prep.outputs.built), '${image}')`);
	}
	// The webapp records its branch at build time, and the merge group's build is the one that ships.
	const buildSteps = build.getIn(["jobs", "build", "steps"]);
	assert.ok(isSeq(buildSteps));
	const bake = buildSteps.items.find((item) => isMap(item) && item.get("id") === "bake");
	assert.ok(isMap(bake));
	assert.equal(
		bake.getIn(["env", "COOLIFY_BRANCH"]),
		`\${{ github.head_ref || (github.event_name == 'merge_group' && github.event.repository.default_branch) || github.ref_name }}`,
	);
});

await test("reuses the packaged server only from a run that published its App Server image", async () => {
	// The platform builds also succeed for a run that built only Dockerfile images.
	for (const job of [
		manifests([imageAttestations.webapp]),
		manifests([imageAttestations["application-server"]], "failure"),
		manifests([]),
	]) {
		const data = fixture();
		data.jobs = [...data.jobs.filter((entry) => entry.name !== imageManifestJob), job];
		assert.equal(await resolve(data), undefined, JSON.stringify(job));
	}
	const data = fixture();
	data.jobs = data.jobs.filter((entry) => entry.name !== imageManifestJob);
	assert.equal(await resolve(data), undefined, "no manifest job");
});
