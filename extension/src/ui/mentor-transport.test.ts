import { readUIMessageStream } from "ai";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ChatMessage } from "@/lib/types";
import { required } from "~/testing/required";
import { mentorTransport, workerFetch } from "~/ui/mentor-transport";

type Listener = (message: unknown) => void;

/** A runtime port the test plays the worker's end of. */
function fakePort() {
	const messages: Listener[] = [];
	const disconnects: (() => void)[] = [];
	const port = {
		name: "",
		posted: [] as unknown[],
		disconnected: false,
		postMessage(message: unknown) {
			port.posted.push(message);
		},
		disconnect() {
			port.disconnected = true;
		},
		onMessage: { addListener: (listener: Listener) => messages.push(listener) },
		onDisconnect: { addListener: (listener: () => void) => disconnects.push(listener) },
		/** The worker sends one event. */
		send(event: unknown) {
			for (const listener of messages) {
				listener(event);
			}
		},
		/** The worker closes its end. */
		close() {
			for (const listener of disconnects) {
				listener();
			}
		},
	};
	return port;
}

const ports = vi.hoisted(() => ({ next: undefined as ReturnType<typeof fakePort> | undefined }));

vi.mock("@wxt-dev/browser", () => ({
	browser: {
		runtime: {
			connect: (info: { name: string }) => {
				const port = required(ports.next, "a port");
				port.name = info.name;
				return port;
			},
		},
	},
}));

let port: ReturnType<typeof fakePort>;

beforeEach(() => {
	port = fakePort();
	ports.next = port;
});

const REQUEST = {
	id: "5b0f7c2e-3a1d-4e8b-9c6f-2d4e6a8b0c1e",
	message: {
		id: "0d9a3f1e-1b2c-4d5e-8f60-718293a4b5c6",
		role: "user",
		parts: [{ type: "text", text: "Hello" }],
	},
	trigger: "submit-message",
};
const BODY = JSON.stringify(REQUEST);

describe("workerFetch", () => {
	it("uses the AI SDK to reconstruct a streamed reply across worker port chunks", async () => {
		const transport = mentorTransport(REQUEST.id);
		const stream = transport.sendMessages({
			chatId: REQUEST.id,
			trigger: "submit-message",
			messageId: undefined,
			abortSignal: undefined,
			messages: [
				{ id: REQUEST.message.id, role: "user", parts: [{ type: "text", text: "Hello" }] },
			],
		});
		await vi.waitFor(() => {
			expect(port.posted).toHaveLength(1);
		});
		port.send({ type: "response", status: 200, contentType: "text/event-stream" });
		const received = await stream;
		port.send({
			type: "chunk",
			text: 'data: {"type":"start","messageId":"reply"}\n\n: ping\n\ndata: {"type":"text-start","id":"text"}\n\n',
		});
		// The SSE record itself is split across port deliveries; the SDK owns the parser.
		port.send({ type: "chunk", text: 'data: {"type":"text-delta","id":"text","delta":"Grüße' });
		port.send({
			type: "chunk",
			text: ' from Heph"}\n\ndata: {"type":"text-end","id":"text"}\n\ndata: {"type":"finish","finishReason":"stop"}\n\ndata: [DONE]\n\n',
		});
		port.send({ type: "end" });
		const replies: ChatMessage[] = [];
		for await (const message of readUIMessageStream<ChatMessage>({ stream: received })) {
			replies.push(message);
		}
		expect(replies.at(-1)).toMatchObject({
			id: "reply",
			role: "assistant",
			parts: [{ type: "text", text: "Grüße from Heph" }],
		});
		expect(port.disconnected).toBe(true);
	});

	it("does not open a port for a malformed SDK body", async () => {
		await expect(
			workerFetch("mentor-turn", { body: JSON.stringify({ url: "https://elsewhere.test" }) }),
		).rejects.toThrow("expected string");
		expect(port.name).toBe("");
	});
	it("hands only the body to the worker and builds the response from what it relays", async () => {
		const response = workerFetch("mentor-turn", { method: "POST", body: BODY });
		expect(port.name).toBe("mentor-turn");
		expect(port.posted).toStrictEqual([{ type: "start", body: REQUEST }]);
		port.send({ type: "response", status: 200, contentType: "text/event-stream" });
		port.send({ type: "chunk", text: "data: one\n\n" });
		port.send({ type: "chunk", text: ": ping\n\n" });
		port.send({ type: "end" });
		const answer = await response;
		expect(answer.status).toBe(200);
		expect(answer.headers.get("Content-Type")).toBe("text/event-stream");
		await expect(answer.text()).resolves.toBe("data: one\n\n: ping\n\n");
	});

	it("rejects with the worker's reason when the turn could not start", async () => {
		const response = workerFetch("mentor-turn", { body: BODY });
		port.send({ type: "failed", message: "The work in this tab changed." });
		await expect(response).rejects.toThrow("The work in this tab changed.");
	});

	it("passes a refusal through as the response the AI SDK raises", async () => {
		const response = workerFetch("mentor-turn", { body: BODY });
		port.send({ type: "response", status: 403, contentType: "text/plain" });
		port.send({ type: "chunk", text: "Not here." });
		port.send({ type: "end" });
		const answer = await response;
		expect(answer.ok).toBe(false);
		await expect(answer.text()).resolves.toBe("Not here.");
	});

	it("closes the port when the reader stops the reply, which stops the turn", async () => {
		const controller = new AbortController();
		const response = workerFetch("mentor-turn", { body: BODY, signal: controller.signal });
		port.send({ type: "response", status: 200, contentType: "text/event-stream" });
		const answer = await response;
		const reading = answer.text();
		controller.abort();
		expect(port.disconnected).toBe(true);
		await expect(reading).rejects.toThrow("The reply was stopped.");
	});

	it("fails the reply when the worker goes away mid-stream", async () => {
		const response = workerFetch("mentor-turn", { body: BODY });
		port.send({ type: "response", status: 200, contentType: "text/event-stream" });
		port.send({ type: "chunk", text: "data: partial\n\n" });
		const answer = await response;
		const reading = answer.text();
		port.close();
		await expect(reading).rejects.toThrow("The connection to Hephaestus was lost");
	});

	it("treats anything off the contract as a lost connection", async () => {
		const response = workerFetch("mentor-turn", { body: BODY });
		port.send({ type: "chunk", text: 4 });
		await expect(response).rejects.toThrow("The connection to Hephaestus was lost");
		expect(port.disconnected).toBe(true);
	});
});
