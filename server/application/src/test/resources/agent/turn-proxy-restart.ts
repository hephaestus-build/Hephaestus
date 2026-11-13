// The JVM fixture starts two real gateways on PostgreSQL, then terminates the first while this turn streams.
import assert from "node:assert/strict";
import { once } from "node:events";
import { createServer, request as forward, type Server } from "node:http";
import { createInterface } from "node:readline";

import { isRecord } from "../../../main/resources/agent/heph-host-protocol.ts";
import { httpTurnApi, runModels } from "../../../main/resources/agent/turn.ts";
import { assertRecoveredTurn, CUT_ANSWER, WHOLE_ANSWER } from "./turn-stream-cut.ts";
import { AGENT_DIR, contextOf, DOCUMENTS, fakeWorker, runTurn } from "./turn-worker.ts";

function chunk(content: string, finish: string | null = null): string {
	return `data: ${JSON.stringify({
		id: "restart",
		object: "chat.completion.chunk",
		created: 1,
		model: "restart-model",
		choices: [{ index: 0, delta: { role: "assistant", content }, finish_reason: finish }],
	})}\n\n`;
}
async function listen(server: Server): Promise<string> {
	server.listen(0, "127.0.0.1");
	await once(server, "listening");
	const address = server.address();
	assert.ok(address !== null && typeof address !== "string");
	return `http://127.0.0.1:${address.port}`;
}
const answering = Promise.withResolvers<undefined>();
let modelCalls = 0;
const model = createServer((request, response) => {
	request.resume();
	request.on("end", () => {
		modelCalls += 1;
		response.writeHead(200, { "content-type": "text/event-stream" });
		if (modelCalls === 1) {
			response.write(chunk(CUT_ANSWER));
			// No finish or usage frame. Closing the first server must interrupt this stream.
			return;
		}
		response.write(chunk("Here is the whole answer. "));
		void (async () => {
			await answering.promise;
			response.write(chunk(WHOLE_ANSWER.slice("Here is the whole answer. ".length), "stop"));
			response.write(
				`data: ${JSON.stringify({
					id: "restart",
					object: "chat.completion.chunk",
					created: 1,
					model: "restart-model",
					choices: [],
					usage: { prompt_tokens: 13, completion_tokens: 7, total_tokens: 20 },
				})}\n\n`,
			);
			response.end("data: [DONE]\n\n");
		})();
	});
});
const lines = createInterface({ input: process.stdin });
const input = lines[Symbol.asyncIterator]();
const modelUrl = await listen(model);
process.stdout.write(`${JSON.stringify({ modelUrl })}\n`);
let relay: Server | undefined;
try {
	const line = await input.next();
	assert.ok(line.done !== true);
	const config: unknown = JSON.parse(line.value);
	assert.ok(
		isRecord(config) &&
			typeof config.first === "string" &&
			typeof config.second === "string" &&
			typeof config.runId === "string" &&
			typeof config.epoch === "number" &&
			typeof config.token === "string",
	);
	const headers = {
		authorization: `Bearer ${config.token}`,
		"X-Agent-Run": config.runId,
		"X-Agent-Epoch": String(config.epoch),
	};
	let proxyCalls = 0;
	const destinations: string[] = [];
	const { first } = config;
	const { second } = config;
	// A stable Service address routes the retry to the surviving server. Both destinations are real proxies.
	relay = createServer((request, response) => {
		proxyCalls += 1;
		const destination = proxyCalls === 1 ? first : second;
		destinations.push(destination);
		const sent = forward(
			new URL(request.url ?? "/", destination),
			{ method: request.method, headers: { ...request.headers, host: new URL(destination).host } },
			(received) => {
				response.writeHead(received.statusCode ?? 502, received.headers);
				received.on("aborted", () => {
					response.destroy();
				});
				received.pipe(response);
			},
		);
		sent.on("error", (error) => {
			response.destroy(error);
		});
		response.on("close", () => {
			sent.destroy();
		});
		request.pipe(sent);
	});
	const relayUrl = await listen(relay);
	let streaming = false;
	const worker = fakeWorker(contextOf([], "question"), DOCUMENTS, (snapshot) => {
		const text = JSON.stringify(snapshot);
		if (!streaming && text.includes("first half")) {
			streaming = true;
			process.stdout.write(`${JSON.stringify({ streaming: true })}\n`);
		}
		if (text.includes("whole answer")) {
			answering.resolve(undefined);
		}
	});
	const observed = worker.api();
	const api = httpTurnApi({
		base: `${second}/internal/agent/runs/${config.runId}`,
		headers,
		label: "restart-proof",
	});
	let admissions = 0;
	const end = await runTurn(worker, runModels({ url: relayUrl, headers }, AGENT_DIR), 10, {
		...api,
		partial: async (update) => {
			await api.partial(update);
			await observed.partial(update);
		},
		end: async (ending) => {
			await api.end(ending);
			admissions += 1;
			await observed.end(ending);
		},
	});
	assertRecoveredTurn(worker, end);
	assert.deepEqual(destinations, [first, second]);
	assert.equal(modelCalls, 2);
	assert.equal(admissions, 1);
	// A repeated terminal delivery must not append another result.
	const duplicate = await fetch(`${second}/internal/agent/runs/${config.runId}/turn/end`, {
		method: "POST",
		headers: { ...headers, "content-type": "application/json" },
		body: JSON.stringify(end),
	});
	assert.equal(duplicate.status, 409);
	await duplicate.body?.cancel();
	process.stdout.write(`${JSON.stringify({ recovered: true, admissions })}\n`);
} finally {
	lines.close();
	for (const server of [relay, model]) {
		server?.closeAllConnections();
		server?.close();
	}
}
