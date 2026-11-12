import { describe, expect, it } from "vitest";
import type { EvidenceCitation, ResultEvidence } from "@/api/types.gen";
import {
	evidenceLineRangeLabel,
	splitPath,
	toEvidenceCheck,
	toEvidenceLocations,
} from "./evidence";

function citation(overrides: Partial<EvidenceCitation> = {}): EvidenceCitation {
	return {
		sourceKind: "scm.pull-request.diff",
		sourcePath: "owner/repo#1",
		path: "src/Main.java",
		side: "NEW",
		commit: null,
		startLine: 42,
		endLine: 44,
		quote: "a();\nb();",
		verification: null,
		...overrides,
	};
}

const NO_EVIDENCE: ResultEvidence = {
	citations: [],
	openQuestions: [],
	search: null,
	inapplicability: null,
};

describe("toEvidenceLocations", () => {
	it("carries a quote with the lines it came from", () => {
		expect(toEvidenceLocations([citation()])).toStrictEqual([
			{
				path: "src/Main.java",
				startLine: 42,
				endLine: 44,
				sourceKind: "scm.pull-request.diff",
				side: "NEW",
				commit: undefined,
				snippet: "a();\nb();",
			},
		]);
	});

	it("keeps the source kind, which decides whether the numbers are lines at all", () => {
		const [location] = toEvidenceLocations([
			citation({ sourceKind: "slack.conversation.thread", side: null }),
		]);

		expect(location?.sourceKind).toBe("slack.conversation.thread");
		expect(location?.side).toBeUndefined();
	});

	it("keeps the place of a citation that recorded no quote", () => {
		const [location] = toEvidenceLocations([citation({ quote: null })]);

		expect(location?.snippet).toBeUndefined();
		expect(location?.path).toBe("src/Main.java");
	});

	it("keeps a historical citation that recorded neither file nor lines", () => {
		const [location] = toEvidenceLocations([
			citation({ path: null, startLine: null, endLine: null }),
		]);

		expect(location?.path).toBeUndefined();
		expect(location?.startLine).toBeUndefined();
		expect(location?.snippet).toBe("a();\nb();");
	});

	it("preserves the commit that a historical quote was verified against", () => {
		const commit = "a".repeat(40);
		const [location] = toEvidenceLocations([citation({ commit })]);
		expect(location?.commit).toBe(commit);
	});

	it("preserves the order the reviewer recorded", () => {
		const locations = toEvidenceLocations([
			citation({ path: "b.java" }),
			citation({ path: "a.java" }),
		]);

		expect(locations.map((location) => location.path)).toStrictEqual(["b.java", "a.java"]);
	});

	it("folds the two sides of one changed line into one location", () => {
		const locations = toEvidenceLocations([
			citation({
				path: "src/pagination.ts",
				side: "OLD",
				startLine: 4,
				endLine: 4,
				quote: "-export const PAGE_SIZE = 25;",
			}),
			citation({
				path: "src/pagination.ts",
				side: "NEW",
				startLine: 4,
				endLine: 4,
				quote: "+export const PAGE_SIZE = 20;",
			}),
		]);

		expect(locations).toStrictEqual([
			{
				path: "src/pagination.ts",
				startLine: 4,
				endLine: 4,
				sourceKind: "scm.pull-request.diff",
				change: {
					before: "-export const PAGE_SIZE = 25;",
					after: "+export const PAGE_SIZE = 20;",
				},
			},
		]);
	});

	it("never folds two citations of different sources, whose line numbers say nothing about each other", () => {
		const locations = toEvidenceLocations([
			citation({ sourcePath: "owner/repo#1", side: "OLD", quote: "-a();" }),
			citation({ sourcePath: "owner/repo#2", side: "NEW", quote: "+a();" }),
		]);

		expect(locations).toHaveLength(2);
		expect(locations.every((location) => location.change === undefined)).toBe(true);
	});

	it("keeps neither side's commit on a folded block: each side was read at its own", () => {
		const [location] = toEvidenceLocations([
			citation({ side: "OLD", commit: "a".repeat(40), quote: "-a();" }),
			citation({ side: "NEW", commit: "b".repeat(40), quote: "+a();" }),
		]);

		expect(location?.change).toStrictEqual({ before: "-a();", after: "+a();" });
		expect(location?.commit).toBeUndefined();
		expect(location?.side).toBeUndefined();
	});

	it("folds a pair cited new side first, and keeps before before after", () => {
		const [location] = toEvidenceLocations([
			citation({ quote: "after();" }),
			citation({ side: "OLD", quote: "before();" }),
		]);

		expect(location?.change).toStrictEqual({ before: "before();", after: "after();" });
	});

	it("leaves two sides of different lines as two locations", () => {
		const locations = toEvidenceLocations([
			citation({ side: "OLD", startLine: 4, endLine: 4 }),
			citation({ side: "NEW", startLine: 9, endLine: 9 }),
		]);

		expect(locations).toHaveLength(2);
		expect(locations.every((location) => location.change === undefined)).toBe(true);
	});

	it("never folds a side that recorded no quote, so its place keeps its own block", () => {
		const locations = toEvidenceLocations([
			citation({ side: "OLD", quote: null }),
			citation({ side: "NEW", quote: "b();" }),
		]);

		expect(locations).toHaveLength(2);
		expect(locations[0]?.snippet).toBeUndefined();
	});

	it("folds every quote of one object source into the block that first named it", () => {
		const core = { sourceKind: "scm.pull-request.core", side: null } as const;
		const locations = toEvidenceLocations([
			citation({
				...core,
				path: "inputs/context/metadata.json",
				startLine: 4,
				endLine: 4,
				quote: '"title" : "Page size"',
			}),
			citation({
				...core,
				path: "inputs/context/metadata.json",
				startLine: 9,
				endLine: 9,
				quote: '"body" : ""',
			}),
		]);

		expect(locations).toHaveLength(1);
		expect(locations[0]?.snippet).toBe('"title" : "Page size"\n"body" : ""');
		expect(locations[0]?.startLine).toBe(4);
	});

	it("leaves two object sources apart, and never folds a code citation by its file", () => {
		const locations = toEvidenceLocations([
			citation({ sourceKind: "scm.pull-request.core", side: null, quote: "a" }),
			citation({ sourceKind: "slack.conversation.thread", side: null, quote: "b" }),
			citation({ path: "src/Main.java", startLine: 1, endLine: 1, quote: "c" }),
			citation({ path: "src/Main.java", startLine: 9, endLine: 9, quote: "d" }),
		]);

		expect(locations.map((location) => location.snippet)).toStrictEqual(["a", "b", "c", "d"]);
	});

	it("never folds a citation that recorded no quote into a source's block", () => {
		const core = { sourceKind: "scm.pull-request.core", side: null } as const;
		const locations = toEvidenceLocations([
			citation({ ...core, quote: null }),
			citation({ ...core, quote: '"body" : ""' }),
		]);

		expect(locations).toHaveLength(2);
		expect(locations[0]?.snippet).toBeUndefined();
	});

	it("treats no citations as no locations", () => {
		expect(toEvidenceLocations([])).toStrictEqual([]);
	});
});

