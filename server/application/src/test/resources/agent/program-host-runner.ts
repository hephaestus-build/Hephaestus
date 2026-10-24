import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import {
	appendFileSync,
	existsSync,
	mkdirSync,
	readFileSync,
	symlinkSync,
	writeFileSync,
} from "node:fs";
import nodePath from "node:path";
import { mock } from "node:test";
import { setTimeout as sleep } from "node:timers/promises";

import {
	createBashToolDefinition,
	type ExtensionFactory,
	type ToolDefinition,
} from "@earendil-works/pi-coding-agent";

import {
	AUTHENTICATION_PRACTICE,
	GOLDEN_INPUTS,
	GOLDEN_RECORD,
	PRACTICES_DIR,
	stageGoldenWorkspace,
} from "./golden-review-workspace.ts";

const LIB = nodePath.resolve(
	import.meta.dirname,
	"../../../../../../docker/agents/practice-scripts/lib",
);

export const INVALID_META_PROGRAM = `import { definePracticeScript } from "../lib/core/practice-script.ts";
export default definePracticeScript({
    meta: { limits: {} },
    async run() { return { outcome: "MET" }; },
});
`;

export function codingAgentExports(
	cwd: string,
	manager: unknown,
	onResourceLoad?: (options: {
		systemPrompt: string;
		agentsFilesOverride?: () => unknown;
		extensionFactories?: ExtensionFactory[];
	}) => void,
	beforeRuntime?: () => void,
) {
	return {
		defineTool: (tool: unknown) => tool,
		createBashToolDefinition,
		estimateTokens: (message: { content: string }) => Math.ceil(message.content.length / 4),
		createCodemodeExtension: () => () => undefined,
		getAgentDir: () => cwd,
		DefaultResourceLoader: class {
			constructor(options: {
				systemPrompt: string;
				agentsFilesOverride?: () => unknown;
				extensionFactories?: ExtensionFactory[];
			}) {
				onResourceLoad?.(options);
			}
			readonly loaded = Promise.resolve();
			async reload() {
				await this.loaded;
			}
		},
		SettingsManager: { create: () => ({}) },
		SessionManager: { create: () => manager, inMemory: () => manager, open: () => manager },
		ModelRuntime: {
			async create() {
				beforeRuntime?.();
				return {
					registerProvider: () => undefined,
					getModel: () => ({ id: "test-model", contextWindow: 128_000, maxTokens: 16_384 }),
				};
			},
		},
	};
}

export function mockPiCodingAgent(
	cwd: string,
	manager: unknown,
	overrides: Record<string, unknown>,
	onResourceLoad?: Parameters<typeof codingAgentExports>[2],
	beforeRuntime?: Parameters<typeof codingAgentExports>[3],
) {
	mock.module("@earendil-works/pi-coding-agent", {
		namedExports: {
			...codingAgentExports(cwd, manager, onResourceLoad, beforeRuntime),
			...overrides,
		},
	});
}

export async function mockChange(overrides: Record<string, unknown>) {
	const change = new URL("../../../main/resources/agent/pi-change.ts", import.meta.url).href;
	const derived: unknown = await import(change);
	assert.ok(typeof derived === "object" && derived !== null);
	mock.module(change, { namedExports: { ...derived, ...overrides } });
}

export type SessionBehaviour = "submit" | "read-change" | "silent" | "hang" | "budget" | "crash";

export interface StagedPractice {
	slug: string;
	program?: string;
	inputs?: boolean;
}

export const SESSION_CANDIDATE = {
	outcome: "NOT_MET",
	summary: "Added authentication call is insecure",
	explanation: "The changed authentication code calls insecure().",
	citations: [],
	problems: [
		{
			severity: "MAJOR",
			summary: "Login calls insecure() on every sign-in",
			explanation: "The added line calls insecure() where the criteria ask for checked().",
			citations: [
				{
					sourceKind: "scm.pull-request.core",
					sourcePath: "evidence/metadata.json",
					path: "evidence/metadata.json",
					startLine: 1,
					endLine: 1,
					quote: JSON.stringify(GOLDEN_RECORD),
				},
			],
			basis: {
				type: "duty",
				criterionQuote: "Added authentication calls use checked(), not insecure().",
				event: 0,
				explanation:
					"The pull request adds a sign-in, which the criteria require to use checked().",
			},
		},
	],
	evidence: {},
};

