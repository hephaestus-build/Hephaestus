import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import {
	outsideWorkspace,
	redactCredentials,
	toolEnvironment,
} from "../../../main/resources/agent/pi-agent-sandbox.ts";
import { runRunner, stagePractices } from "./program-host-runner.ts";
import { assertToolEnvironment } from "./tool-environment-check.ts";

void test("the tool environment keeps the shell's basics and Git's hardening, and nothing else", () => {
	assert.deepEqual(
		toolEnvironment({
			PATH: "/usr/bin",
			HOME: "/home/agent",
			LANG: "C.UTF-8",
			TMPDIR: "/tmp",
			GIT_CONFIG_COUNT: "1",
			GIT_CONFIG_KEY_0: "core.hooksPath",
			GIT_CONFIG_VALUE_0: "/nonexistent",
			GIT_TOKEN: "git-token",
			HEPHAESTUS_SANDBOX_TOKEN: "sandbox-token",
			LLM_PROXY_TOKEN: "proxy-token",
			LLM_PROXY_CLAIM: "{}",
			NODE_OPTIONS: "--require=x",
		}),
		{
			PATH: "/usr/bin",
			HOME: "/home/agent",
			LANG: "C.UTF-8",
			TMPDIR: "/tmp",
			GIT_CONFIG_COUNT: "1",
			GIT_CONFIG_KEY_0: "core.hooksPath",
			GIT_CONFIG_VALUE_0: "/nonexistent",
		},
	);
});

void test("a review's bash command gets only the tool environment, even where its process holds a stray secret", async (t) => {
	const cwd = mkdtempSync(path.join(tmpdir(), "pi-tool-environment-"));
	t.after(() => rmSync(cwd, { recursive: true, force: true }));
	stagePractices(cwd, [{ slug: "test-practice" }]);

	const exit = await runRunner(
		cwd,
		{},
		{
			PI_HOST_BASH_COMMAND: "env > tool-environment.txt",
			HEPHAESTUS_SANDBOX_TOKEN: "sandbox-token-sentinel",
			SOME_SERVICE_API_KEY: "api-key-sentinel",
		},
	);

	assert.ok(
		exit.stderr.includes("tool.called phase=review practice=0 tool=bash"),
		"the runner called bash",
	);
	// The runner holds the harness's credential, `job-token`, as a review runner holds its own.
	assertToolEnvironment(readFileSync(path.join(cwd, "tool-environment.txt"), "utf8"), [
		"job-token",
		"sandbox-token-sentinel",
		"api-key-sentinel",
	]);
});

void test("the file tools open the workspace and bash's own output files, and a link out of the workspace counts where it leads", (t) => {
	const root = mkdtempSync(path.join(tmpdir(), "pi-workspace-"));
	t.after(() => rmSync(root, { recursive: true, force: true }));
	mkdirSync(path.join(root, "inputs"));
	writeFileSync(path.join(root, "inputs", "INDEX.md"), "index");
	symlinkSync("/etc/hosts", path.join(root, "inputs", "hosts"));
	const bashOutput = path.join(tmpdir(), "pi-bash-0123456789abcdef.log");

	assert.equal(outsideWorkspace("inputs/INDEX.md", root, root), undefined);
	assert.equal(outsideWorkspace("@inputs/INDEX.md", root, root), undefined);
	assert.equal(outsideWorkspace("inputs/not-yet.md", root, root), undefined);
	assert.equal(outsideWorkspace(bashOutput, root, root), undefined);
	for (const refused of [
		"/proc/self/environ",
		"/proc/1/environ",
		"../outside.txt",
		"inputs/hosts",
		"~/.ssh/id_ed25519",
		path.join(tmpdir(), "other.log"),
	]) {
		assert.ok(outsideWorkspace(refused, root, root)?.includes("is outside") === true, refused);
	}
});

void test("what leaves a sandbox to be stored carries no signed token", () => {
	const token = "eyJhbGciOiJFUzI1NiJ9.eyJzYW5kYm94X2lkIjoieCJ9.c2lnbmF0dXJlLXNlbnRpbmVs";
	assert.equal(redactCredentials(`seen ${token} here`), "seen [credential] here");
	assert.equal(
		redactCredentials(JSON.stringify({ text: `Bearer ${token}` })),
		'{"text":"Bearer [credential]"}',
	);
	assert.equal(
		redactCredentials("eyJ is only the start of a word"),
		"eyJ is only the start of a word",
	);
});
