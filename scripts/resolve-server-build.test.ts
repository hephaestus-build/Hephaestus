import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { isMap, isSeq, parseDocument } from "yaml";

import { resolveServerBuild } from "./resolve-server-build.ts";

const repository = "hephaestus-build/Hephaestus";
const commit = "a".repeat(40);
const requiredJobs = [
	"App Server: Package",
	"Build / App Server: Generated artifacts",
	"Build / App Server: Database",
	"Build / Webapp: E2E",
	"App Server image / Build linux/amd64 Docker Image",
	"Test / App Server: Unit and architecture",
	"Test / App Server: Integration (application)",
	"Test / App Server: Integration (providers-and-startup)",
	"Security / Dependencies, secrets, and policy",
	"CI Status Gate",
];
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
			...requiredJobs,
			"App Server image / Build linux/arm64 Docker Image",
			"Release evidence preflight",
		].map((name) => ({ name, conclusion: "success" })),
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
	return resolveServerBuild(repository, commit, "main", (path) => {
		if (path.includes("/workflows/")) return Promise.resolve({ workflow_runs: [data.run] });
		if (path.includes("/jobs?")) {
			assert.match(path, /filter=latest/);
			return Promise.resolve([{ jobs: data.jobs.slice(0, 4) }, { jobs: data.jobs.slice(4) }]);
		}
		return Promise.resolve([{ artifacts: [] }, { artifacts: [data.artifact] }]);
	});
}

await test("selects the immutable artifact of an exact-commit successful release-candidate merge group", async () => {
	assert.deepEqual(await resolve(), { runId: 101, artifactId: 202 });
});

await test("reuses an ordinary merge group without release-only image and evidence jobs", async () => {
	const data = fixture();
	data.jobs = data.jobs
		.filter((job) => job.name !== "App Server image / Build linux/arm64 Docker Image")
		.map((job) =>
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
		await resolveServerBuild(repository, commit, "main", () =>
			Promise.resolve({ workflow_runs: [] }),
		),
		undefined,
	);
	assert.equal(
		await resolveServerBuild(repository, commit, "main", () =>
			Promise.resolve({ workflow_runs: [{}] }),
		),
		undefined,
	);
	await assert.rejects(
		resolveServerBuild(repository, commit, "main", () => Promise.reject(new Error("unavailable"))),
		/unavailable/,
	);
	const data = fixture();
	data.artifact.id = 0;
	await assert.rejects(resolve(data), /invalid identifier/);
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
	assert.match(String(download.get("uses")), /^actions\/download-artifact@/);
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
	assert.match(String(upload.get("uses")), /^actions\/upload-artifact@/);
	assert.equal(upload.getIn(["with", "name"]), `\${{ env.SERVER_BUILD_ARTIFACT }}`);
});