export function directProgram(before = ""): string {
	return `import { definePracticeScript, generateText, step } from "../lib/core/practice-script.ts";
export default definePracticeScript({
	meta: { models: { chat: "optional" } },
	async run(ctx) {
		let note = "none";
		${before}
		return {
			outcome: "DOES_NOT_APPLY",
			summary: \`No user interface changes; note \${note}\`,
			explanation: "The change adds no view, template or component.",
			citations: [await ctx.evidence.citeRecord("metadata.json", 1)],
			evidence: {
				inapplicability: {
					consulted: ["scm.pull-request.core"],
					prerequisite: "user interface views",
					ruledOutBy: "The record names one changed Java file.",
				},
			},
			coverage: { source: "claimed", selected: 1, checked: 1, total: 1, complete: true },
		};
	},
});
`;
}

export function stagePractices(cwd: string, practices: readonly StagedPractice[]): void {
	stageGoldenWorkspace(
		cwd,
		6,
		readFileSync(
			new URL("../../../main/resources/agent/pi-orchestrator.md", import.meta.url),
			"utf8",
		),
	);
	const write = (file: string, text: string) => {
		mkdirSync(nodePath.dirname(nodePath.join(cwd, file)), { recursive: true });
		writeFileSync(nodePath.join(cwd, file), text);
	};
	write("package.json", '{"type":"module"}\n');
	symlinkSync(LIB, nodePath.join(cwd, nodePath.dirname(PRACTICES_DIR), "lib"));
	symlinkSync(
		nodePath.resolve(LIB, "../kits"),
		nodePath.join(cwd, nodePath.dirname(PRACTICES_DIR), "kits"),
	);
	write(
		"catalog/practices/index.json",
		JSON.stringify(
			practices.map(({ slug }) => ({
				slug,
				name: "Authentication review",
				group: "security",
				readsSources: ["scm.pull-request.core", "scm.pull-request.diff"],
				exhaustiveSources: ["scm.pull-request.diff"],
				versionId: "7",
			})),
		),
	);
	for (const { slug, program, inputs = true } of practices) {
		write(`catalog/practices/${slug}.md`, AUTHENTICATION_PRACTICE);
		if (inputs) {
			write(
				`${PRACTICES_DIR}/${slug}.inputs.json`,
				JSON.stringify({ ...GOLDEN_INPUTS, practice: { ...GOLDEN_INPUTS.practice, slug } }),
			);
		}
		if (program !== undefined) {
			write(`${PRACTICES_DIR}/${slug}.ts`, program);
		}
	}
}

export interface RunnerExit {
	status: number | null;
	stderr: string;
}

// Run in a child process so the test process can keep answering proxy requests.
export async function runRunner(
	cwd: string,
	sessions: Record<string, SessionBehaviour>,
	env: Record<string, string> = {},
): Promise<RunnerExit> {
	const firstCompletion = env.PI_HOST_FIRST_COMPLETION;
	const child = spawn(
		process.execPath,
		["--experimental-test-module-mocks", import.meta.filename],
		{
			env: {
				...process.env,
				PI_HOST_SCENARIO: JSON.stringify(sessions),
				PI_RUNNER_CWD: cwd,
				PI_CODING_AGENT_DIR: cwd,
				AGENT_BUDGET_MS: "60000",
				PI_PRACTICE_MODEL_CALLS: "12",
				PI_PRACTICE_CONCURRENCY: "1",
				PI_PRACTICE_OUTPUT_TOKENS: "16000",
				PI_DIRECT_MODEL_TOKENS: "200000",
				LLM_PROXY_URL: "https://unused.invalid",
				LLM_PROXY_TOKEN: "job-token",
				PI_COMPOSITION_PHASES: "PUBLIC_REVIEW,PRIVATE_FEEDBACK",
				...env,
			},
			stdio: [
				"ignore",
				"ignore",
				"pipe",
				...(firstCompletion === undefined ? [] : ["ipc" as const]),
			],
		},
	);
	let stderr = "";
	let released = false;
	assert.ok(child.stderr !== null);
	child.stderr.setEncoding("utf8");
	child.stderr.on("data", (chunk: string) => {
		stderr += chunk;
		if (
			firstCompletion !== undefined &&
			!released &&
			["built-in", "custom"].some((kind) =>
				stderr.includes(`[pi-runner] ${firstCompletion}: ${kind} program `),
			)
		) {
			released = true;
			child.send("first-program-completed");
		}
	});
	const timer = setTimeout(() => {
		child.kill("SIGKILL");
	}, 60_000);
	await once(child, "close");
	clearTimeout(timer);
	return { status: child.exitCode, stderr };
}

