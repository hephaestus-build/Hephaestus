// Every run kind that a sandbox drives in-process opens its sessions through openRunSession: the real registry
// dispatches each kind, and a recording factory stands in for the session factory.

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { mock } from "node:test";

import type { RunDescriptor } from "../../../main/resources/agent/pi-session.ts";

/** Set in the child process that runs the dispatch with module mocks. */
const CHILD = "PI_SESSION_DISPATCH_CHILD";

if (process.env[CHILD] === undefined) {
	void test("every in-process run kind of the registry opens its session through openRunSession", () => {
		const child = spawnSync(
			process.execPath,
			["--experimental-test-module-mocks", import.meta.filename],
			{
				// Without the parent's test-runner context, the child reports in text that a failure message can show.
				env: { ...process.env, NODE_TEST_CONTEXT: undefined, [CHILD]: "1" },
				encoding: "utf8",
			},
		);
		assert.equal(child.status, 0, `${child.stdout}\n${child.stderr}`);
	});
} else {
	await dispatch();
}

async function dispatch(): Promise<void> {
	const AGENT = new URL("../../../main/resources/agent/", import.meta.url);
	const root = mkdtempSync(path.join(tmpdir(), "pi-session-dispatch-"));
	process.on("exit", () => {
		rmSync(root, { recursive: true, force: true });
	});

	mock.module(new URL("sandbox-paths.ts", AGENT).href, {
		exports: {
			WORKSPACE: path.join(root, "work"),
			AGENT_DIR: path.join(root, ".pi"),
			PROGRAM_FAILURE: path.join(root, "program-failure"),
		},
	});

	/** The system prompt of each session that a kind opened through the factory. */
	const opened: string[] = [];
	mock.module(new URL("pi-session.ts", AGENT).href, {
		exports: {
			openRunSession: async (_place: unknown, descriptor: RunDescriptor) => {
				opened.push(descriptor.systemPrompt);
				throw new Error("the session factory was called");
			},
		},
	});

	const { KINDS } = await import("../../../main/resources/agent/driver-kinds.ts");
	const { contextOf } = await import("./turn-worker.ts");

	for (const kind of ["TURN", "DIGEST"]) {
		void test(`the registry's ${kind} runner opens its session through openRunSession`, async () => {
			const runner = KINDS[kind];
			assert.ok(runner);
			const systemPrompt = `You are Hephaestus, running a ${kind}.`;
			mock.method(globalThis, "fetch", async (input: string | URL | Request) => {
				const url = new URL(input instanceof Request ? input.url : input);
				return url.pathname.endsWith("/context")
					? Response.json(contextOf([], "question", systemPrompt))
					: Response.json({});
			});
			try {
				await runner({
					assignment: {
						runId: `run-${kind}`,
						epoch: 1,
						kind,
						leasedUntil: Date.now() + 60_000,
						deadline: null,
					},
					gateway: {
						url: "http://gateway:8081",
						headers: { authorization: "Bearer sandbox-token" },
					},
					signal: new AbortController().signal,
				});
			} finally {
				mock.restoreAll();
			}
			assert.ok(opened.includes(systemPrompt), `${kind} opened no session through the factory`);
		});
	}
}
