import { describe, expect, it } from "vitest";

import type { ReviewedWorkRef } from "@/api/types.gen";

import { countedStripWork, stripPieces } from "./practice-feedback-card-strip";

const pullRequest = (n: number): ReviewedWorkRef => ({
	id: String(n),
	kind: "scm.pull_request",
	label: `#${n}`,
});

const problem = (n: number, day: string) => ({
	ref: pullRequest(n),
	date: new Date(day),
	outcome: "COMMISSION_PROBLEM" as const,
});
const clean = (n: number, day: string) => ({ ref: pullRequest(n), date: new Date(day) });

const labels = (pieces: ReturnType<typeof stripPieces>) => pieces.map((piece) => piece.ref.label);

describe("stripPieces", () => {
	const sevenProblems = [11, 12, 13, 14, 15, 16, 17].map((n) => problem(n, `2026-08-${n}`));

	it("keeps the newest five, oldest first", () => {
		expect(labels(stripPieces(sevenProblems, []))).toStrictEqual([
			"#13",
			"#14",
			"#15",
			"#16",
			"#17",
		]);
	});

	it("lets the evidence give way to newer clean work", () => {
		const pieces = stripPieces(sevenProblems, [clean(21, "2026-09-07"), clean(22, "2026-09-09")]);
		expect(labels(pieces)).toStrictEqual(["#15", "#16", "#17", "#21", "#22"]);
		expect(pieces.at(-1)?.outcome).toBe("DEMONSTRATED_STRENGTH");
	});

	it("places clean work among the evidence by its date, not after it", () => {
		const pieces = stripPieces(
			[problem(17, "2026-08-28"), problem(19, "2026-09-06")],
			[clean(18, "2026-09-01"), clean(21, "2026-09-07")],
		);
		expect(labels(pieces)).toStrictEqual(["#17", "#18", "#19", "#21"]);
	});

	it("shows a piece reviewed twice twice, and counts it once", () => {
		const pieces = stripPieces(
			[problem(17, "2026-08-28"), problem(17, "2026-09-02"), problem(19, "2026-09-06")],
			[],
		);
		expect(labels(pieces)).toStrictEqual(["#17", "#17", "#19"]);
		expect(new Set(pieces.map((piece) => piece.key)).size).toBe(3);
		expect(countedStripWork(pieces.map((piece) => piece.ref)).text).toBe(
			"Newest two pull requests",
		);
	});
});