describe("splitPath", () => {
	it("splits so the directory can absorb truncation", () => {
		expect(splitPath("src/main/java/Foo.java")).toStrictEqual({
			directory: "src/main/java/",
			fileName: "Foo.java",
		});
	});

	it("treats a bare file name as having no directory", () => {
		expect(splitPath("Foo.java")).toStrictEqual({ directory: "", fileName: "Foo.java" });
	});
});

describe("evidenceLineRangeLabel", () => {
	it("collapses a single-line range", () => {
		expect(
			evidenceLineRangeLabel({
				path: "a",
				startLine: 62,
				endLine: 62,
				sourceKind: "scm.pull-request.diff",
			}),
		).toBe("line 62");
	});

	it("renders a real range", () => {
		expect(
			evidenceLineRangeLabel({
				path: "a",
				startLine: 62,
				endLine: 70,
				sourceKind: "scm.pull-request.diff",
			}),
		).toBe("lines 62 to 70");
	});

	it("says nothing for a citation that recorded no lines", () => {
		expect(evidenceLineRangeLabel({ sourceKind: "scm.pull-request.diff" })).toBeUndefined();
	});
});

describe("toEvidenceCheck", () => {
	it("says where a search looked, what it read and how far the absence reaches", () => {
		expect(
			toEvidenceCheck({
				...NO_EVIDENCE,
				search: {
					lookedFor: "a test exercising the new branch",
					consulted: ["scm.pull-request.diff", "scm.repository.tree"],
					boundary: "every test file the diff touches and the repository's own test tree",
				},
			}),
		).toStrictEqual([
			{ term: "Looked for", detail: "a test exercising the new branch" },
			{ term: "Read", detail: "The code changes and Files and history in the repository" },
			{
				term: "How far it reached",
				detail: "every test file the diff touches and the repository's own test tree",
			},
		]);
	});

	it("says when the practice applies and the fact that left it nothing to judge", () => {
		expect(
			toEvidenceCheck({
				...NO_EVIDENCE,
				inapplicability: {
					prerequisite: "a change that calls out of the process",
					consulted: ["scm.pull-request.diff"],
					ruledOutBy: "nothing in the diff calls out of the process",
				},
			}),
		).toStrictEqual([
			{ term: "Applies when", detail: "a change that calls out of the process" },
			{ term: "Read", detail: "The code changes" },
			{
				term: "Nothing to judge because",
				detail: "nothing in the diff calls out of the process",
			},
		]);
	});

	it("shows the match account of an inapplicability warrant", () => {
		expect(
			toEvidenceCheck({
				...NO_EVIDENCE,
				inapplicability: {
					prerequisite: "an active AuthService",
					consulted: ["scm.repository.tree"],
					ruledOutBy: "No active AuthService exists in the checkout.",
					hits: "Both matches are commented-out registrations.",
				},
			}),
		).toContainEqual({
			term: "What it found",
			detail: "Both matches are commented-out registrations.",
		});
	});

	it("leaves open questions to their own section, so they never read as a check", () => {
		expect(
			toEvidenceCheck({
				...NO_EVIDENCE,
				openQuestions: [
					{
						openQuestion: "whether the rename was asked for",
						wouldSettleIt: "the review thread the description points at",
						citation: null,
					},
				],
			}),
		).toStrictEqual([]);
	});

	it("shows what a search found when the result says so", () => {
		const search = {
			lookedFor: "a changelog entry",
			boundary: "the files this change touches",
			consulted: [],
			hits: "Each match is an older release's entry.",
		};
		expect(toEvidenceCheck({ ...NO_EVIDENCE, search }).at(-1)).toStrictEqual({
			term: "What it found",
			detail: "Each match is an older release's entry.",
		});
	});

	it("names an unknown source kind generically and drops the pair when nothing was consulted", () => {
		const search = {
			lookedFor: "a changelog entry",
			boundary: "the files this change touches",
		};
		expect(
			toEvidenceCheck({
				...NO_EVIDENCE,
				search: { ...search, consulted: ["future.source"] },
			})[1],
		).toStrictEqual({ term: "Read", detail: "Another source" });
		expect(
			toEvidenceCheck({
				...NO_EVIDENCE,
				search: { ...search, consulted: [] },
			}).map(({ term }) => term),
		).toStrictEqual(["Looked for", "How far it reached"]);
	});

	it("has nothing to say about a result with no warrant at all", () => {
		expect(toEvidenceCheck(null)).toStrictEqual([]);
		expect(toEvidenceCheck(NO_EVIDENCE)).toStrictEqual([]);
	});
});
