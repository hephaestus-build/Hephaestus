import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
	armsInOrder,
	casesInReplicate,
	COIN_SEED,
	firstArm,
	runSeed,
} from "./feedback-eval/ichnaea-compose.ts";

/** Whether a parsed JSON value is an object of named fields. */
const isRecord = (value: unknown): value is Record<string, unknown> =>
	typeof value === "object" && value !== null && !Array.isArray(value);

const COMPOSE = path.join(import.meta.dirname, "feedback-eval", "ichnaea-compose.ts");
const GATE = path.join(import.meta.dirname, "feedback-eval", "gate.json");

void test("a seeded coin per case and replicate picks the first arm, and either arm can go first", () => {
	const cases = casesInReplicate(0);
	assert.equal(cases.length, 72);
	const firsts = cases.map((id) => firstArm(id, 0));
	// The same inputs give the same order.
	assert.deepEqual(
		cases.map((id) => firstArm(id, 0)),
		firsts,
	);
	// A fair coin: neither arm goes first on every case. With 72 cases each side gets far more than 20.
	const baseFirst = firsts.filter((arm) => arm === "base").length;
	assert.ok(baseFirst > 20 && baseFirst < 52, `base first on ${String(baseFirst)} of 72 cases`);
	// The replicate is part of the coin: some case changes its order between replicates.
	assert.ok(cases.some((id) => firstArm(id, 0) !== firstArm(id, 1)));
	// Another seed gives another order.
	assert.ok(cases.some((id) => firstArm(id, 0, `${COIN_SEED}x`) !== firstArm(id, 0)));
	const arms = [{ name: "base" }, { name: "candidate" }];
	for (const id of cases.slice(0, 10)) {
		assert.equal(armsInOrder(arms, id, 0)[0]?.name, firstArm(id, 0));
		assert.equal(armsInOrder(arms, id, 0).length, 2);
	}
});

/**
 * An arm checkout whose Gradle wrapper stands in for the live test: it records the requested case, completed with one
 * request, in the result file that the compose script names. `gradlew.bat` runs the same script, so Windows takes the
 * path that it takes with a real checkout.
 */
function stubArm(parent: string, name: string): string {
	const root = path.join(parent, name);
	mkdirSync(path.join(root, "server"), { recursive: true });
	mkdirSync(path.join(root, "scripts", "feedback-eval"), { recursive: true });
	cpSync(GATE, path.join(root, "scripts", "feedback-eval", "gate.json"));
	const gradlew = path.join(root, "server", "gradlew");
	writeFileSync(
		gradlew,
		[
			"#!/usr/bin/env node",
			'const fs = require("node:fs");',
			"const file = process.env.COMPOSITION_EVAL_CHECKED;",
			"const id = process.env.COMPOSITION_EVAL_CASES;",
			'const run = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, "utf8")) : { version: 1, cases: [] };',
			"run.cases.push({ id, failure: null, requests: 1 });",
			"fs.writeFileSync(file, JSON.stringify(run));",
			"",
		].join("\n"),
	);
	chmodSync(gradlew, 0o755);
	writeFileSync(`${gradlew}.bat`, `@"${process.execPath}" "%~dp0gradlew" %*\r\n`);
	return root;
}

void test("each case runs its arms back to back in the coin's order, and the second starts after the first ended", () => {
	const dir = mkdtempSync(path.join(tmpdir(), "compose-coin-"));
	const out = path.join(dir, "out");
	const base = stubArm(dir, "base");
	const candidate = stubArm(dir, "candidate");
	// Replicate 2 holds only the withhold cases, which the suite gives three trials.
	const run = spawnSync(
		process.execPath,
		[COMPOSE, "--replicate", "2", "--base", base, "--candidate", candidate, "--out", out],
		{ encoding: "utf8" },
	);
	assert.equal(run.status, 0, run.stderr);
	const lines = readFileSync(path.join(out, "attempts-r2.jsonl"), "utf8")
		.split("\n")
		.filter((line) => line !== "")
		.map((line) => {
			const parsed: unknown = JSON.parse(line);
			assert.ok(isRecord(parsed));
			return parsed;
		});
	const cases = casesInReplicate(2);
	assert.equal(lines.length, 2 * cases.length);
	for (const [index, id] of cases.entries()) {
		const [first, second] = [lines[2 * index], lines[2 * index + 1]];
		assert.ok(first !== undefined && second !== undefined);
		// Back to back, in the coin's order, and the log says so.
		assert.equal(first.case, id);
		assert.equal(second.case, id);
		assert.equal(first.arm, firstArm(id, 2));
		assert.notEqual(second.arm, first.arm);
		assert.deepEqual([first.position, second.position], [1, 2]);
		assert.equal(first.first, firstArm(id, 2));
		assert.equal(second.first, firstArm(id, 2));
		// The second arm's trial starts only after the first arm's trial has ended (DN-47), on the harness's clock.
		assert.ok(
			String(second.startedAt) >= String(first.endedAt),
			`${id}: the second arm started early`,
		);
		assert.ok(String(first.endedAt) >= String(first.startedAt));
	}
	// Both orders occur in the run.
	assert.equal(new Set(lines.filter((l) => l.position === 1).map((l) => l.arm)).size, 2);
});

void test("a run's registered seed orders its arms, and every attempt line records it", () => {
	assert.equal(runSeed({}), COIN_SEED);
	assert.equal(runSeed({ COMPOSE_COIN_SEED: "" }), COIN_SEED);
	assert.equal(runSeed({ COMPOSE_COIN_SEED: "composition-set-coin-2" }), "composition-set-coin-2");
	const dir = mkdtempSync(path.join(tmpdir(), "compose-seed-"));
	const out = path.join(dir, "out");
	const run = spawnSync(
		process.execPath,
		[
			COMPOSE,
			"--replicate",
			"2",
			"--base",
			stubArm(dir, "base"),
			"--candidate",
			stubArm(dir, "candidate"),
			"--out",
			out,
		],
		{ encoding: "utf8", env: { ...process.env, COMPOSE_COIN_SEED: "composition-set-coin-2" } },
	);
	assert.equal(run.status, 0, run.stderr);
	const lines = readFileSync(path.join(out, "attempts-r2.jsonl"), "utf8")
		.split("\n")
		.filter((line) => line !== "")
		.map((line) => {
			const parsed: unknown = JSON.parse(line);
			assert.ok(isRecord(parsed));
			return parsed;
		});
	for (const line of lines) {
		assert.equal(line.seed, "composition-set-coin-2");
		if (line.position === 1) {
			assert.equal(line.arm, firstArm(String(line.case), 2, "composition-set-coin-2"));
		}
	}
	// The registered seed gives another order than the default on some case.
	assert.ok(
		casesInReplicate(2).some((id) => firstArm(id, 2, "composition-set-coin-2") !== firstArm(id, 2)),
	);
});
