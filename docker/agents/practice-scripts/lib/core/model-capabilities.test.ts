import assert from "node:assert/strict";
import test from "node:test";
import { registerForcedToolChoice, supportsForcedToolChoice } from "./model-capabilities.ts";

void test("a successful probe permits forcing through an opaque alias and an unknown probe clears it", () => {
	const model = { modelId: "review-model" };
	assert.equal(supportsForcedToolChoice(model), false);
	registerForcedToolChoice(model, true);
	assert.equal(supportsForcedToolChoice(model), true);
	registerForcedToolChoice(model, false);
	assert.equal(supportsForcedToolChoice(model), false);
	assert.equal(supportsForcedToolChoice("claude-opus-4-6"), false);
	assert.equal(supportsForcedToolChoice(undefined), false);
});
