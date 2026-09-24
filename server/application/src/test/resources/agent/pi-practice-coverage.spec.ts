import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import nodePath from "node:path";
import { test } from "node:test";

import { PracticeCoverageLedger } from "../../../main/resources/agent/pi-practice-coverage.ts";

void test("an abort after two practices leaves a complete atomic coverage snapshot", () => {
	const directory = mkdtempSync(nodePath.join(tmpdir(), "pi-practice-coverage-"));
	try {
		const eligible = ["a", "b", "c", "d"];
		const abort = new AbortController();
		const path = nodePath.join(directory, "practice-coverage.json");
		const ledger = new PracticeCoverageLedger(path, eligible);

		assert.deepEqual(JSON.parse(readFileSync(path, "utf8")), {
			eligible: 4,
			evaluated: 0,
			outcomes: eligible.map((practiceSlug) => ({ practiceSlug, outcome: "NOT_REACHED" })),
		});

		for (const [index, slug] of eligible.entries()) {
			if (abort.signal.aborted) {
				break;
			}
			ledger.markEvaluated([slug]);
			if (index === 1) {
				abort.abort();
			}
		}

		assert.deepEqual(JSON.parse(readFileSync(path, "utf8")), {
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
	const directory = mkdtempSync(nodePath.join(tmpdir(), "pi-practice-coverage-"));
	try {
		const ledger = new PracticeCoverageLedger(nodePath.join(directory, "practice-coverage.json"), [
			"eligible",
		]);
		assert.throws(
			() => ledger.markEvaluated(["eligible", "unknown"]),
			/evaluated practice is not eligible: unknown/u,
		);
		assert.partialDeepStrictEqual(
			JSON.parse(readFileSync(nodePath.join(directory, "practice-coverage.json"), "utf8")),
			{
				evaluated: 0,
			},
		);
	} finally {
		rmSync(directory, { recursive: true, force: true });
	}
});
