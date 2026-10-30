/** Deterministic OpenAI chat replay for synthetic workspaces. Never forwards a request. */
import { createHash } from "node:crypto";
import { once } from "node:events";
import { readFile } from "node:fs/promises";
import { createServer, type ServerResponse } from "node:http";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import {
	parseReplay,
	claimReplayTool,
	selectReplayCall,
	sensitivityGrid,
	type ReplaySample,
} from "./lib/capacity-replay.ts";
import { asArray, asRecord, isRecord, parseJson } from "./lib/json.ts";

const { values } = parseArgs({
	options: {
		replay: { type: "string" },
		grid: { type: "boolean" },
		"tool-mode": { type: "string", default: "mixed-read" },
		port: { type: "string", default: "8080" },
	},
});
if (values.grid === true) {
	process.stdout.write(`${JSON.stringify(sensitivityGrid(), null, 2)}\n`);
} else {
	if (values.replay === undefined || values.replay === "") {
		throw new Error("--replay is required; generate assumed cases with --grid");
	}
	const replay = parseReplay(parseJson(await readFile(values.replay, "utf8")));
	const runLabel = process.env.CAPACITY_RUN_LABEL;
	if (runLabel !== undefined && !/^[a-z0-9-]+$/u.test(runLabel)) {
		throw new Error("Invalid run label");
	}
	if (!["none", "mixed-read"].includes(values["tool-mode"])) {
		throw new Error("--tool-mode must be none|mixed-read");
	}
	const port = Number(values.port);
	if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
		throw new Error("--port must be 1..65535");
	}
	let sequence = 0;
	const toolSent = new Set<string>();
	const callOrdinals = new Map<string, number>();
	const server = createServer((request, response) => {
		void handle();
		async function handle() {
			try {
				await serve();
			} catch {
				if (!response.headersSent) {
					response.writeHead(400);
				}
				response.end();
			}
		}
		async function serve() {
			if (request.method === "GET" && request.url === "/v1/models") {
				response.writeHead(200, { "content-type": "application/json" }).end(
					JSON.stringify({
						object: "list",
						data: [
							{ id: "capacity-replay", object: "model", created: 0, owned_by: "capacity-bench" },
						],
					}),
				);
				return;
			}
			if (request.url === "/health") {
				response.end("ok");
				return;
			}
			if (request.method !== "POST" || request.url !== "/v1/chat/completions") {
				response.writeHead(404).end();
				return;
			}
			const chunks: Buffer[] = [];
			let size = 0;
			for await (const part of request) {
				const chunk = Buffer.isBuffer(part) ? part : Buffer.from(String(part));
				size += chunk.length;
				if (size > 4 * 1024 * 1024) {
					response.writeHead(413).end();
					return;
				}
				chunks.push(chunk);
			}
			const raw = Buffer.concat(chunks).toString("utf8");
			const body = asRecord(parseJson(raw), "request");
			asArray(body.messages, "messages");
			const { sample, logicalKey, ordinal, marker, requestKey } = selectReplayCall(
				raw,
				replay.samples,
				runLabel,
				callOrdinals,
			);
			sequence += 1;
			const call = sequence;
			const log = (phase: string) =>
				process.stdout.write(
					`${JSON.stringify({
						call,
						syntheticMarker: marker,
						runLabel,
						replayKey: logicalKey,
						callOrdinal: ordinal,
						phase,
						at: new Date().toISOString(),
						provenance: replay.provenance,
						status: sample.status,
						inputTokens: sample.inputTokens,
						outputTokens: sample.outputTokens,
					})}\n`,
				);
			log("arrival");
			// Request/response bodies and headers never enter logs.
			if (sample.status === 429) {
				response.writeHead(429, { "retry-after": "1" }).end('{"error":{"type":"rate_limit"}}');
				log("end");
				return;
			}
			const { tool, text, model, usage } = completion(
				body,
				call,
				sample,
				toolSent,
				requestKey,
				values["tool-mode"] === "mixed-read",
			);
			await sleep(sample.ttftMs);
			if (response.destroyed) {
				log("cancelled");
				return;
			}
			if (body.stream === true) {
				response.writeHead(200, { "content-type": "text/event-stream" });
				log("first_token");
				for (let index = 0; index < 20; index += 1) {
					await write(response, {
						id: `capacity-${call}`,
						object: "chat.completion.chunk",
						model,
						choices: [
							{
								index: 0,
								delta: {
									content: text.slice(
										Math.floor((index * text.length) / 20),
										Math.floor(((index + 1) * text.length) / 20),
									),
								},
								finish_reason: null,
							},
						],
					});
					await sleep((sample.durationMs - sample.ttftMs) / 20);
					if (isDestroyed(response)) {
						log("cancelled");
						return;
					}
				}
				await write(response, {
					id: `capacity-${call}`,
					object: "chat.completion.chunk",
					model,
					choices: [
						{
							index: 0,
							delta: tool ? { tool_calls: [tool] } : {},
							finish_reason: tool ? "tool_calls" : "stop",
						},
					],
					usage,
				});
				response.end("data: [DONE]\n\n");
			} else {
				await sleep(sample.durationMs - sample.ttftMs);
				response.writeHead(200, { "content-type": "application/json" }).end(
					JSON.stringify({
						id: `capacity-${call}`,
						object: "chat.completion",
						model,
						choices: [
							{
								index: 0,
								message: {
									role: "assistant",
									content: text,
									...(tool ? { tool_calls: [tool] } : {}),
								},
								finish_reason: tool ? "tool_calls" : "stop",
							},
						],
						usage,
					}),
				);
			}
			log("end");
		}
	});
	server.listen(port, "0.0.0.0");
	for (const signal of ["SIGTERM", "SIGINT"] as const) {
		process.once(signal, () => {
			server.close();
		});
	}
}

function isDestroyed(response: ServerResponse): boolean {
	return response.destroyed;
}

function completion(
	body: Record<string, unknown>,
	call: number,
	sample: ReplaySample,
	toolSent: Set<string>,
	requestKey: string,
	toolsEnabled: boolean,
) {
	const tools = asArray(body.tools ?? [], "tools").map((tool) => asRecord(tool, "tool"));
	const useTools = (createHash("sha256").update(requestKey).digest()[0] ?? 0) % 2 === 0;
	const read = tools.find((tool) => isRecord(tool.function) && tool.function.name === "read");
	const tool =
		toolsEnabled && useTools && read && claimReplayTool(toolSent, requestKey)
			? {
					index: 0,
					id: `call_${call}`,
					type: "function",
					function: { name: "read", arguments: '{"path":"INDEX.md"}' },
				}
			: null;
	return {
		tool,
		text: " Synthetic benchmark response.".repeat(Math.ceil(sample.outputTokens / 4)),
		model: typeof body.model === "string" ? body.model : "capacity-replay",
		usage: {
			prompt_tokens: sample.inputTokens,
			completion_tokens: sample.outputTokens,
			total_tokens: sample.inputTokens + sample.outputTokens,
		},
	};
}

async function write(response: ServerResponse, value: unknown) {
	if (!response.write(`data: ${JSON.stringify(value)}\n\n`)) {
		const abort = new AbortController();
		const closed = () => {
			abort.abort();
		};
		response.once("close", closed);
		try {
			if (!response.destroyed) {
				await once(response, "drain", {
					signal: AbortSignal.any([abort.signal, AbortSignal.timeout(30_000)]),
				});
			}
		} catch (error) {
			if (!response.destroyed) {
				throw error;
			}
		} finally {
			response.off("close", closed);
		}
	}
}
