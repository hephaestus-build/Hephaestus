/**
 * One practice script in its own process. The process may not start a program and holds no
 * credential, so its searches and model calls go to the runner.
 */
import { once } from "node:events";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

import { callLabel } from "./lib/call-label.ts";
import { evidenceTools } from "./lib/evidence-tools.ts";
import { delegateGrep } from "./lib/grep.ts";
import {
	MAX_LABEL_CHARS,
	MAX_LOG_CHARS,
	type ChildJob,
	type ChildMessage,
	type RunnerMessage,
} from "./lib/ipc.ts";
import { isJsonObject } from "./lib/json.ts";
import { ipcModels } from "./lib/models-ipc.ts";
import { isPracticeModule } from "./lib/practice-contract.ts";
import { UnratedError, type ModelNeeds, type PrecomputeDefinition } from "./lib/precompute.ts";

const send = process.send?.bind(process);
if (send === undefined) {
	throw new Error("child.ts runs only as a process forked by runner.ts");
}
const post = (message: ChildMessage) => send(message);

/** Send the last message. A disconnect before a large message has left this process drops it. */
const postLast = promisify((message: ChildMessage, sent: (error: Error | null) => void) => {
	send(message, undefined, undefined, sent);
});

/** Replies the runner owes this child, by request id. */
const pending = new Map<number, (message: RunnerMessage) => void>();
let nextId = 0;
const metaReply = Promise.withResolvers<Extract<RunnerMessage, { kind: "meta-accepted" }>>();
let tokensLeft = 0;

/** The runner's own messages are trusted, so only the field that routes them is checked. */
function isRunnerMessage(value: unknown): value is RunnerMessage {
	return isJsonObject(value) && typeof value.kind === "string";
}

const jobArrived = once(process, "message");
process.on("message", (message: unknown) => {
	if (!isRunnerMessage(message) || message.kind === "job") {
		return;
	}
	if (message.kind === "meta-accepted") {
		tokensLeft = message.tokens;
		metaReply.resolve(message);
		return;
	}
	const waiter = pending.get(message.id);
	pending.delete(message.id);
	waiter?.(message);
});

async function request(make: (id: number) => ChildMessage): Promise<RunnerMessage> {
	const id = nextId;
	nextId += 1;
	const reply = Promise.withResolvers<RunnerMessage>();
	pending.set(id, reply.resolve);
	post(make(id));
	return reply.promise;
}

delegateGrep(async (pattern, dir, opts) => {
	const reply = await request((id) => ({ kind: "grep", id, pattern, dir, opts }));
	if (reply.kind === "grep") {
		return reply.matches;
	}
	throw new Error(reply.kind === "grep-failed" ? reply.message : "unexpected reply to a search");
});

function isDefinition(value: unknown): value is PrecomputeDefinition<ModelNeeds> {
	return isJsonObject(value) && typeof value.run === "function" && isJsonObject(value.meta);
}

async function runDefinition(
	job: ChildJob,
	definition: PrecomputeDefinition<ModelNeeds>,
): Promise<unknown> {
	post({ kind: "meta", meta: definition.meta });
	const accepted = await metaReply.promise;
	const deadline = AbortSignal.timeout(Math.max(0, job.deadline - Date.now()));
	const models = ipcModels(accepted.models, async (slot, options, signal) => {
		signal?.throwIfAborted();
		const answered = new AbortController();
		const reply = await request((id) => {
			signal?.addEventListener(
				"abort",
				() => {
					post({ kind: "cancel", id });
				},
				{ once: true, signal: answered.signal },
			);
			const label = (callLabel.getStore() ?? "").slice(0, MAX_LABEL_CHARS);
			return { kind: "model", id, slot, label, options };
		}).finally(() => {
			answered.abort();
		});
		if (reply.kind === "model") {
			tokensLeft = reply.tokensLeft;
			return reply.answer;
		}
		if (reply.kind === "model-failed") {
			tokensLeft = reply.tokensLeft;
			// The AI SDK's `maxRetries` retries only an `APICallError`, so a precompute call runs once.
			throw new UnratedError(reply.reason, reply.message);
		}
		throw new Error("unexpected reply to a model call");
	});
	return definition.run({
		practice: { slug: job.slug },
		repo: job.repoPath,
		change: job.diffFiles,
		changeDir: job.changeDir,
		metadata: job.metadata,
		context: { dir: job.contextDir, reference: job.contextReference },
		now: job.now,
		models,
		tools: evidenceTools(job.repoPath),
		remaining: () => ({ tokens: tokensLeft, ms: Math.max(0, job.deadline - Date.now()) }),
		signal: deadline,
		log: (text) => {
			post({ kind: "log", text: text.slice(0, MAX_LOG_CHARS) });
		},
	});
}

const arrived: readonly unknown[] = await jobArrived;
const first = arrived[0];
if (!isRunnerMessage(first) || first.kind !== "job") {
	throw new Error("the runner sends the job first");
}
const { job } = first;
try {
	const mod: unknown = await import(pathToFileURL(job.modulePath).href);
	const exported: unknown = isJsonObject(mod) ? mod.default : undefined;
	if (isDefinition(exported)) {
		await postLast({ kind: "result", value: await runDefinition(job, exported) });
	} else if (isPracticeModule(mod)) {
		const value: unknown = await mod.default(
			job.repoPath,
			job.diffFiles,
			job.metadata,
			job.contextDir,
			job.changeDir,
			job.contextReference,
		);
		await postLast({ kind: "result", value });
	} else {
		throw new Error("the script must export a default function or a definePrecompute definition");
	}
} catch (error) {
	// A practice script is foreign code (DB-stored data), so it can throw a non-Error value.
	await postLast({
		kind: "failed",
		message: error instanceof Error ? error.message : String(error),
	});
}
process.disconnect();
