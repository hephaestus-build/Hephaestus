import { browser } from "@wxt-dev/browser";
import { DefaultChatTransport } from "ai";

import { mentorTurnBody } from "@/lib/mentor-turn";
import type { ChatMessage } from "@/lib/types";
import { MENTOR_TURN_PORT, turnEventSchema, turnRequestSchema } from "~/shared/mentor";

const LOST = "The connection to Hephaestus was lost, so the reply stopped.";

function aborted(): DOMException {
	return new DOMException("The reply was stopped.", "AbortError");
}

/**
 * A `fetch` for the AI SDK that never touches the network: it hands the SDK's request body to the
 * worker over a port and builds the response from what the worker relays — status, then the stream's
 * bytes as they arrive, then its end. The worker owns the address, the credential and the headers, so
 * nothing here is sent anywhere but the worker. Aborting closes the port, which is how the reader
 * stops a reply: the worker aborts its request and the server ends the turn.
 */
export async function workerFetch(
	_input: RequestInfo | URL,
	init?: RequestInit,
): Promise<Response> {
	const signal = init?.signal ?? undefined;
	if (signal?.aborted === true) {
		throw aborted();
	}
	const body: unknown = typeof init?.body === "string" ? JSON.parse(init.body) : undefined;
	const request = turnRequestSchema.parse({ type: "start", body });
	const port = browser.runtime.connect({ name: MENTOR_TURN_PORT });
	const reply = Promise.withResolvers<Response>();
	const encoder = new TextEncoder();
	let controller: ReadableStreamDefaultController<Uint8Array> | undefined;
	let phase: "headers" | "body" | "closed" = "headers";
	const close = () => {
		signal?.removeEventListener("abort", onAbort);
		port.disconnect();
	};
	const stream = new ReadableStream<Uint8Array>({
		start(start) {
			controller = start;
		},
		cancel() {
			phase = "closed";
			close();
		},
	});
	const fail = (error: Error) => {
		if (phase === "closed") {
			return;
		}
		if (phase === "headers") {
			reply.reject(error);
		}
		phase = "closed";
		controller?.error(error);
		close();
	};
	const onAbort = () => {
		fail(aborted());
	};
	signal?.addEventListener("abort", onAbort, { once: true });
	port.onDisconnect.addListener(() => {
		fail(new Error(LOST));
	});
	port.onMessage.addListener((message: unknown) => {
		if (phase === "closed") {
			return;
		}
		const parsed = turnEventSchema.safeParse(message);
		if (!parsed.success) {
			fail(new Error(LOST));
			return;
		}
		const event = parsed.data;
		if (event.type === "failed") {
			fail(new Error(event.message));
			return;
		}
		if (event.type === "response" && phase === "headers") {
			try {
				const response = new Response(stream, {
					status: event.status,
					headers: { "Content-Type": event.contentType },
				});
				phase = "body";
				reply.resolve(response);
			} catch {
				fail(new Error(LOST));
			}
			return;
		}
		if (event.type === "chunk" && phase === "body") {
			controller?.enqueue(encoder.encode(event.text));
			return;
		}
		if (event.type === "end" && phase === "body") {
			phase = "closed";
			controller?.close();
			close();
			return;
		}
		fail(new Error(LOST));
	});
	try {
		port.postMessage(request);
	} catch {
		fail(new Error(LOST));
	}
	return reply.promise;
}

/**
 * The AI SDK's own transport for one conversation, with the request body every mentor client sends
 * (`mentorTurnBody`) and `workerFetch` in place of the network.
 */
export function mentorTransport(threadId: string): DefaultChatTransport<ChatMessage> {
	return new DefaultChatTransport<ChatMessage>({
		// Never requested: `workerFetch` hands the body to the worker, which owns the address.
		api: "mentor-turn",
		fetch: workerFetch,
		prepareSendMessagesRequest: (options) => ({ body: mentorTurnBody(options, threadId) }),
	});
}
