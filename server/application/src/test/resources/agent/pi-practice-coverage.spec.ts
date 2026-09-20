import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { PracticeCoverageLedger } from "../../../main/resources/agent/pi-practice-coverage.ts";
import { mapConcurrent } from "../../../main/resources/agent/pi-review-tree.ts";

void test("a watchdog abort leaves a complete atomic coverage snapshot", async () => {
	const directory = mkdtempSync(path.join(tmpdir(), "pi-practice-coverage-"));
	try {
		const eligible = ["a", "b", "c", "d"];
		const abort = new AbortController();
		const ledgerPath = path.join(directory, "practice-coverage.json");
		const ledger = new PracticeCoverageLedger(ledgerPath, eligible);

		assert.deepEqual(JSON.parse(readFileSync(ledgerPath, "utf8")), {
			eligible: 4,
			evaluated: 0,
			outcomes: eligible.map((practiceSlug) => ({ practiceSlug, outcome: "NOT_REACHED" })),
		});

		await mapConcurrent(
			eligible,
			1,
			(slug, index) => {
				ledger.markEvaluated([slug]);
				if (index === 1) {
					abort.abort();
				}
			},
			abort.signal,
		);

		assert.deepEqual(JSON.parse(readFileSync(ledgerPath, "utf8")), {
			eligible: 4,
			evaluated: 2,
			outcomes: [
				{ practiceSlug: "a", outcome: "EVALUATED" },
				{ practiceSlug: "b", outcome: "EVALUATED" },
				{ practiceSlug: "c", outcome: "NOT_REACHED" },
				{ practiceSlug: "d", outcome: "NOT_REACHED" },
			],
		});
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
});

void test("the ledger rejects outcomes outside its eligible practice set", () => {
	const directory = mkdtempSync(path.join(tmpdir(), "pi-practice-coverage-"));
	try {
		const ledger = new PracticeCoverageLedger(path.join(directory, "practice-coverage.json"), [
			"eligible",
		]);
		assert.throws(
			() => ledger.markEvaluated(["eligible", "unknown"]),
			/evaluated practice is not eligible: unknown/u,
		);
		assert.partialDeepStrictEqual(
			JSON.parse(readFileSync(path.join(directory, "practice-coverage.json"), "utf8")),
			{
				evaluated: 0,
			},
		);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
});
