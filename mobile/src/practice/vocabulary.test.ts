import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

import {
	DIFF_SIDE,
	FEEDBACK_RESOLUTION,
	FEEDBACK_USEFULNESS,
	NOT_CURRENT,
	NOT_LIVE_ORIGIN,
	OUTCOME,
	SEVERITY,
	STANDING,
	STANDING_ORDER,
	TREND,
	UNASSESSED,
} from "./vocabulary";

function web(file: string): string {
	return readFileSync(
		new URL(`../../../webapp/src/components/${file}`, import.meta.url),
		"utf8",
	).replaceAll(/\s+/gu, " ");
}

describe("practice vocabulary", () => {
	it.each([
		["practice-vocabulary/practice-group-standing-defs.ts", STANDING],
		["practice-vocabulary/practice-trend-defs.ts", TREND],
		["practice-vocabulary/feedback-resolution-defs.ts", FEEDBACK_RESOLUTION],
		["practice-vocabulary/feedback-usefulness-defs.ts", FEEDBACK_USEFULNESS],
		["practice-vocabulary/outcome-defs.ts", OUTCOME],
		["practice-vocabulary/assessment-status-defs.ts", UNASSESSED],
		["practice-vocabulary/severity-defs.ts", SEVERITY],
	] as const)("matches %s", (file, defs) => {
		const source = web(file);
		for (const [value, def] of Object.entries(defs)) {
			expect(source).toContain(`${value}: {`);
			expect(source).toContain(`label: "${def.label}"`);
			expect(source).toContain(def.description.replaceAll(/\s+/gu, " "));
		}
	});

	it("keeps the web's short standing labels and its order, most in need of attention first", () => {
		const source = web("practice-vocabulary/practice-group-standing-defs.ts");
		for (const def of Object.values(STANDING)) {
			expect(source).toContain(`shortLabel: "${def.shortLabel}"`);
		}
		const positions = STANDING_ORDER.map((value) => source.indexOf(`${value}: {`));
		expect(positions).toStrictEqual([...positions].sort((a, b) => a - b));
	});

	it("words review-rule currentness, origin and diff sides as the web does", () => {
		const badges = web("practice-vocabulary/ClaimCurrentness.tsx");
		for (const def of Object.values(NOT_CURRENT)) {
			expect(badges).toContain(`badge: "${def.label}"`);
			expect(badges).toContain(def.description);
		}
		for (const label of Object.values(NOT_LIVE_ORIGIN)) {
			expect(web("practice-vocabulary/observation-origin-defs.ts")).toContain(`"${label}"`);
		}
		const sources = web("practice-vocabulary/evidence-source-defs.ts");
		for (const [side, label] of Object.entries(DIFF_SIDE)) {
			expect(sources).toContain(`${side}: "${label}"`);
		}
	});
});
