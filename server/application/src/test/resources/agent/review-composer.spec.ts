import assert from "node:assert/strict";
import test from "node:test";

import { composeReview, type Session } from "../../../main/resources/agent/review-composer.ts";

const session: Session = {
	destination: "ON_WORK",
	model: {
		protocol: "openai-completions",
		modelId: "model",
		reasoningEffort: null,
		maxOutputTokens: 256,
		temperature: null,
	},
	system: "Compose.",
	input: "Review this work.",
	tools: [],
	finish: "Decide.",
	nudge: "Use tools.",
	accepted: false,
	fits: true,
	calls: 12,
	publicationContext: "v1:1:history",
};

/** A gateway whose routes answer by path, and that keeps the paths it was called on. */
function gateway(answer: (path: string) => Response | Promise<Response>) {
	const paths: string[] = [];
	const fetch: typeof globalThis.fetch = async (input) => {
		const path = new URL(input instanceof Request ? input.url : input).pathname;
		paths.push(path);
		return answer(path);
	};
	return { paths, fetch };
}

const json = (body: unknown) => Response.json(body);
const next = json.bind(null, {
	session,
	unavailable: null,
	deadline: new Date(Date.now() + 60_000).toISOString(),
	lastAttempt: false,
});

void test("a run that loses its lease stops at once and closes nothing", async () => {
	const { paths, fetch } = gateway((path) => {
		if (path.endsWith("/next")) {
			return next();
		}
		if (path.endsWith("/chat/completions")) {
			return json({
				choices: [{ message: { role: "assistant", content: null, tool_calls: [toolCall()] } }],
			});
		}
		return new Response(null, { status: 409 });
	});

	const outcome = await composeReview({
		compositionUrl: "http://gateway/composition",
		proxyUrl: "http://gateway/llm",
		headers: {},
		fetch,
	});

	assert.deepEqual(outcome, { leaseLost: true });
	assert.deepEqual(paths, [
		"/composition/next",
		"/llm/chat/completions",
		"/composition/tools/check_package",
	]);
});

void test("a run the driver aborts stops at its next await and closes nothing", async () => {
	const driver = new AbortController();
	const { paths, fetch } = gateway((path) => {
		if (path.endsWith("/next")) {
			return next();
		}
		// The driver stops the run while the model answers.
		driver.abort();
		throw new DOMException("aborted", "AbortError");
	});

	const outcome = await composeReview({
		compositionUrl: "http://gateway/composition",
		proxyUrl: "http://gateway/llm",
		headers: {},
		fetch,
		signal: driver.signal,
	});

	assert.deepEqual(outcome, { leaseLost: true });
	assert.deepEqual(paths, ["/composition/next", "/llm/chat/completions"]);
});

function toolCall() {
	return { id: "call-1", type: "function", function: { name: "check_package", arguments: "{}" } };
}
