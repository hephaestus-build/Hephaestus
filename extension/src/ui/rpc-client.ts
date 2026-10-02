import { browser } from "@wxt-dev/browser";

import {
	eventSchema,
	type RpcError,
	type RpcEvent,
	type RpcRequest,
	type RpcResponses,
	type RpcResult,
} from "~/shared/rpc";

export class RpcClientError extends Error {
	readonly code: RpcError["code"];

	constructor(error: RpcError) {
		super(error.message);
		this.name = "RpcClientError";
		this.code = error.code;
	}
}

let latestGeneration = -1;
const listeners = new Set<(event: RpcEvent) => void>();

/** The newest generation this view has heard of; anything older it is holding is out of date. */
export function knownGeneration(): number {
	return latestGeneration;
}

function learnGeneration(generation: number): void {
	latestGeneration = Math.max(latestGeneration, generation);
}

/**
 * Asks the worker. An answer computed under a generation older than one this view has already heard
 * about is thrown away as `stale` — the event that announced the newer one also triggered a refetch.
 */
export async function ask<K extends RpcRequest["type"]>(
	request: Extract<RpcRequest, { type: K }>,
): Promise<RpcResponses[K]> {
	// `sendMessage` is typed `any`: the worker's reply is the RpcResult it built for this command.
	const result: RpcResult<RpcResponses[K]> | undefined = await browser.runtime.sendMessage(request);
	if (result === undefined) {
		throw new RpcClientError({ code: "network", message: "The extension did not answer." });
	}
	if (result.generation < latestGeneration) {
		throw new RpcClientError({ code: "stale", message: "Out of date." });
	}
	learnGeneration(result.generation);
	if (!result.ok) {
		throw new RpcClientError(result.error);
	}
	return result.data;
}

let listening = false;

/** Subscribes to worker events, accepting them only from this extension's worker. */
export function onWorkerEvent(listener: (event: RpcEvent) => void): () => void {
	if (!listening) {
		listening = true;
		browser.runtime.onMessage.addListener((message, sender) => {
			// Other views' requests reach this listener too; only the worker's broadcasts count.
			if (
				sender.id !== browser.runtime.id ||
				sender.tab !== undefined ||
				sender.url !== browser.runtime.getURL("/background.js")
			) {
				return;
			}
			const event = eventSchema.safeParse(message);
			if (!event.success) {
				return;
			}
			if (event.data.type === "state-changed") {
				learnGeneration(event.data.generation);
			}
			for (const notify of listeners) {
				notify(event.data);
			}
		});
	}
	listeners.add(listener);
	return () => {
		listeners.delete(listener);
	};
}
