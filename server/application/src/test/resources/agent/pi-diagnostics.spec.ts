import assert from "node:assert/strict";
import { readdirSync, readFileSync } from "node:fs";
import { test } from "node:test";

import vocabulary from "../../../main/resources/agent/pi-diagnostics.json" with { type: "json" };
import {
	errorKind,
	formatDiagnostic,
	isDiagnosticLine,
	SCALAR_TEXT,
} from "../../../main/resources/agent/pi-diagnostics.ts";
import { REASONING_EFFORTS } from "../../../main/resources/agent/pi-provider.ts";
import {
	REASONS,
	RUN_STATUSES,
	STOP_REASONS,
} from "../../../main/resources/agent/work/practice-scripts/lib/core/contract.ts";
import { UnratedError } from "../../../main/resources/agent/work/practice-scripts/lib/core/practice-script.ts";

const CANARY = "sk-diagnostics-canary";
const AGENT = new URL("../../../main/resources/agent/", import.meta.url);

void test("a diagnostic line holds counts, flags and codes, and a field it does not allow is left out and counted", () => {
	const line = formatDiagnostic("tool.failed", {
		phase: "review",
		turn: 1,
		practice: 0,
		tool: "bash",
		reason_chars: 42,
		codemode: true,
		reason: `cat: .env: API_KEY=${CANARY}`,
		raw_stop_reason: CANARY,
		turn_label: "turn 1/3 (security) / auth-review",
		missing: undefined,
	});

	assert.equal(
		line,
		"[pi-runner] tool.failed phase=review turn=1 practice=0 tool=bash reason_chars=42 codemode=true invalid=3",
	);
	assert.ok(isDiagnosticLine(line));
	assert.equal(
		formatDiagnostic("stop_reason.leaked", { stop_reason: CANARY }),
		"[pi-runner] diagnostic.invalid invalid=1",
	);
	assert.equal(
		formatDiagnostic("provider.parse_failed", {}, "provider"),
		"[pi-provider] provider.parse_failed",
	);
});

void test("the worker keeps only lines of the vocabulary, also when they have the shape of one", () => {
	for (const line of [
		`[pi-runner] review practice-a tool error: bash — cat: .env: API_KEY=${CANARY}`,
		`[pi-runner] review provider call failed for good after 3 retries: 401 ${CANARY}`,
		`[heph-host 2026-10-31T00:00:00Z] ${CANARY}`,
		`[pi-runner] assistant.message raw_stop_reason=${CANARY}`,
		`[pi-runner] session.leaked secret=${CANARY}`,
		`[pi-runner] assistant.message stop_reason=${CANARY}`,
		`[pi-runner] tool.failed tool=${CANARY}`,
		`[pi-runner] tool.failed turn=${CANARY}`,
		`[pi-runner] review.fatal at=${CANARY}`,
		"[pi-runner] review.fatal error=Error at=654321",
		"[pi-runner] turn.finished turn=1 turn=2",
		"[pi-runner] turn.finished  turn=1",
		"[pi-runner] turn.finished turn=1 ",
		"[pi-runner] turn.finished =1",
	]) {
		assert.ok(!isDiagnosticLine(line), line);
	}
	assert.ok(isDiagnosticLine("[pi-runner] review.fatal error=TypeError"));
	assert.ok(isDiagnosticLine("[pi-runner] coverage.measured evaluated=2 eligible=3 ratio=0.667"));
});

void test("an error is named by its class and closed reason from the vocabulary, never by its message", () => {
	const named = new Error(CANARY);
	named.name = "SkDiagnosticsCanary";
	const reasoned = Object.assign(new TypeError(CANARY), { reason: "sk_canary" });

	assert.equal(errorKind(new TypeError(`could not read ${CANARY}`)), "TypeError");
	assert.equal(errorKind(new UnratedError("unavailable", CANARY)), "UnratedError.unavailable");
	assert.equal(errorKind(named), "other");
	assert.equal(errorKind(reasoned), "TypeError");
	assert.equal(errorKind(CANARY), "not_an_error");
});

void test("a fatal error is named by its class alone: no field takes text or digits from its message or stack", () => {
	const failure = new Error("authentication failed /pi-runner.ts:654321:1 pin 482913");

	const line = formatDiagnostic("review.fatal", { error: errorKind(failure) });

	assert.equal(line, "[pi-runner] review.fatal error=Error");
	assert.ok(!isDiagnosticLine("[pi-runner] review.fatal error=Error at=654321"));
});

void test("the vocabulary's closed lists are the runtime's own", () => {
	const { fields } = vocabulary;
	assert.deepEqual(
		fields.error.filter((code) => code.startsWith("UnratedError.")).toSorted(),
		[...REASONS, "cancelled"].map((reason) => `UnratedError.${reason}`).toSorted(),
	);
	assert.deepEqual(fields.program_stop.toSorted(), [...STOP_REASONS].toSorted());
	assert.deepEqual(fields.status.toSorted(), [...RUN_STATUSES].toSorted());
	assert.deepEqual(
		fields.reasoning_effort.toSorted(),
		[...REASONING_EFFORTS.map((effort) => effort.toLowerCase()), "default"].toSorted(),
	);
});

void test("every event that the runtime writes is in the vocabulary", () => {
	const written = readdirSync(AGENT)
		.filter((file) => file.endsWith(".ts"))
		.flatMap((file) => [
			...readFileSync(new URL(file, AGENT), "utf8").matchAll(/\bdiagnose\(\s*"(?<event>[^"]+)"/gu),
		])
		.map((match) => match.groups?.event ?? "");

	assert.ok(written.length > 50);
	assert.deepEqual(
		written.filter((event) => !vocabulary.events.includes(event)),
		[],
	);
});

void test("the worker reads the same vocabulary with the same scalar patterns", () => {
	const java = readFileSync(
		new URL(
			"../../../main/java/de/tum/cit/aet/hephaestus/agent/sandbox/kubernetes/DiagnosticVocabulary.java",
			import.meta.url,
		),
		"utf8",
	);
	assert.match(java, /RESOURCE = "agent\/pi-diagnostics\.json";/u);
	const patterns = Object.fromEntries(
		[
			...java.matchAll(/"(?<type>[a-z]+)", Pattern\.compile\("(?<pattern>(?:[^"\\]|\\.)*)"\)/gu),
		].map(({ groups }): [string, string] => [
			groups?.type ?? "",
			groups?.pattern?.replaceAll(String.raw`\\`, "\\") ?? "",
		]),
	);
	// Java's Matcher.matches() anchors the whole text; the runtime's patterns anchor it with ^ and $.
	assert.deepEqual(
		patterns,
		Object.fromEntries(
			Object.entries(SCALAR_TEXT).map(([type, pattern]) => [type, pattern.source.slice(1, -1)]),
		),
	);
});
