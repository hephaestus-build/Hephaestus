import assert from "node:assert/strict";
import test from "node:test";

import type { ModelRuntime } from "@earendil-works/pi-coding-agent";

import {
	type RegisteredModel,
	registerHephaestusProvider,
} from "../../../main/resources/agent/pi-provider.ts";

function registered(env: Record<string, string | undefined>): RegisteredModel {
	let models: RegisteredModel[] = [];
	const runtime: Pick<ModelRuntime, "registerProvider"> = {
		registerProvider(_name, provider) {
			models = typeof _name === "string" && "models" in provider ? (provider.models ?? []) : [];
		},
	};
	const ok = registerHephaestusProvider(
		runtime,
		{ apiProtocol: "openai-completions", modelId: "m", supportsReasoning: false },
		{ LLM_PROXY_URL: "https://proxy.invalid", LLM_PROXY_TOKEN: "t", ...env },
	);
	assert.equal(ok, true);
	const model = models[0];
	if (!model) {
		throw new Error("one model registered");
	}
	return model;
}

void test("the operator's review temperature becomes the model's sampling parameter, else none is sent", () => {
	assert.deepEqual(registered({ LLM_SAMPLING_TEMPERATURE: "0.2" }).samplingParams, {
		temperature: 0.2,
	});
	assert.equal("samplingParams" in registered({}), false);
	// A value that is not a number leaves the model's own default in place rather than sending junk.
	assert.equal("samplingParams" in registered({ LLM_SAMPLING_TEMPERATURE: "warm" }), false);
});
