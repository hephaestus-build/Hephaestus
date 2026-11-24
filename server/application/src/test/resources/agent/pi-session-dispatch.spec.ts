// Every run kind opens its Pi sessions through openRunSession, checked by running each kind: the registry dispatches
// the in-process kinds with a recording factory, and the review runner that REVIEW starts runs with one too.

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { mock } from "node:test";

import type { KindRunner } from "../../../main/resources/agent/driver.ts";
import { capturedLines, runRunner, stagePractices } from "./program-host-runner.ts";

/** Set in the child process that runs the dispatch with module mocks. */
const CHILD = "PI_SESSION_DISPATCH_CHILD";

/**
 * The one run kind that keeps its own model client: the composer's proxy fence checks each request against that
 * client's conversation until the publication cutover retires the kind (CF-18). It is named by its kind, so a model
 * session built by any other kind's runner fails.
 */
const OWN_CLIENT_UNTIL_CUTOVER = "REVIEW_COMPOSE";

/**
 * What a kind's runner built: sessions through the factory, Pi sessions past it, and model sessions of its own.
 */
interface Use {
	factory: number;
	direct: number;
	modelSession: number;
}

/** Why a kind's use breaks the rule, or null. */
function breach(kind: string, use: Use): string | null {
	if (kind === OWN_CLIENT_UNTIL_CUTOVER) {
		return null;
	}
	if (use.modelSession > 0) {
		return `${kind} built a model session of its own`;
	}
	if (use.direct > 0) {
		return `${kind} built a Pi session past the factory`;
	}
	return use.factory === 0 ? `${kind} opened no session through the factory` : null;
}

