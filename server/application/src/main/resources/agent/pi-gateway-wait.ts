import { setTimeout as sleep } from "node:timers/promises";

import { createAssistantMessageEventStream, type AssistantMessage } from "@earendil-works/pi-ai";
import type { AgentSession } from "@earendil-works/pi-coding-agent";

import { diagnose } from "./pi-diagnostics.ts";
import { errorText } from "./pi-error-text.ts";
import { gatewayConnectionFailed } from "./work/practice-scripts/lib/core/gateway-connection.ts";

type Stream = AgentSession["agent"]["streamFunction"];

/** The lease wrapper stops the process at lease expiry; this wait also obeys the model call's cancellation and work bound. */
export function waitForGateway(stream: Stream, workDeadline: number): Stream {
	return async (model, context, options) => {
		const deadline = AbortSignal.timeout(Math.max(0, workDeadline - Date.now()));
		const signal =
			options?.signal === undefined ? deadline : AbortSignal.any([options.signal, deadline]);
		const isAborted = () => signal.aborted;
		const transport = options?.fetch ?? globalThis.fetch;
		let disconnected = false;
		const wasDisconnected = () => disconnected;
		const fetchGateway: typeof fetch = async (input, init) => {
			disconnected = false;
			let response: Response;
			try {
				response = await transport(input, init);
			} catch (error) {
				disconnected = !isAborted() && gatewayConnectionFailed(error);
				throw error;
			}
			if (response.body === null) {
				return response;
			}
			const reader = response.body.getReader();
			const body = new ReadableStream<Uint8Array>({
				async pull(controller) {
					try {
						const next = await reader.read();
						if (next.done) {
							reader.releaseLock();
							controller.close();
						} else {
							controller.enqueue(next.value);
						}
					} catch (error) {
						disconnected = !isAborted() && gatewayConnectionFailed(error);
						reader.releaseLock();
						controller.error(error);
					}
				},
				async cancel(reason) {
					try {
						await reader.cancel(reason);
					} finally {
						reader.releaseLock();
					}
				},
			});
			return new Response(body, {
				status: response.status,
				statusText: response.statusText,
				headers: response.headers,
			});
		};
		const call = async () => stream(model, context, { ...options, signal, fetch: fetchGateway });
		let current = await call();
		const output = createAssistantMessageEventStream();
		void (async () => {
			let started = false;
			let lastFailure: AssistantMessage | undefined;
			let waits = 0;
			try {
				for (;;) {
					let again = false;
					for await (const event of current) {
						if (event.type === "error" && wasDisconnected() && !isAborted()) {
							lastFailure = event.error;
							again = true;
							break;
						}
						// Agent core appends on start; replay partials replace that same assistant message.
						const repeatedStart = event.type === "start" && started;
						started ||= event.type === "start";
						if (!repeatedStart) {
							output.push(event);
						}
					}
					if (!again) {
						output.end(await current.result());
						return;
					}
					waits += 1;
					diagnose("gateway.waiting", { waits });
					await sleep(1000, undefined, { signal });
					current = await call();
				}
			} catch (error) {
				const previous = lastFailure ?? (await current.result());
				const stopReason = isAborted() ? "aborted" : "error";
				const failed: AssistantMessage = {
					...previous,
					content: [],
					diagnostics: [],
					stopReason,
					errorMessage: errorText(error),
				};
				output.push({ type: "error", reason: stopReason, error: failed });
				output.end();
			}
		})();
		return output;
	};
}
