import assert from "node:assert/strict";
import test from "node:test";

import { findModelAttribution, MODEL_ATTRIBUTION_PATTERNS } from "./lib/model-attribution.ts";

void test("catches every attribution pattern", () => {
	assert.deepEqual(
		findModelAttribution("Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"),
		["a Co-Authored-By trailer naming a model or tool"],
	);
	assert.deepEqual(findModelAttribution("Co-authored-by: gpt-5 <bot@example.com>"), [
		"a Co-Authored-By trailer naming a model or tool",
	]);
	assert.deepEqual(
		findModelAttribution("Claude-Session: https://claude.ai/code/session_01GZzUtPvAhEnBHsxqWTaRHn"),
		["a Claude-Session trailer", "a claude.ai/code or session link"],
	);
	assert.deepEqual(
		findModelAttribution("🤖 Generated with [Claude Code](https://claude.com/claude-code)"),
		['a "Generated with" marker'],
	);
	assert.deepEqual(
		findModelAttribution("See https://claude.ai/code/session_abc for the transcript."),
		["a claude.ai/code or session link"],
	);
});

void test("leaves a human Co-authored-by trailer alone", () => {
	assert.deepEqual(findModelAttribution("Co-authored-by: Jane Doe <jane@example.com>"), []);
	assert.deepEqual(
		findModelAttribution("Fixes the flaky test.\n\nCo-authored-by: Jane Doe <jane@example.com>"),
		[],
	);
});

void test("every pattern has a name unique enough to report on its own", () => {
	const names = MODEL_ATTRIBUTION_PATTERNS.map(({ name }) => name);
	assert.equal(new Set(names).size, names.length);
});
