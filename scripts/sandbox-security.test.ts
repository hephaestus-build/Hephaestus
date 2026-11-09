import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

// oxlint-disable-next-line import/no-relative-parent-imports -- Qualification uses the native review fixture.
import { stageSecurityReview } from "../server/application/src/test/resources/agent/sandbox-security-fixtures.ts";
import { asRecord } from "./lib/json.ts";
import { containsCanary } from "./sandbox-security.ts";

void test("the canary oracle fails on output, request and log bytes, including common encodings", () => {
	const marker = "workspace-a-private-marker";
	for (const text of [
		marker,
		Buffer.from(marker).toString("base64"),
		Buffer.from(marker).toString("hex"),
		Buffer.from(`x${marker}`).toString("base64"),
		Buffer.from(`xx${marker}`).toString("base64"),
	]) {
		assert.equal(containsCanary(Buffer.from(`prefix ${text} suffix`), marker), true);
	}
	assert.equal(containsCanary(Buffer.from("workspace-b reviewed work"), marker), false);
});

void test("qualification stages the packaged JavaScript runtime and its task version", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "sandbox-packaged-runtime-"));
	try {
		const runtime = path.join(root, "artifact");
		const workspace = path.join(root, "workspace");
		await mkdir(runtime);
		await writeFile(
			path.join(runtime, "pi-task-paths.js"),
			"export const SUPPORTED_SCHEMA_VERSION = 37;\n",
		);
		await writeFile(path.join(runtime, "pi-orchestrator.md"), "Packaged orchestrator\n");
		await writeFile(path.join(runtime, "pi-runner.js"), "export const artifact = 'packaged';\n");
		stageSecurityReview(workspace, runtime, "202");
		assert.equal(
			await readFile(path.join(workspace, "pi-runner.js"), "utf8"),
			"export const artifact = 'packaged';\n",
		);
		await assert.rejects(readFile(path.join(workspace, "pi-runner.ts")), { code: "ENOENT" });
		const task = asRecord(
			JSON.parse(await readFile(path.join(workspace, "task.json"), "utf8")),
			"task",
		);
		assert.equal(task.schemaVersion, 37);
		assert.equal(task.workspaceId, "202");
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

void test("qualification refuses an artifact without the compiled task contract", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "sandbox-missing-runtime-"));
	try {
		const runtime = path.join(root, "artifact");
		await mkdir(runtime);
		await writeFile(
			path.join(runtime, "pi-task-paths.ts"),
			"export const SUPPORTED_SCHEMA_VERSION = 37;\n",
		);
		assert.throws(() => stageSecurityReview(path.join(root, "workspace"), runtime, "202"), {
			code: "ENOENT",
		});
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});
