import assert from "node:assert/strict";
import test from "node:test";

import { findModelAttribution, MODEL_ATTRIBUTION_PATTERNS } from "./lib/model-attribution.ts";

void test("catches every attribution pattern", () => {
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

void test("allows a Co-authored-by trailer, whether it names a person or a model", () => {
	assert.deepEqual(findModelAttribution("Co-authored-by: Jane Doe <jane@example.com>"), []);
	assert.deepEqual(
		findModelAttribution("Fixes the flaky test.\n\nCo-authored-by: Jane Doe <jane@example.com>"),
		[],
	);
	assert.deepEqual(
		findModelAttribution("Fixes it.\n\nCo-authored-by: Claude Opus 5.5 <noreply@anthropic.com>"),
		[],
	);
	assert.deepEqual(findModelAttribution("Co-Authored-By: gpt-5 <bot@example.com>"), []);
});

void test("every pattern has a name unique enough to report on its own", () => {
	const names = MODEL_ATTRIBUTION_PATTERNS.map(({ name }) => name);
	assert.equal(new Set(names).size, names.length);
});

void test("does not treat artifact regeneration as attribution", () => {
	assert.deepEqual(findModelAttribution("Both API artifacts were regenerated with zero diff."), []);
	assert.deepEqual(findModelAttribution("The client was REGENERATED WITH the updated spec."), []);
});