/** A function that is not a class, so a call to it may return what it built. */
function isPlainFunction(value: unknown): value is (...args: never[]) => unknown {
	return (
		typeof value === "function" && !/^class[\s{]/u.test(Function.prototype.toString.call(value))
	);
}

/** A runner that ends its run without opening any session. */
const noSession: KindRunner = async () => ({ leaseLost: false, outcome: "completed" });

if (process.env[CHILD] === undefined) {
	void test("every in-process run kind opens its sessions through openRunSession", () => {
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

	void test("the review runner that REVIEW starts opens its sessions through openRunSession", async () => {
		const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-session-review-")));
		try {
			stagePractices(cwd, [{ slug: "measured" }]);
			const exit = await runRunner(cwd, {});
			assert.equal(exit.status, 0, exit.stderr);
			const use = {
				factory: capturedLines(cwd, "sessions").length,
				direct: capturedLines(cwd, "direct-sessions").length,
				modelSession: capturedLines(cwd, "model-sessions").length,
			};
			assert.equal(breach("REVIEW", use), null);
		} finally {
			rmSync(cwd, { recursive: true, force: true });
		}
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

	// REVIEW's measurement runs its whole path in the spec's own image layout: an empty volume, and two programs that
	// note that they ran and exit.
	const volume = path.join(root, "volume");
	const sdkDirectory = path.join(root, "pi-sdk");
	const ran = path.join(root, "programs-ran");
	mkdirSync(volume);
	mkdirSync(path.join(sdkDirectory, "node_modules"), { recursive: true });
	for (const program of ["gateway-init.js", "gateway-run.js"]) {
		writeFileSync(
			path.join(sdkDirectory, program),
			`require("node:fs").appendFileSync(${JSON.stringify(ran)}, ${JSON.stringify(`${program}\n`)});`,
		);
	}
	mock.module(new URL("sandbox-paths.ts", AGENT).href, {
		exports: {
			VOLUME: volume,
			PI_SDK: sdkDirectory,
			WORKSPACE: path.join(root, "work"),
			AGENT_DIR: path.join(root, ".pi"),
			PROGRAM_FAILURE: path.join(root, "program-failure"),
		},
	});

	let use: Use = { factory: 0, direct: 0, modelSession: 0 };

	// Every Pi session that a runner builds is counted by its type, whatever SDK function built it. The factory's own
	// build is no build past it.
	let insideFactory = false;
	const sdk = await import("@earendil-works/pi-coding-agent");
	const buildsSession = (value: unknown): boolean =>
		value instanceof sdk.AgentSession ||
		value instanceof sdk.AgentSessionRuntime ||
		(typeof value === "object" &&
			value !== null &&
			"session" in value &&
			value.session instanceof sdk.AgentSession);
	const note = (value: unknown): unknown => {
		if (!insideFactory && buildsSession(value)) {
			use.direct += 1;
		}
		return value;
	};
	const noteWhenBuilt = async (built: Promise<unknown>): Promise<unknown> => note(await built);
	const recorded =
		(function_: (...args: never[]) => unknown) =>
		(...args: never[]): unknown => {
			const built = function_(...args);
			return built instanceof Promise ? noteWhenBuilt(built) : note(built);
		};
	mock.module("@earendil-works/pi-coding-agent", {
		exports: Object.fromEntries(
			Object.entries(sdk).map(([name, value]) => [
				name,
				isPlainFunction(value) ? recorded(value) : value,
			]),
		),
	});

	// The factory opens real sessions, so a run goes on past it, and is counted.
	const factory = await import("../../../main/resources/agent/pi-session.ts");
	mock.module(new URL("pi-session.ts", AGENT).href, {
		exports: {
			...factory,
			openRunSession: async (...args: Parameters<typeof factory.openRunSession>) => {
				use.factory += 1;
				insideFactory = true;
				try {
					return await factory.openRunSession(...args);
				} finally {
					insideFactory = false;
				}
			},
		},
	});

	const composerModel = await import("../../../main/resources/agent/review-composer-model.ts");
	class RecordedModelSession extends composerModel.ModelSession {
		constructor(...args: ConstructorParameters<typeof composerModel.ModelSession>) {
			super(...args);
			use.modelSession += 1;
		}
	}
	mock.module(new URL("review-composer-model.ts", AGENT).href, {
		exports: { ...composerModel, ModelSession: RecordedModelSession },
	});

	const recordedSdk = await import("@earendil-works/pi-coding-agent");
	const { KINDS } = await import("../../../main/resources/agent/driver-kinds.ts");
	const { measureReview } = await import("../../../main/resources/agent/review-measure.ts");
	const { contextOf } = await import("./turn-worker.ts");

	/**
	 * Runs a kind's runner against a gateway that serves its context, a composition with nothing to compose, and a
	 * model that refuses each call, so a turn ends after its session's first request.
	 */
	async function measure(kind: string, runner: KindRunner): Promise<Use & { outcome: unknown }> {
		use = { factory: 0, direct: 0, modelSession: 0 };
		mock.method(globalThis, "fetch", async (input: string | URL | Request) => {
			const url = new URL(input instanceof Request ? input.url : input);
			if (url.pathname.startsWith("/internal/llm")) {
				return Response.json({ error: { message: "refused" } }, { status: 400 });
			}
			if (url.pathname.endsWith("/context")) {
				return Response.json(
					kind === "REVIEW"
						? {
								command: ["node", "runner.js"],
								env: {},
								workDeadline: Date.now() + 60_000,
								uploadDeadline: Date.now() + 120_000,
							}
						: contextOf([], "question"),
				);
			}
			if (url.pathname.endsWith("/composition/next")) {
				return new Response(null, { status: 204 });
			}
			return Response.json(url.pathname.endsWith("/composition/close") ? { handedOn: null } : {});
		});
		try {
			const outcome = await runner({
				assignment: {
					runId: `run-${kind}`,
					epoch: 1,
					kind,
					leasedUntil: Date.now() + 60_000,
					deadline: null,
				},
				gateway: { url: "http://gateway:8081", headers: { authorization: "Bearer sandbox-token" } },
				signal: new AbortController().signal,
			});
			return { ...use, outcome };
		} finally {
			mock.restoreAll();
		}
	}

	const inProcess = Object.entries(KINDS).filter(([kind]) => kind !== "REVIEW");

	void test("REVIEW's measurement runs its programs and builds no session of its own", async () => {
		assert.equal(KINDS.REVIEW, measureReview);
		const measured = await measure("REVIEW", measureReview);
		// The whole path ran: both programs ran, and the measurement completed.
		assert.deepEqual(readFileSync(ran, "utf8").trim().split("\n"), [
			"gateway-init.js",
			"gateway-run.js",
		]);
		assert.deepEqual(measured.outcome, { leaseLost: false, outcome: "completed" });
		assert.deepEqual([measured.modelSession, measured.direct], [0, 0]);
	});

	for (const [kind, runner] of inProcess) {
		void test(`the registry's ${kind} runner keeps the factory rule`, async () => {
			assert.equal(breach(kind, await measure(kind, runner)), null);
		});
	}

	/** A runner that builds a model session of its own, as the composer does. */
	const ownLoop: KindRunner = async () => {
		const own = new RecordedModelSession(
			async () => ({ status: 200, body: "", refusal: null }),
			{
				protocol: "openai-completions",
				modelId: "m",
				reasoningEffort: null,
				maxOutputTokens: 1,
				temperature: null,
			},
			"system",
			[],
		);
		assert.ok(own instanceof composerModel.ModelSession);
		return { leaseLost: false, outcome: "completed" };
	};

	void test("a runner that builds a model session of its own breaks the rule", async () => {
		assert.match(breach("TURN", await measure("TURN", ownLoop)) ?? "", /model session of its own/u);
	});

	/** Builds a Pi session past the factory, in the spec's own folders. */
	async function buildPastFactory(): Promise<void> {
		const { session } = await recordedSdk.createAgentSessionFromServices({
			services: await recordedSdk.createAgentSessionServices({ cwd: root, agentDir: root }),
			sessionManager: recordedSdk.SessionManager.inMemory(root),
		});
		session.dispose();
	}

	/** A turn runner that, after its factory session, builds a second Pi session itself. */
	const afterFactory: KindRunner = async (run) => {
		const turn = KINDS.TURN;
		assert.ok(turn);
		const outcome = await turn(run);
		await buildPastFactory();
		return outcome;
	};

	void test("a runner that builds a Pi session past the factory breaks the rule, also after its factory session", async () => {
		const measured = await measure("TURN", afterFactory);
		assert.equal(measured.factory, 1);
		assert.match(breach("TURN", measured) ?? "", /past the factory/u);
	});

	void test("a runner that opens no session through the factory breaks the rule", async () => {
		assert.match(
			breach("DIGEST", await measure("DIGEST", noSession)) ?? "",
			/no session through the factory/u,
		);
	});
}
