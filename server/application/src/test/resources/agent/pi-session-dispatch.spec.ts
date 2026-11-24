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
			const returned = new Set(capturedLines(cwd, "sessions"));
			const built = capturedLines(cwd, "built-sessions");
			const use = {
				factory: returned.size,
				direct: built.filter((session) => !returned.has(session)).length,
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

	// Every Pi session is counted where it is constructed, whichever SDK function or caller builds it: the constructor
	// installs the session's tool hooks. A session of the factory's is one that a call to the factory returned.
	let built: object[] = [];
	let fromFactory = new WeakSet<object>();
	const sdk = await import("@earendil-works/pi-coding-agent");
	const sessionPrototype: object = sdk.AgentSession.prototype;
	const installToolHooks: unknown = Reflect.get(sessionPrototype, "_installAgentToolHooks");
	assert.ok(
		typeof installToolHooks === "function",
		"a Pi session installs its tool hooks as it is built",
	);
	Reflect.set(
		sessionPrototype,
		"_installAgentToolHooks",
		function recordBuild(this: object, ...args: unknown[]) {
			built.push(this);
			return Reflect.apply(installToolHooks, this, args) as unknown;
		},
	);

	const factory = await import("../../../main/resources/agent/pi-session.ts");
	mock.module(new URL("pi-session.ts", AGENT).href, {
		exports: {
			...factory,
			openRunSession: async (...args: Parameters<typeof factory.openRunSession>) => {
				const opened = await factory.openRunSession(...args);
				fromFactory.add(opened.session);
				return opened;
			},
		},
	});

	let modelSessions = 0;
	const composerModel = await import("../../../main/resources/agent/review-composer-model.ts");
	class RecordedModelSession extends composerModel.ModelSession {
		constructor(...args: ConstructorParameters<typeof composerModel.ModelSession>) {
			super(...args);
			modelSessions += 1;
		}
	}
	mock.module(new URL("review-composer-model.ts", AGENT).href, {
		exports: { ...composerModel, ModelSession: RecordedModelSession },
	});

	const { KINDS } = await import("../../../main/resources/agent/driver-kinds.ts");
	const { measureReview } = await import("../../../main/resources/agent/review-measure.ts");
	const { contextOf } = await import("./turn-worker.ts");

	/**
	 * Runs a kind's runner against a gateway that serves its context, a composition with nothing to compose, and a
	 * model that refuses each call, so a turn ends after its session's first request.
	 */
	async function measure(
		kind: string,
		runner: KindRunner,
	): Promise<Use & { asked: number; outcome: unknown }> {
		built = [];
		fromFactory = new WeakSet();
		modelSessions = 0;
		let asked = 0;
		mock.method(globalThis, "fetch", async (input: string | URL | Request) => {
			const url = new URL(input instanceof Request ? input.url : input);
			if (url.pathname.startsWith("/internal/llm")) {
				asked += 1;
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
			const factorySessions = built.filter((session) => fromFactory.has(session)).length;
			return {
				factory: factorySessions,
				direct: built.length - factorySessions,
				modelSession: modelSessions,
				asked,
				outcome,
			};
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
		// The measurement builds no session at all: the staged review runner opens its sessions in its own process.
		assert.deepEqual([measured.factory, measured.direct, measured.modelSession], [0, 0, 0]);
	});

	for (const [kind, runner] of inProcess) {
		void test(`the registry's ${kind} runner keeps the factory rule`, async () => {
			const measured = await measure(kind, runner);
			assert.equal(breach(kind, measured), null);
			if (kind !== OWN_CLIENT_UNTIL_CUTOVER) {
				// The factory's session asked the model, so the run went on past it.
				assert.ok(measured.asked > 0, `${kind} asked the model nothing`);
			}
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
		const { session } = await sdk.createAgentSessionFromServices({
			services: await sdk.createAgentSessionServices({ cwd: root, agentDir: root }),
			sessionManager: sdk.SessionManager.inMemory(root),
		});
		session.dispose();
	}

	/** A turn runner that builds a second Pi session itself while its factory session is open. */
	const alongsideFactory: KindRunner = async (run) => {
		const turn = KINDS.TURN;
		assert.ok(turn);
		const [outcome] = await Promise.all([turn(run), buildPastFactory()]);
		return outcome;
	};

	void test("a runner that builds a Pi session past the factory breaks the rule, also beside its factory session", async () => {
		const measured = await measure("TURN", alongsideFactory);
		assert.equal(measured.factory, 1);
		assert.match(breach("TURN", measured) ?? "", /past the factory/u);
	});

	/** A turn runner that, after its factory session, constructs another session from that session's parts. */
	const constructsSession: KindRunner = async (run) => {
		const turn = KINDS.TURN;
		assert.ok(turn);
		const outcome = await turn(run);
		const [opened] = built;
		assert.ok(opened instanceof sdk.AgentSession);
		const own = new sdk.AgentSession({
			agent: opened.agent,
			sessionManager: opened.sessionManager,
			settingsManager: opened.settingsManager,
			resourceLoader: opened.resourceLoader,
			modelRuntime: opened.modelRuntime,
			cwd: root,
		});
		own.dispose();
		return outcome;
	};

	void test("a runner that constructs a Pi session itself breaks the rule", async () => {
		const measured = await measure("TURN", constructsSession);
		assert.equal(measured.factory, 1);
		assert.match(breach("TURN", measured) ?? "", /past the factory/u);
	});

	/** The turn runner, whose call to the factory fails before it builds a session; the turn still ends. */
	const failedFactory: KindRunner = async (run) => {
		mock.method(sdk.DefaultResourceLoader.prototype, "reload", async () => {
			throw new Error("the factory failed");
		});
		const turn = KINDS.TURN;
		assert.ok(turn);
		return turn(run);
	};

	void test("a runner whose factory call fails opens no session through the factory", async () => {
		assert.match(
			breach("TURN", await measure("TURN", failedFactory)) ?? "",
			/no session through the factory/u,
		);
	});

	void test("a runner that opens no session through the factory breaks the rule", async () => {
		assert.match(
			breach("DIGEST", await measure("DIGEST", noSession)) ?? "",
			/no session through the factory/u,
		);
	});
}
