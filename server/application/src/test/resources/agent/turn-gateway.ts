import assert from "node:assert/strict";
import { createInterface } from "node:readline";

import { isRecord } from "../../../main/resources/agent/conversation-host-protocol.ts";
import { httpTurnApi, runModels } from "../../../main/resources/agent/turn.ts";
import { AGENT_DIR, startTurn } from "./turn-worker.ts";

const lines = createInterface({ input: process.stdin });
try {
	const line = await lines[Symbol.asyncIterator]().next();
	assert.ok(line.done !== true);
	const config: unknown = JSON.parse(line.value);
	assert.ok(
		isRecord(config) &&
			typeof config.url === "string" &&
			typeof config.runId === "string" &&
			typeof config.epoch === "number" &&
			typeof config.token === "string",
	);
	const { url, runId, epoch, token } = config;
	const headers = {
		authorization: `Bearer ${token}`,
		"X-Agent-Run": runId,
		"X-Agent-Epoch": String(epoch),
	};
	const api = httpTurnApi({
		base: `${url}/internal/agent/runs/${runId}`,
		headers,
		label: "anchor-removal",
		patience: new AbortController().signal,
	});
	const attempt = startTurn(api, runModels({ url, headers }, AGENT_DIR));
	assert.equal(await attempt.done, true);
} finally {
	lines.close();
}
