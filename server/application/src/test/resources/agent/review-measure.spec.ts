// A review's measurement reads the server's context strictly, and gives each program the gateway's routes and claim.

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readdir, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { extract, extractArguments } from "../../../../../../docker/agents/pi/gateway-init.ts";
import {
	emptyDirectory,
	measureEnvironment,
	parseMeasureContext,
} from "../../../main/resources/agent/review-measure.ts";

const RUN = "0b1d4b71-5f1e-4a52-9a43-7e0c4b0d8a11";

const context = {
	command: ["sh", "-c", "node /workspace/.run-pi.js"],
	env: { AGENT_BUDGET_MS: "60000", GIT_CONFIG_NOSYSTEM: "1" },
	workDeadline: 1000,
	uploadDeadline: 2000,
};

void test("a measurement context that names a runner, its environment and its deadlines is read whole", () => {
	assert.deepEqual(parseMeasureContext(context), context);
	for (const broken of [
		null,
		[],
		{ ...context, command: [] },
		{ ...context, command: ["sh", ""] },
		{ ...context, env: { AGENT_BUDGET_MS: 60_000 } },
		{ ...context, uploadDeadline: context.workDeadline },
		{ ...context, workDeadline: "soon" },
	]) {
		assert.throws(() => parseMeasureContext(broken), /measurement context/u);
	}
});

void test("each program of the measurement reaches the gateway with the sandbox token and the run's claim", () => {
	const env = measureEnvironment(
		{
			assignment: { runId: RUN, epoch: 3, kind: "REVIEW", leasedUntil: 0, deadline: null },
			gateway: {
				url: "http://10.43.0.12:8081",
				headers: {
					authorization: "Bearer sandbox-token",
					"x-agent-run": RUN,
					"x-agent-epoch": "3",
				},
			},
			signal: new AbortController().signal,
		},
		parseMeasureContext(context),
	);

	assert.equal(env.AGENT_BUDGET_MS, "60000");
	assert.equal(env.LLM_PROXY_URL, "http://10.43.0.12:8081/internal/llm");
	assert.equal(env.LLM_PROXY_TOKEN, "sandbox-token");
	assert.deepEqual(JSON.parse(env.LLM_PROXY_CLAIM ?? ""), {
		"x-agent-run": RUN,
		"x-agent-epoch": "3",
	});
	assert.equal(
		env.SANDBOX_RUNTIME_URL,
		`http://10.43.0.12:8081/internal/agent/runs/${RUN}/runtime`,
	);
	assert.equal(env.SANDBOX_WORK_DEADLINE_MS, "1000");
	assert.equal(env.SANDBOX_UPLOAD_DEADLINE_MS, "2000");
});

void test("an attempt's workspace is emptied, read-only captured directories included, and the volume stays", async () => {
	const root = await mkdtemp(path.join(tmpdir(), "review-measure-"));
	const volume = path.join(root, "workspace");
	const outside = path.join(root, "outside");
	try {
		await mkdir(path.join(volume, "repos", "reviewed", ".git"), { recursive: true });
		await writeFile(path.join(volume, "repos", "reviewed", "pom.xml"), "<project/>");
		await writeFile(path.join(volume, "INDEX.json"), "{}");
		await mkdir(path.join(volume, "out"));
		await writeFile(path.join(volume, "out", "result.json"), "{}");
		await mkdir(outside);
		await writeFile(path.join(outside, "kept"), "kept");
		await symlink(outside, path.join(volume, "node_modules"));
		await chmod(path.join(volume, "repos", "reviewed", "pom.xml"), 0o444);
		await chmod(path.join(volume, "repos", "reviewed", ".git"), 0o555);
		await chmod(path.join(volume, "repos", "reviewed"), 0o555);
		await chmod(path.join(volume, "repos"), 0o555);

		await emptyDirectory(volume);

		assert.deepEqual(await readdir(volume), []);
		assert.deepEqual(await readdir(outside), ["kept"]);
	} finally {
		await rm(root, { recursive: true, force: true });
	}
});

const gnuTar = spawnSync("tar", ["--version"], { encoding: "utf8" }).stdout.includes("GNU tar");
/** The sandbox image extracts with GNU tar. CI runs this test; elsewhere the skip is announced. */
const noGnuTar =
	gnuTar || process.env.CI === "true"
		? false
		: "the sandbox image extracts with GNU tar, which this host lacks";
if (noGnuTar !== false) {
	console.log(`::warning::review-measure extraction test skipped: ${noGnuTar}`);
}

void test(
	"a second attempt in the same sandbox extracts its captured workspace once the first attempt's tree is gone",
	{ skip: noGnuTar },
	async () => {
		assert.ok(gnuTar, "CI must run this test with GNU tar");
		const root = await mkdtemp(path.join(tmpdir(), "review-measure-attempts-"));
		const source = path.join(root, "source");
		const volume = path.join(root, "workspace");
		const archive = path.join(root, "workspace.tar");
		try {
			// A captured workspace: read-only directories and files, as the server stages them.
			await mkdir(path.join(source, "repos", "reviewed", ".git"), { recursive: true });
			await mkdir(path.join(source, "context"));
			await writeFile(path.join(source, "repos", "reviewed", "pom.xml"), "<project/>");
			await writeFile(path.join(source, "repos", "reviewed", ".git", "HEAD"), "ref: main");
			await writeFile(path.join(source, "context", "notes.md"), "notes");
			for (const file of [
				"repos/reviewed/pom.xml",
				"repos/reviewed/.git/HEAD",
				"context/notes.md",
			]) {
				await chmod(path.join(source, file), 0o444);
			}
			for (const directory of ["repos/reviewed/.git", "repos/reviewed", "repos", "context"]) {
				await chmod(path.join(source, directory), 0o555);
			}
			const created = spawnSync("tar", ["--create", "--file", archive, "--directory", source, "."]);
			assert.equal(created.status, 0, String(created.stderr));
			await mkdir(volume);

			await extract(archive, volume, []);
			// The defect: the next attempt extracting over the first attempt's tree fails.
			assert.notEqual(spawnSync("tar", extractArguments(archive, volume, [])).status, 0);

			await emptyDirectory(volume);
			await extract(archive, volume, []);
			assert.equal(
				await readFile(path.join(volume, "repos", "reviewed", "pom.xml"), "utf8"),
				"<project/>",
			);
			assert.equal(await readFile(path.join(volume, "context", "notes.md"), "utf8"), "notes");
		} finally {
			await emptyDirectory(root);
			await rm(root, { recursive: true, force: true });
		}
	},
);
