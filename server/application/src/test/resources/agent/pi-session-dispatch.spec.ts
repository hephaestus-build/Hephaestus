// Every run kind opens its Pi sessions through openRunSession, checked by running each kind: the registry dispatches
// the in-process kinds with a recording factory, and the review runner that REVIEW starts runs with one too.

import assert from "node:assert/strict";
import { AsyncLocalStorage } from "node:async_hooks";
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
	outsideFactory?: number;
	factory: number;
	direct: number;
	modelSession: number;
}

/** Why a kind's use breaks the rule, or null. */
function breach(kind: string, use: Use): string | null {
	// The exemption covers the composer's own model client only: no kind builds a Pi session past the factory.
	if (use.direct > 0) {
		return `${kind} built a Pi session past the factory`;
	}
	if (kind === OWN_CLIENT_UNTIL_CUTOVER) {
		return null;
	}
	if (kind === "DRAFT_REPORT" && (use.outsideFactory ?? 0) > 0) {
		return `${kind} called the model outside its Pi session`;
	}
	if (use.modelSession > 0) {
		return `${kind} built a model session of its own`;
	}
	return use.factory === 0 ? `${kind} opened no session through the factory` : null;
}

/** A runner that ends its run without opening any session. */
const noSession: KindRunner = async () => ({ leaseLost: false, outcome: "completed" });

/**
 * How the staged review ends with each model: an answering model's result is staged and taken to admission; a model
 * that refuses each call leaves it unreachable, and the runner exits before admission.
 */
const ENDS = {
	answers: { status: 0, admissions: [1], staged: ["result"] },
	refuses: { status: 76, admissions: [], staged: [] },
};

/**
 * Runs the staged review runner on the real SDK, through the review specs' harness, with a model that answers or
 * refuses, checks that the review ended as it does with that model, and reads what it built.
 */
async function stagedReview(
	model: keyof typeof ENDS,
	env: Record<string, string> = {},
): Promise<Use & { asked: number }> {
	const cwd = realpathSync(mkdtempSync(path.join(tmpdir(), "pi-session-review-")));
	try {
		stagePractices(cwd, [{ slug: "measured" }]);
		const exit = await runRunner(cwd, {}, { PI_HOST_REAL_SDK: cwd, PI_HOST_MODEL: model, ...env });
		assert.equal(exit.status, ENDS[model].status, exit.stderr);
		// An answering model's candidate survives validation as a result, which the review takes to admission.
		assert.deepEqual(capturedLines(cwd, "staged"), ENDS[model].staged, exit.stderr);
		assert.deepEqual(capturedLines(cwd, "admission"), ENDS[model].admissions, exit.stderr);
		const [sessions] = capturedLines(cwd, "session-use");
		assert.ok(isSessionUse(sessions), exit.stderr);
		return {
			...sessions,
			modelSession: capturedLines(cwd, "model-sessions").length,
			asked: capturedLines(cwd, "model-asked").length,
		};
	} finally {
		rmSync(cwd, { recursive: true, force: true });
	}
}

