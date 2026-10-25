import { describe, expect, it } from "vitest";

import type { ItemRef } from "@/api/types.gen";

import { cleanProgress, TIMELINE_WORK_LIMIT, timelinePieces } from "./practice-feedback-timeline";

const pullRequest = (n: number): ItemRef => ({
	id: String(n),
	kind: "scm.pull_request",
	label: `#${n}`,
	url: null,
	container: "ls1intum/Hephaestus",
	parentId: null,
	path: null,
	provider: "GITHUB",
	removedAt: null,
	title: null,
});

const problem = (n: number, day: string) => ({
	ref: pullRequest(n),
	date: new Date(day),
	outcome: "NOT_MET" as const,
});
const clean = (n: number, day: string) => ({ ref: pullRequest(n), date: new Date(day) });

const labels = (pieces: ReturnType<typeof timelinePieces>) =>
	pieces.map((piece) => piece.ref.label);

describe("timelinePieces", () => {
	const sevenProblems = [11, 12, 13, 14, 15, 16, 17].map((n) => problem(n, `2026-08-${n}`));

	it("keeps the newest five, oldest first", () => {
		expect(labels(timelinePieces(sevenProblems, []))).toStrictEqual([
			"#13",
			"#14",
			"#15",
			"#16",
			"#17",
		]);
	});

	it("lets the evidence give way to newer clean work", () => {
		const pieces = timelinePieces(sevenProblems, [
			clean(21, "2026-09-07"),
			clean(22, "2026-09-09"),
		]);
		expect(labels(pieces)).toStrictEqual(["#15", "#16", "#17", "#21", "#22"]);
		expect(pieces.at(-1)?.outcome).toBe("MET");
	});

	it("orders interleaved evidence and clean work by their actual review times", () => {
		const pieces = timelinePieces(
			[problem(19, "2026-09-06"), problem(17, "2026-08-28")],
			[clean(21, "2026-09-07"), clean(18, "2026-09-01")],
		);
		expect(labels(pieces)).toStrictEqual(["#17", "#18", "#19", "#21"]);
	});

	it("shows a piece reviewed twice twice, each under its own key", () => {
		const pieces = timelinePieces(
			[problem(17, "2026-08-28"), problem(17, "2026-09-02"), problem(19, "2026-09-06")],
			[],
		);
		expect(labels(pieces)).toStrictEqual(["#17", "#17", "#19"]);
		expect(new Set(pieces.map((piece) => piece.key)).size).toBe(3);
	});

	it("caps the timeline at the five newest pieces of a long history", () => {
		expect(TIMELINE_WORK_LIMIT).toBe(5);
		const ninePieces = timelinePieces(
			[11, 12, 13, 14, 15, 16, 17].map((n) => problem(n, `2026-08-${n}`)),
			[clean(21, "2026-09-07"), clean(22, "2026-09-09")],
			TIMELINE_WORK_LIMIT,
		);
		expect(labels(ninePieces)).toStrictEqual(["#15", "#16", "#17", "#21", "#22"]);
	});
});

describe("cleanProgress", () => {
	it("says the run and what is still missing, never a fraction", () => {
		expect(cleanProgress({ cleanCount: 2, cleanNeeded: 3 })).toBe(
			"2 clean in a row · 1 more resolves it",
		);
		expect(cleanProgress({ cleanCount: 1, cleanNeeded: 3 })).toBe(
			"1 clean in a row · 2 more resolve it",
		);
	});

	it("says how long the run has to be while it is empty", () => {
		expect(cleanProgress({ cleanCount: 0, cleanNeeded: 3 })).toBe(
			"None clean yet · 3 in a row resolve it",
		);
		expect(cleanProgress({ cleanCount: 0, cleanNeeded: 1 })).toBe(
			"None clean yet · 1 in a row resolves it",
		);
	});

	it("says only the run once it is full", () => {
		expect(cleanProgress({ cleanCount: 3, cleanNeeded: 3 })).toBe("3 clean in a row");
	});
});
