import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";

import {
	DIAGNOSTIC_LINE,
	errorKind,
	formatDiagnostic,
	stackOf,
	token,
} from "../../../main/resources/agent/pi-diagnostics.ts";
import { UnratedError } from "../../../main/resources/agent/work/practice-scripts/lib/core/practice-script.ts";

const CANARY = "sk-diagnostics-canary";

void test("a diagnostic line holds codes, counts and labels, and a text becomes invalid", () => {
	const line = formatDiagnostic("tool.failed", {
		turn: token("turn 1/3 (security) / auth-review"),
		tool: "bash",
		reason_chars: 42,
		codemode: true,
		reason: `cat: .env: API_KEY=${CANARY}`,
		missing: undefined,
		empty: "",
	});

	assert.equal(
		line,
		"[pi-runner] tool.failed turn=turn_1/3_security_/_auth-review tool=bash reason_chars=42 codemode=true reason=invalid",
	);
	assert.match(line, DIAGNOSTIC_LINE);
	assert.equal(
		formatDiagnostic("provider.parse_failed", {}, "provider"),
		"[pi-provider] provider.parse_failed",
	);
});

void test("an error is named by its class and closed reason, and placed by its own frames, never by its message", () => {
	const failure = new TypeError(`could not read ${CANARY}`);
	failure.stack = `TypeError: could not read ${CANARY}\n    at read (file:///app/agent/pi-runner.ts:2586:6)\n    at main (file:///app/agent/pi-runner.ts:3247:3)`;

	assert.equal(errorKind(failure), "TypeError");
	assert.equal(errorKind(new UnratedError("unavailable", CANARY)), "UnratedError.unavailable");
	assert.equal(errorKind(CANARY), "string");
	assert.equal(stackOf(failure), "pi-runner.ts:2586,pi-runner.ts:3247");
	assert.equal(stackOf(CANARY), undefined);
	const line = formatDiagnostic("review.fatal", {
		error: errorKind(failure),
		at: stackOf(failure),
	});
	assert.ok(!line.includes(CANARY));
	assert.match(line, DIAGNOSTIC_LINE);
});

void test("an older runtime's free-text line does not match the grammar", () => {
	for (const line of [
		`[pi-runner] review practice-a tool error: bash — cat: .env: API_KEY=${CANARY}`,
		`[pi-runner] review provider call failed for good after 3 retries: 401 ${CANARY}`,
		`[pi-runner] result refused for practice-a: the quote "${CANARY}" is not in src/Auth.java`,
		`[pi-runner] practice.failed reason=${"x".repeat(129)}`,
		`[heph-host 2026-10-31T00:00:00Z] ${CANARY}`,
	]) {
		assert.doesNotMatch(line, DIAGNOSTIC_LINE, line);
	}
});

void test("the worker keeps the same grammar that the runtime writes", () => {
	const java = readFileSync(
		new URL(
			"../../../main/java/de/tum/cit/aet/hephaestus/agent/sandbox/kubernetes/SandboxExits.java",
			import.meta.url,
		),
		"utf8",
	);
	const compiled = /DIAGNOSTIC_LINE = Pattern\.compile\(\s*"(?<pattern>(?:[^"\\]|\\.)*)"\);/u.exec(
		java,
	)?.groups?.pattern;
	assert.ok(compiled !== undefined, "SandboxExits declares DIAGNOSTIC_LINE");
	// Java's Matcher.matches() anchors the whole line; the runtime's pattern anchors it with ^ and $.
	assert.equal(`^${compiled.replaceAll(String.raw`\\`, "\\")}$`, DIAGNOSTIC_LINE.source);
});