function isSessionUse(value: unknown): value is { factory: number; direct: number } {
	return (
		typeof value === "object" &&
		value !== null &&
		"factory" in value &&
		typeof value.factory === "number" &&
		"direct" in value &&
		typeof value.direct === "number"
	);
}

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

	void test("the composer's exemption covers its own model client, not a Pi session past the factory", () => {
		assert.equal(
			breach(OWN_CLIENT_UNTIL_CUTOVER, { factory: 0, direct: 0, modelSession: 1 }),
			null,
		);
		assert.match(
			breach(OWN_CLIENT_UNTIL_CUTOVER, { factory: 0, direct: 1, modelSession: 1 }) ?? "",
			/past the factory/u,
		);
	});

	for (const model of ["answers", "refuses"] as const) {
		void test(`the review runner that REVIEW starts opens its sessions through openRunSession when the model ${model}`, async () => {
			const staged = await stagedReview(model);
			assert.equal(breach("REVIEW", staged), null);
			assert.ok(staged.asked > 0, "the review asked the model nothing");
		});
	}

	for (const how of ["construct", "services", "restore"]) {
		void test(`a review runner that also builds a Pi session by ${how} breaks the rule, though its review goes on`, async () => {
			const staged = await stagedReview("answers", { PI_HOST_BUILD_BESIDE: how });
			assert.match(breach("REVIEW", staged) ?? "", /past the factory/u);
			// The extra session came beside the factory's, which then reviewed to admission.
			assert.ok(staged.asked > 0, "the review asked the model nothing");
		});
	}
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
			`require("node:fs").appendFileSync(${JSON.stringify(ran)}, JSON.stringify([${JSON.stringify(program)}, ...process.argv.slice(2)]) + "\\n");`,
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
	const factoryPrompt = new AsyncLocalStorage<boolean>();
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
				const prompt = opened.session.prompt.bind(opened.session);
				opened.session.prompt = async (...promptArgs) =>
					factoryPrompt.run(true, async () => prompt(...promptArgs));
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
	const failures = {
		refuses: [400, "refused"],
		times_out: [403, "draft_deadline: time ran out"],
		stalls: [504, "upstream_stalled: provider went silent"],
		throttled: [429, "draft_unavailable: provider rate limit"],
		provider_error: [502, "draft_unavailable: provider failed"],
		paused: [503, "upstream_paused: provider paused"],
		capacity: [503, "draft_capacity: unavailable"],
		quota: [429, "draft_quota: daily limit reached"],
		budget: [429, "budget exhausted"],
		ai_choice: [403, "AI choice refused"],
	} as const;

	async function measure(
		kind: string,
		runner: KindRunner,
		model:
			| keyof typeof failures
			| "answers"
			| "cancelled"
			| "stream_stalls"
			| "stream_times_out" = "refuses",
	): Promise<Use & { asked: number; outsideFactory: number; outcome: unknown }> {
		built = [];
		fromFactory = new WeakSet();
		modelSessions = 0;
		let asked = 0;
		let outsideFactory = 0;
		const claim = new AbortController();
		// Only this run's own stand-in goes when it ends: the module recorders stay for every later import.
		const gateway = mock.method(globalThis, "fetch", async (input: string | URL | Request) => {
			const url = new URL(input instanceof Request ? input.url : input);
			if (url.pathname.startsWith("/internal/llm")) {
				asked += 1;
				if (factoryPrompt.getStore() !== true) {
					outsideFactory += 1;
				}
				if (model === "cancelled") {
					claim.abort();
				}
				if (model === "answers" || model === "stream_stalls" || model === "stream_times_out") {
					const chunk = {
						id: "draft",
						object: "chat.completion.chunk",
						created: 1,
						model: "draft-model",
					};
					const frame = (delta: Record<string, string>, finishReason: string | null) =>
						`data: ${JSON.stringify({ ...chunk, choices: [{ index: 0, delta, finish_reason: finishReason }] })}\n\n`;
					const partial = frame({ role: "assistant", content: '{"disposition":"ISSUE"}' }, null);
					const code = model === "stream_stalls" ? "upstream_stalled" : "draft_deadline";
					const end =
						model === "answers"
							? `${frame({}, "stop")}data: [DONE]\n\n`
							: `data: ${JSON.stringify({ error: { message: `${code}: stream ended`, type: code, code } })}\n\n`;
					return new Response(`${partial}${end}`, {
						headers: { "content-type": "text/event-stream" },
					});
				}
				const [status, message] = failures[model === "cancelled" ? "refuses" : model];
				return Response.json({ error: { message } }, { status });
			}
			if (url.pathname.endsWith("/context")) {
				if (kind === "DRAFT_REPORT") {
					return Response.json({
						instanceModelId: "5",
						system: "Draft one report.",
						prompt: "Redacted report.",
						model: {
							apiProtocol: "openai-completions",
							modelId: "draft-model",
							maxOutputTokens: 1500,
						},
					});
				}
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
				signal: claim.signal,
			});
			const factorySessions = built.filter((session) => fromFactory.has(session)).length;
			return {
				factory: factorySessions,
				direct: built.length - factorySessions,
				modelSession: modelSessions,
				asked,
				outsideFactory,
				outcome,
			};
		} finally {
			gateway.mock.restore();
		}
	}

	const inProcess = Object.entries(KINDS).filter(([kind]) => kind !== "REVIEW");

	void test("REVIEW's measurement runs its programs and builds no session of its own", async () => {
		assert.equal(KINDS.REVIEW, measureReview);
		const measured = await measure("REVIEW", measureReview);
		// The whole path ran: both programs ran, the run program with the runner's command, and the measurement completed.
		const programs: unknown[] = readFileSync(ran, "utf8")
			.trim()
			.split("\n")
			.map((line): unknown => JSON.parse(line));
		assert.deepEqual(programs, [["gateway-init.js"], ["gateway-run.js", "node", "runner.js"]]);
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

	const draft = KINDS.DRAFT_REPORT;
	assert.ok(draft);
	for (const [model, outcome] of [
		["answers", { leaseLost: false, outcome: "completed", answer: '{"disposition":"ISSUE"}' }],
		["times_out", { leaseLost: false, outcome: "completed", cause: "model_timeout" }],
		["cancelled", { leaseLost: true }],
		["stalls", { leaseLost: false, outcome: "handed_on", cause: "model_unavailable" }],
		["stream_stalls", { leaseLost: false, outcome: "handed_on", cause: "model_unavailable" }],
		["stream_times_out", { leaseLost: false, outcome: "completed", cause: "model_timeout" }],
		["throttled", { leaseLost: false, outcome: "handed_on", cause: "model_unavailable" }],
		["provider_error", { leaseLost: false, outcome: "handed_on", cause: "model_unavailable" }],
		["paused", { leaseLost: false, outcome: "handed_on", cause: "model_unavailable" }],
		["capacity", { leaseLost: false, outcome: "handed_on", cause: "model_capacity" }],
		["quota", { leaseLost: false, outcome: "completed", cause: "quota_exhausted" }],
		["refuses", { leaseLost: false, outcome: "completed", cause: "model_failed" }],
		["budget", { leaseLost: false, outcome: "completed", cause: "model_failed" }],
		["ai_choice", { leaseLost: false, outcome: "completed", cause: "model_failed" }],
	] as const) {
		void test(`a draft whose model ${model} makes exactly one call through Pi`, async () => {
			const measured = await measure("DRAFT_REPORT", draft, model);
			assert.equal(breach("DRAFT_REPORT", measured), null);
			assert.equal(measured.asked, 1);
			assert.equal(measured.outsideFactory, 0);
			assert.deepEqual(measured.outcome, outcome);
		});
	}

	void test("a draft that calls the proxy outside its opened session breaks the rule", async () => {
		const measured = await measure("DRAFT_REPORT", async (run) => {
			await fetch(`${run.gateway.url}/internal/llm/chat/completions`);
			return draft(run);
		});
		assert.equal(measured.factory, 1);
		assert.match(breach("DRAFT_REPORT", measured) ?? "", /outside its Pi session/u);
	});

	/** A runner that imports the composer's model client when it runs and builds one, as a kind's own loop would. */
	const ownLoop: KindRunner = async () => {
		const { ModelSession } = await import("../../../main/resources/agent/review-composer-model.ts");
		const own = new ModelSession(
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

	/** A turn runner that builds a second Pi session itself while its call to the factory is still open. */
	const alongsideFactory: KindRunner = async (run) => {
		const reload: unknown = Reflect.get(sdk.DefaultResourceLoader.prototype, "reload");
		assert.ok(typeof reload === "function");
		// The factory loads its resources before it builds its session: the second build lands inside the call. That
		// build loads resources too, so only the first load builds.
		let besideBuilt = false;
		const loading = mock.method(
			sdk.DefaultResourceLoader.prototype,
			"reload",
			async function reloadAfterBuild(this: InstanceType<typeof sdk.DefaultResourceLoader>) {
				if (!besideBuilt) {
					besideBuilt = true;
					await buildPastFactory();
				}
				return Reflect.apply(reload, this, []) as unknown;
			},
		);
		try {
			const turn = KINDS.TURN;
			assert.ok(turn);
			return await turn(run);
		} finally {
			loading.mock.restore();
		}
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
		const loading = mock.method(sdk.DefaultResourceLoader.prototype, "reload", async () => {
			throw new Error("the factory failed");
		});
		try {
			const turn = KINDS.TURN;
			assert.ok(turn);
			return await turn(run);
		} finally {
			loading.mock.restore();
		}
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
