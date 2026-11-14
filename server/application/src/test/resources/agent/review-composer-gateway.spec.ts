import assert from "node:assert/strict";
import test from "node:test";

import {
	ANSWER_EVENT,
	compositionClient,
} from "../../../main/resources/agent/review-composer-gateway.ts";

const ANSWER =
	'{"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Done."}}]}';

function client(response: Response) {
	const sent: RequestInit[] = [];
	const gateway = compositionClient({
		compositionUrl: "http://server/internal/agent/runs/run/composition",
		proxyUrl: "http://server/internal/llm",
		headers: {},
		fetch: async (_input, init) => {
			sent.push(init ?? {});
			return response;
		},
	});
	return { gateway, sent };
}

function stream(text: string): Response {
	return new Response(text, { status: 200, headers: { "content-type": "text/event-stream" } });
}

void test("a streamed call answers with the gateway's recorded answer, not with the provider's events", async () => {
	const { gateway } = client(
		stream(
			'data: {"choices":[{"index":0,"delta":{"content":"Do"}}]}\n\n' +
				'data: {"choices":[{"index":0,"delta":{"content":"ne."},"finish_reason":"stop"}]}\n\n' +
				"data: [DONE]\n\n" +
				`event: ${ANSWER_EVENT}\ndata: ${ANSWER}\n\n`,
		),
	);

	const answer = await gateway.model("openai-completions", "{}");

	assert.deepEqual(answer, { status: 200, body: ANSWER, refusal: null });
});

void test("a stream that ends without the recorded answer is a failed call the loop may retry", async () => {
	const { gateway } = client(
		stream('data: {"choices":[{"index":0,"delta":{"content":"Half an"}}]}\n\n'),
	);

	const answer = await gateway.model("openai-completions", "{}");

	assert.deepEqual(answer, { status: 502, body: "", refusal: null });
});

void test("a provider's answer as one body and a refusal of the proxy reach the loop as they are", async () => {
	const body = client(
		new Response(ANSWER, { status: 200, headers: { "content-type": "application/json" } }),
	);
	assert.deepEqual(await body.gateway.model("openai-completions", "{}"), {
		status: 200,
		body: ANSWER,
		refusal: null,
	});

	const refused = client(
		new Response("not streamed", {
			status: 400,
			headers: { "Hephaestus-Proxy-Refusal": "NOT_STREAMED" },
		}),
	);
	assert.deepEqual(await refused.gateway.model("openai-completions", "{}"), {
		status: 400,
		body: "not streamed",
		refusal: "NOT_STREAMED",
	});
});
