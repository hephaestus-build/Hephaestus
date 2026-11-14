/**
 * A practice whose result may say that the repository lacks something hands its session the search
 * that backs such a claim. Admission reads only a plain pattern, and only one that names what the
 * claim says is absent, so the program prepares that pattern from the declarations the change adds.
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { CASES } from "../../../../../../docker/agents/practice-scripts/corpus-cases.ts";
import { runBundled } from "../../../../../../docker/agents/practice-scripts/run-bundled.ts";
import {
	ABSENCE_PLAIN_SEARCH,
	unsearchedClaim,
} from "../../../main/resources/agent/pi-session-reads.ts";

const PLAIN = new RegExp(`^(?:${ABSENCE_PLAIN_SEARCH})$`, "u");
const polyglot = CASES.find((entry) => entry.name === "every-language")?.run;
// `export function total()` is a declaration of the case; these are only called in it.
const DECLARED = "total";
const CALLED = new Set(["print", "log", "parseInt", "fetch", "get", "panic"]);

for (const slug of ["documents-public-api-and-behaviour-changes", "ships-tests-with-the-change"]) {
	void test(`${slug} prepares a search for the declarations that the change adds`, async () => {
		assert.ok(polyglot !== undefined, "the corpus has its every-language case");
		const { directions = [] } = await runBundled(slug, polyglot);
		const pattern = directions.find(
			(direction) => direction.startsWith(String.raw`\b`) && PLAIN.test(direction),
		);
		assert.ok(pattern !== undefined, `a plain pattern among: ${directions.join(" / ")}`);
		const names = pattern.split("|").map((term) => term.replaceAll(String.raw`\b`, ""));

		assert.ok(names.includes(DECLARED), `the pattern names a declaration: ${pattern}`);
		assert.deepEqual(
			names.filter((name) => CALLED.has(name)),
			[],
			"a call is no declaration",
		);
		assert.equal(
			unsearchedClaim(`references to ${DECLARED} outside the change`, [], [pattern], true),
			undefined,
			"a warrant that names the declaration is backed by the prepared search",
		);
	});
}
