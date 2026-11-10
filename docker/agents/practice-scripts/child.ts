// Custom scripts run without credentials and request evidence and their review through the host.
import { once } from "node:events";
import { pathToFileURL } from "node:url";
import { promisify } from "node:util";

import { NOT_A_DEFINITION_ERROR } from "./lib/core/contract.ts";
import type {
	ChildJob,
	ChildMessage,
	HostMessage,
	ReadRequest,
	ReadValues,
} from "./lib/core/ipc.ts";
import { isJsonObject } from "./lib/core/json.ts";
import {
	captureFailures,
	isPracticeScriptDefinition,
	UnratedError,
	type PracticeScriptDefinition,
} from "./lib/core/practice-script.ts";
import { programContext } from "./lib/core/program-context.ts";
import { guardScriptImports } from "./lib/core/script-imports.ts";

guardScriptImports();

const send = process.send?.bind(process);
if (send === undefined) {
	throw new Error("The program child runs only as a process forked by the program host");
}
const post = (message: ChildMessage) => send(JSON.stringify(message));

// Keep IPC connected until the final message finishes sending.
const postLast = promisify((message: ChildMessage, sent: (error: Error | null) => void) => {
	send(JSON.stringify(message), undefined, undefined, sent);
});

const pending = new Map<number, (message: HostMessage) => void>();
let nextId = 0;
const metaReply = Promise.withResolvers<undefined>();
const stopped = new AbortController();

// Host messages are trusted; only the routing field needs validation.
function isHostMessage(value: unknown): value is HostMessage {
	return isJsonObject(value) && typeof value.kind === "string";
}

const jobArrived = once(process, "message");
process.on("message", (message: unknown) => {
	if (!isHostMessage(message) || message.kind === "job") {
		return;
	}
	if (message.kind === "meta-accepted") {
		metaReply.resolve(undefined);
		return;
	}
	if (message.kind === "abort") {
		stopped.abort(
			new UnratedError(message.reason, `the host stopped this program: ${message.reason}`),
		);
		return;
	}
	const waiter = pending.get(message.id);
	pending.delete(message.id);
	waiter?.(message);
});

async function request(make: (id: number) => ChildMessage): Promise<HostMessage> {
	const id = nextId;
	nextId += 1;
	const reply = Promise.withResolvers<HostMessage>();
	pending.set(id, reply.resolve);
	post(make(id));
	return reply.promise;
}

async function read<R extends ReadRequest>(readRequest: R): Promise<ReadValues[R["op"]]>;
async function read(readRequest: ReadRequest): Promise<ReadValues[keyof ReadValues]> {
	stopped.signal.throwIfAborted();
	const reply = await request((id) => ({ kind: "read", id, request: readRequest }));
	if (reply.kind === "read-result") {
		return reply.value;
	}
	if (reply.kind === "read-failed") {
		throw new UnratedError(reply.reason, reply.message);
	}
	throw new Error("unexpected reply to a read");
}

async function runDefinition(
	job: ChildJob,
	definition: PracticeScriptDefinition,
): Promise<unknown> {
	post({ kind: "meta", meta: definition.meta });
	await metaReply.promise;
	const abortSignal = AbortSignal.any([
		stopped.signal,
		AbortSignal.timeout(Math.max(0, job.deadline - Date.now())),
	]);
	const ctx = programContext(job, {
		read,
		review: async (options) => {
			stopped.signal.throwIfAborted();
			const reply = await request((id) => ({
				kind: "review",
				id,
				options: options ?? {},
				captureFailures: [...captureFailures()],
			}));
			if (reply.kind === "review-failed") {
				throw new UnratedError(reply.reason, reply.message);
			}
			throw new Error("unexpected reply to a review");
		},
		log: (text) => {
			post({ kind: "log", text });
		},
		abortSignal,
	});
	return definition.run(ctx);
}

const arrived: readonly unknown[] = await jobArrived;
const first = arrived[0];
if (!isHostMessage(first) || first.kind !== "job") {
	throw new Error("the host sends the job first");
}
const { job } = first;
try {
	const mod: unknown = await import(pathToFileURL(job.modulePath).href);
	const exported: unknown = isJsonObject(mod) ? mod.default : undefined;
	if (!isPracticeScriptDefinition(exported)) {
		throw new Error(NOT_A_DEFINITION_ERROR);
	}
	const value = await runDefinition(job, exported);
	await postLast({ kind: "result", value, captureFailures: [...captureFailures()] });
} catch (error) {
	// A practice script is foreign code (DB-stored data), so it can throw a non-Error value.
	await postLast({
		kind: "failed",
		message: error instanceof Error ? error.message : String(error),
		...(error instanceof UnratedError ? { reason: error.reason } : {}),
	});
}
process.disconnect();