const parseJson = (text: string): unknown => JSON.parse(text);
const hasText = (value: string | undefined): value is string => value !== undefined && value !== "";

export function readJsonFile(cwd: string, file: string): unknown {
	return parseJson(readFileSync(nodePath.join(cwd, file), "utf8"));
}

export function field(value: unknown, key: string): unknown {
	if (typeof value !== "object" || value === null) {
		return undefined;
	}
	const found: unknown = Reflect.get(value, key);
	return found;
}

export function capturedLines(cwd: string, name: string): unknown[] {
	const file = nodePath.join(cwd, "captured", `${name}.jsonl`);
	return existsSync(file)
		? readFileSync(file, "utf8")
				.split("\n")
				.filter((line) => line !== "")
				.map(parseJson)
		: [];
}

const scenario = process.env.PI_HOST_SCENARIO;
if (scenario !== undefined && scenario !== "") {
	const firstCompletion = process.env.PI_HOST_FIRST_COMPLETION;
	if (firstCompletion !== undefined) {
		const released = Promise.withResolvers<undefined>();
		process.once("message", (message) => {
			assert.equal(message, "first-program-completed");
			process.disconnect();
			released.resolve(undefined);
		});
		const host =
			await import("../../../../../../docker/agents/practice-scripts/lib/core/program-host.ts");
		mock.module(nodePath.join(LIB, "core/program-host.ts"), {
			namedExports: {
				...host,
				runPracticeProgram: async (...args: Parameters<typeof host.runPracticeProgram>) => {
					const outcome = await host.runPracticeProgram(...args);
					// Keep both programs real; release the later outcome only after the first completed.
					if (args[1].slug !== firstCompletion) {
						await released.promise;
					}
					return outcome;
				},
			},
		});
	}
	const cwd = process.env.PI_RUNNER_CWD;
	assert.ok(cwd !== undefined && cwd !== "");
	const sessions: unknown = JSON.parse(scenario);
	assert.ok(typeof sessions === "object" && sessions !== null);
	const behaviourOf = (slug: string): unknown => field(sessions, slug) ?? "submit";
	const contextRoot = field(
		parseJson(readFileSync(nodePath.join(cwd, "task.json"), "utf8")),
		"contextRoot",
	);
	assert.ok(typeof contextRoot === "string");
	const metadataPath = `${contextRoot}/metadata.json`;
	const recordPath = hasText(process.env.PI_HOST_SCAN_PATH)
		? `${contextRoot}/item.json`
		: metadataPath;
	mkdirSync(nodePath.join(cwd, "captured"), { recursive: true });
	const capture = (name: string, value: unknown) => {
		appendFileSync(nodePath.join(cwd, "captured", `${name}.jsonl`), `${JSON.stringify(value)}\n`);
	};

	await mockChange({
		writeChangeView: () => {
			capture("change-view", true);
		},
	});
	const realFetch = globalThis.fetch;
	mock.method(globalThis, "fetch", async (input: string | URL | Request, init?: RequestInit) => {
		const url = input instanceof Request ? input.url : String(input);
		if (!url.endsWith("/admit-results")) {
			return realFetch(input, init);
		}
		assert.ok(typeof init?.body === "string");
		capture("admission", {
			body: parseJson(init.body),
			reviewInputPresent: existsSync(nodePath.join(cwd, "work/review-input")),
		});
		return Response.json({
			contractVersion: 4,
			admissionDigest: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			publicReviewed: false,
			privateProgress: {
				decidedResultIds: [],
				practicePageDeliveries: 0,
				conversationDeliveries: 0,
			},
			admitted: [],
			withheld: [],
			practicePageSupport: null,
			conversationEligibility: null,
		});
	});

	const manager = {
		appendCustomEntry: () => undefined,
		getSessionFile: () => undefined,
		getSessionId: () => "host-session",
		buildSessionProjection: () => ({ messages: [] }),
	};
	interface CustomTool {
		name: string;
		parameters: ToolDefinition["parameters"];
		prepareArguments?: (args: unknown) => unknown;
		execute: (id: string, input: unknown) => Promise<unknown>;
	}
	let sessionCount = 0;
	mockPiCodingAgent(cwd, manager, {
		async createAgentSession(options: { customTools: CustomTool[] }) {
			sessionCount += 1;
			const session = sessionCount;
			const handlers = new Set<(event: unknown) => void>();
			const emit = (event: unknown) => {
				for (const handler of handlers) {
					handler(event);
				}
			};
			const submit = options.customTools.find((tool) => tool.name === "submit_result");
			assert.ok(submit, "submit_result is registered");
			async function inspectShell() {
				const shellCommand = process.env.PI_HOST_BASH_COMMAND;
				if (hasText(shellCommand)) {
					const bashTool = options.customTools.find((tool) => tool.name === "bash");
					assert.ok(bashTool);
					emit({
						type: "tool_execution_start",
						toolCallId: "shell",
						toolName: "bash",
						args: { command: shellCommand },
					});
					emit({
						type: "tool_execution_end",
						toolCallId: "shell",
						toolName: "bash",
						isError: false,
						result: await bashTool.execute("shell", { command: shellCommand }),
					});
				}
			}

			const submitCandidate = async (sent: unknown, slug: string, allowFailure: boolean) => {
				try {
					let argumentsToSubmit = submit.prepareArguments?.(sent) ?? sent;
					if (hasText(process.env.PI_HOST_SCAN_PATH)) {
						const payload: string = JSON.stringify({
							tool: { name: submit.name, parameters: submit.parameters },
							args: argumentsToSubmit,
						});
						const validated = spawnSync(
							process.execPath,
							[
								"--experimental-import-meta-resolve",
								"--input-type=module",
								"-e",
								`const { validateToolArguments } = await import(import.meta.resolve("@earendil-works/pi-ai", import.meta.resolve("@earendil-works/pi-coding-agent")));
const { tool, args } = JSON.parse(process.argv[1]);
process.stdout.write(JSON.stringify(validateToolArguments(tool, { name: tool.name, arguments: args })));`,
								payload,
							],
							{ encoding: "utf8" },
						);
						assert.equal(validated.status, 0, validated.stderr);
						argumentsToSubmit = parseJson(validated.stdout);
					}
					const reply = await submit.execute(`submit-${session}`, argumentsToSubmit);
					capture("submitted", { slug, reply });
				} catch (error) {
					if (!allowFailure) {
						throw error;
					}
					capture("submitted", {
						slug,
						reply: {
							isError: true,
							content: [
								{ type: "text", text: error instanceof Error ? error.message : String(error) },
							],
						},
					});
				}
			};

			async function submitSessionResult(slug: string): Promise<void> {
				const candidate: unknown = hasText(process.env.PI_HOST_SESSION_CANDIDATE)
					? parseJson(process.env.PI_HOST_SESSION_CANDIDATE)
					: SESSION_CANDIDATE;
				const correction = process.env.PI_HOST_SESSION_CORRECTION;
				const candidates = hasText(correction) ? [candidate, parseJson(correction)] : [candidate];
				if (process.env.PI_HOST_PROBLEM_DRAFTS === "true") {
					assert.ok(typeof candidate === "object" && candidate !== null);
					const problems = field(candidate, "problems");
					assert.ok(Array.isArray(problems));
					const upsert = options.customTools.find((tool) => tool.name === "upsert_problem");
					assert.ok(upsert);
					for (const problem of problems) {
						const reply = await upsert.execute(
							"draft",
							upsert.prepareArguments?.(problem) ?? problem,
						);
						capture("problem-drafts", { slug, reply });
					}
					const keyed = Object.fromEntries(
						Object.entries(candidate).filter(([key]) => key !== "problems"),
					);
					await submitCandidate(
						{
							...keyed,
							problemKeys: problems.map((problem: unknown) => field(problem, "key")),
						},
						slug,
						false,
					);
					capture("model-finish", { slug, at: Date.now() });
					return;
				}
				for (const sent of candidates) {
					await submitCandidate(sent, slug, hasText(correction));
					capture("model-finish", { slug, at: Date.now() });
				}
			}

			let sessionSlug: string | undefined;
			let aborted = false;
			const isAborted = () => aborted;
			const agent = {
				state: { tools: [] },
				finishTurn: async () => ({ action: "continue" }),
				streamFunction: (
					_model: unknown,
					_context: unknown,
					requestOptions?: { maxTokens?: number },
				) => {
					capture("requests", { slug: sessionSlug, maxTokens: requestOptions?.maxTokens });
					return requestOptions?.maxTokens ?? 100;
				},
			};
			return {
				extensionsResult: { errors: [] },
				session: {
					state: { messages: [] },
					agent,
					systemPrompt: "",
					model: { contextWindow: 128_000, maxTokens: 16_384 },
					settingsManager: { getCompactionSettings: () => ({ reserveTokens: 16_384 }) },
					sessionManager: manager,
					subscribe(handler: (event: unknown) => void) {
						handlers.add(handler);
						return () => {
							handlers.delete(handler);
						};
					},
					clearQueue: () => undefined,
					pendingMessageCount: 0,
					getContextUsage: () => ({ tokens: 1000, contextWindow: 128_000, percent: 0 }),
					compact: async () => ({}),
					isIdle: true,
					isStreaming: false,
					waitForIdle: async () => undefined,
					abortCompaction: () => undefined,
					abort: async () => {
						aborted = true;
						if (sessionSlug === process.env.PI_HOST_STOP_FAILURE) {
							throw new Error("session stop failed");
						}
					},
					dispose: () => undefined,
					steer: async () => undefined,
					async prompt(
						text: string,
						promptOptions?: { preflightResult?: (disposition: string) => void },
					) {
						promptOptions?.preflightResult?.("started");
						sessionSlug ??= /Submit one result for (?<slug>[a-z0-9-]+) with submit_result/u.exec(
							text,
						)?.groups?.slug;
						const slug = sessionSlug;
						assert.ok(slug !== undefined, "the session's first turn names its practice");
						const behaviour = behaviourOf(slug);
						const delays = parseJson(process.env.PI_HOST_DELAYS ?? "{}");
						const delay = field(delays, slug);
						capture("model-start", { slug, at: Date.now() });
						if (typeof delay === "number") {
							await sleep(delay);
						}
						if (behaviour === "budget") {
							while (!isAborted()) {
								const output = Math.min(100, agent.streamFunction({ maxTokens: 16_384 }, {}, {}));
								emit({
									type: "message_end",
									message: {
										role: "assistant",
										stopReason: "toolUse",
										content: [],
										usage: { input: 100, output, cacheRead: 0, cacheWrite: 0, cost: { total: 0 } },
									},
								});
								emit({ type: "turn_end" });
								await sleep(10);
							}
							return;
						}
						capture("prompts", {
							session,
							slug,
							reviewInput: existsSync(nodePath.join(cwd, `work/review-input/${slug}.json`)),
							changeSupplied: text.includes("### `work/change/diff.patch`"),
						});
						if (behaviour === "hang") {
							await Promise.withResolvers<never>().promise;
						}
						// One answered call, then the process fails outside every runner catch.
						if (behaviour === "crash") {
							emit({
								type: "message_end",
								message: {
									role: "assistant",
									stopReason: "stop",
									content: [{ type: "text", text: "Reading." }],
									usage: {
										input: 100,
										output: 10,
										cacheRead: 0,
										cacheWrite: 0,
										cost: { total: 0 },
									},
								},
							});
							setImmediate(() => {
								throw new Error("the host crashed during a review session");
							});
							await Promise.withResolvers<never>().promise;
						}
						await inspectShell();
						const readPath = behaviour === "read-change" ? "work/change/diff.patch" : recordPath;
						capture("reads", { slug, path: readPath });
						emit({
							type: "tool_execution_start",
							toolCallId: `read-${session}`,
							toolName: "read",
							args: { path: readPath },
						});
						emit({
							type: "tool_execution_end",
							toolCallId: `read-${session}`,
							toolName: "read",
							result: {
								content: [
									{ type: "text", text: readFileSync(nodePath.join(cwd, readPath), "utf8") },
								],
								details: {},
							},
							isError: false,
						});
						if (hasText(process.env.PI_HOST_SCAN_PATH)) {
							const read = options.customTools.find((tool) => tool.name === "read");
							assert.ok(read, "the governed scan reader is registered");
							capture(
								"scan-read",
								await read.execute("scan-read", { path: process.env.PI_HOST_SCAN_PATH }),
							);
						}
						agent.streamFunction({ maxTokens: 16_384 }, {}, {});
						const submits = behaviour === "submit" || behaviour === "read-change";
						emit({
							type: "message_end",
							message: {
								role: "assistant",
								stopReason: submits ? "toolUse" : "stop",
								usage: {
									input: 1000,
									output: 100,
									cacheRead: 0,
									cacheWrite: 0,
									cost: { total: 0 },
								},
								content: submits
									? [
											{
												type: "toolCall",
												id: `submit-${session}`,
												name: "submit_result",
												arguments: {},
											},
										]
									: [{ type: "text", text: "Done." }],
							},
						});
						emit({ type: "turn_end" });
						if (submits) {
							await submitSessionResult(slug);
						}
					},
				},
			};
		},
	});
	const { runReviewJob } = await import("../../../main/resources/agent/pi-runner.ts");
	await runReviewJob();
}
