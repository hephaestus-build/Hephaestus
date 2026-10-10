/**
 * A stand-in for the server's precompute proxy routes, `POST /precompute/<slot>/<operation>`. Tests
 * give it a handler per call; live tests forward to real endpoints. It checks the precompute token
 * and records every request, so a test can assert what left the runner.
 */
import { once } from "node:events";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";

export interface StandInRequest {
	slot: string;
	operation: string;
	headers: IncomingMessage["headers"];
	body: Record<string, unknown>;
}

/**
 * Answers one request: a JSON body, `{ status: <number> }` for an error, or a promise that never
 * settles. A string `status`, as a Responses API body carries, is part of a JSON body.
 */
export type StandInHandler = (request: StandInRequest) => Promise<unknown>;

export interface StandIn {
	url: string;
	requests: StandInRequest[];
	close: () => Promise<void>;
}

const ROUTE =
	/^\/precompute\/(?<slot>chat|decision|embedding|reranking)\/(?<operation>chat\/completions|responses|decisions|embeddings|rerank)$/u;

/** A parsed JSON body the tests read by key: any object, an array included. */
function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null;
}

/** Write the handler's answer, or a 500 with the error when the handler rejects. */
async function respond(res: ServerResponse, answering: Promise<unknown>): Promise<void> {
	let answer: unknown;
	try {
		answer = await answering;
	} catch (error) {
		res
			.writeHead(500, { "content-type": "application/json" })
			.end(JSON.stringify({ error: String(error) }));
		return;
	}
	const status =
		typeof answer === "object" &&
		answer !== null &&
		"status" in answer &&
		typeof answer.status === "number"
			? answer.status
			: 200;
	res.writeHead(status, { "content-type": "application/json" }).end(JSON.stringify(answer));
}

export async function startStandIn(token: string, handler: StandInHandler): Promise<StandIn> {
	const requests: StandInRequest[] = [];
	const server = createServer((req: IncomingMessage, res: ServerResponse) => {
		const chunks: Buffer[] = [];
		req.on("data", (chunk: Buffer) => {
			chunks.push(chunk);
		});
		req.on("end", () => {
			const route = ROUTE.exec(req.url ?? "");
			if (req.method !== "POST" || route === null) {
				res.writeHead(404).end();
				return;
			}
			if (req.headers.authorization !== `Bearer ${token}`) {
				res.writeHead(401).end();
				return;
			}
			const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
			const request: StandInRequest = {
				slot: route.groups?.slot ?? "",
				operation: route.groups?.operation ?? "",
				headers: req.headers,
				body: isRecord(parsed) ? parsed : {},
			};
			requests.push(request);
			void respond(res, handler(request));
		});
	});
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	if (address === null || typeof address === "string") {
		throw new Error("the stand-in listens on a TCP port");
	}
	const { port } = address;
	return {
		url: `http://127.0.0.1:${port}`,
		requests,
		close: async () => {
			server.closeAllConnections();
			const closed = once(server, "close");
			server.close();
			await closed;
		},
	};
}

/** OpenAI chat-completions JSON with one assistant message. */
export function chatMessage(content: string, extra: Record<string, unknown> = {}) {
	return {
		id: "chatcmpl-stand-in",
		object: "chat.completion",
		created: 0,
		model: "stand-in",
		choices: [
			{ index: 0, finish_reason: "stop", message: { role: "assistant", content }, ...extra },
		],
		usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
	};
}

/** OpenAI chat-completions JSON whose assistant message calls one tool. */
export function chatToolCall(name: string, args: object, id = "call-1") {
	return {
		id: "chatcmpl-stand-in",
		object: "chat.completion",
		created: 0,
		model: "stand-in",
		choices: [
			{
				index: 0,
				finish_reason: "tool_calls",
				message: {
					role: "assistant",
					content: null,
					tool_calls: [
						{ id, type: "function", function: { name, arguments: JSON.stringify(args) } },
					],
				},
			},
		],
		usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
	};
}
