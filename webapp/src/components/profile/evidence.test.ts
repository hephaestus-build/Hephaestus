import { describe, expect, it } from "vitest";
import type { EvidenceCitation } from "@/api/types.gen";
import {
	evidenceLineRangeLabel,
	splitPath,
	toEvidenceCheck,
	toEvidenceLocations,
} from "./evidence";

function citation(overrides: Partial<EvidenceCitation> = {}): EvidenceCitation {
	return {
		sourceKind: "scm.pull-request.diff",
		artifactPath: "owner/repo#1",
		path: "src/Main.java",
		side: "NEW",
		startLine: 42,
		endLine: 44,
		quote: "a();\nb();",
		quoteRedacted: false,
		...overrides,
	};
}

describe("toEvidenceLocations", () => {
	it("carries a quote with the lines it came from", () => {
		expect(toEvidenceLocations({ citations: [citation()] })).toStrictEqual([
			{
				path: "src/Main.java",
				startLine: 42,
				endLine: 44,
				sourceKind: "scm.pull-request.diff",
				side: "NEW",
				snippet: "a();\nb();",
				redacted: false,
			},
		]);
	});

	it("keeps the source kind, which decides whether the numbers are lines at all", () => {
		const [location] = toEvidenceLocations({
			citations: [citation({ sourceKind: "slack.conversation.thread", side: undefined })],
		});

		expect(location?.sourceKind).toBe("slack.conversation.thread");
		expect(location?.side).toBeUndefined();
	});

	it("keeps a redacted citation, so a withheld quote stays distinguishable from an unquoted one", () => {
		const [location] = toEvidenceLocations({
			citations: [citation({ quote: undefined, quoteRedacted: true })],
		});

		expect(location?.snippet).toBeUndefined();
		expect(location?.redacted).toBe(true);

		expect(location?.path).toBe("src/Main.java");
	});

	it("preserves the order the reviewer recorded", () => {
		const locations = toEvidenceLocations({
			citations: [citation({ path: "b.java" }), citation({ path: "a.java" })],
		});

		expect(locations.map((location) => location.path)).toStrictEqual(["b.java", "a.java"]);
	});

	it("folds the two sides of one changed line into one location", () => {
		const locations = toEvidenceLocations({
			citations: [
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
			],
		});

		expect(locations).toStrictEqual([
			{
				path: "src/pagination.ts",
				startLine: 4,
				endLine: 4,
				sourceKind: "scm.pull-request.diff",
				redacted: false,
				change: {
					before: "-export const PAGE_SIZE = 25;",
					after: "+export const PAGE_SIZE = 20;",
				},
			},
		]);
	});

	it("folds a pair cited new side first, and keeps before before after", () => {
		const [location] = toEvidenceLocations({
			citations: [citation({ quote: "after();" }), citation({ side: "OLD", quote: "before();" })],
		});

		expect(location?.change).toStrictEqual({ before: "before();", after: "after();" });
	});

	it("leaves two sides of different lines as two locations", () => {
		const locations = toEvidenceLocations({
			citations: [
				citation({ side: "OLD", startLine: 4, endLine: 4 }),
				citation({ side: "NEW", startLine: 9, endLine: 9 }),
			],
		});

		expect(locations).toHaveLength(2);
		expect(locations.every((location) => location.change === undefined)).toBe(true);
	});

	it("never folds a withheld quote away, so the redaction keeps its own block", () => {
		const locations = toEvidenceLocations({
			citations: [
				citation({ side: "OLD", quote: undefined, quoteRedacted: true }),
				citation({ side: "NEW", quote: "b();" }),
			],
		});

		expect(locations).toHaveLength(2);
		expect(locations[0]?.redacted).toBe(true);
	});

	it("folds every quote of one object source into the block that first named it", () => {
		const core = { sourceKind: "scm.pull-request.core", side: undefined } as const;
		const locations = toEvidenceLocations({
			citations: [
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
			],
		});

		expect(locations).toHaveLength(1);
		expect(locations[0]?.snippet).toBe('"title" : "Page size"\n"body" : ""');
		expect(locations[0]?.startLine).toBe(4);
	});

	it("leaves two object sources apart, and never folds a code citation by its file", () => {
		const locations = toEvidenceLocations({
			citations: [
				citation({ sourceKind: "scm.pull-request.core", side: undefined, quote: "a" }),
				citation({ sourceKind: "slack.conversation.thread", side: undefined, quote: "b" }),
				citation({ path: "src/Main.java", startLine: 1, endLine: 1, quote: "c" }),
				citation({ path: "src/Main.java", startLine: 9, endLine: 9, quote: "d" }),
			],
		});

		expect(locations.map((location) => location.snippet)).toStrictEqual(["a", "b", "c", "d"]);
	});

	it("never folds a withheld quote into a source's block", () => {
		const core = { sourceKind: "scm.pull-request.core", side: undefined } as const;
		const locations = toEvidenceLocations({
			citations: [
				citation({ ...core, quote: undefined, quoteRedacted: true }),
				citation({ ...core, quote: '"body" : ""' }),
			],
		});

		expect(locations).toHaveLength(2);
		expect(locations[0]?.redacted).toBe(true);
	});

	it("treats absent evidence as no citations", () => {
		expect(toEvidenceLocations(undefined)).toStrictEqual([]);
		expect(toEvidenceLocations({ citations: [] })).toStrictEqual([]);
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
				redacted: false,
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
				redacted: false,
			}),
		).toBe("lines 62 to 70");
	});
});

describe("toEvidenceCheck", () => {
	it("says where a search looked, what it read and how far the absence reaches", () => {
		expect(
			toEvidenceCheck({
				citations: [],
				search: {
					lookedFor: "a test exercising the new branch",
					consulted: ["scm.pull-request.diff", "scm.repository.tree"],
					boundary: "every test file the diff touches and the repository's own test tree",
				},
			}),
		).toStrictEqual([
			{ term: "Looked for", detail: "a test exercising the new branch" },
			{ term: "Read", detail: "The code changes and Files in the repository" },
			{
				term: "How far it reached",
				detail: "every test file the diff touches and the repository's own test tree",
			},
		]);
	});

	it("says what the practice looks for and the fact that left it nothing to judge", () => {
		expect(
			toEvidenceCheck({
				citations: [],
				inapplicability: {
					subject: "how a change handles a timeout",
					consulted: ["scm.pull-request.diff"],
					ruledOutBy: "nothing in the diff calls out of the process",
				},
			}),
		).toStrictEqual([
			{ term: "Looks for", detail: "how a change handles a timeout" },
			{ term: "Read", detail: "The code changes" },
			{
				term: "Nothing to judge because",
				detail: "nothing in the diff calls out of the process",
			},
		]);
	});

	it("says the question that was left open and what would have answered it", () => {
		expect(
			toEvidenceCheck({
				citations: [],
				undecidability: {
					openQuestion: "whether the rename was asked for",
					wouldSettleIt: "the review thread the description points at",
				},
			}),
		).toStrictEqual([
			{ term: "Open question", detail: "whether the rename was asked for" },
			{ term: "Would settle it", detail: "the review thread the description points at" },
		]);
	});

	it("names an unknown source kind verbatim and drops the pair when nothing was consulted", () => {
		const search = {
			lookedFor: "a changelog entry",
			boundary: "the files this change touches",
		};
		expect(
			toEvidenceCheck({ citations: [], search: { ...search, consulted: ["future.source"] } })[1],
		).toStrictEqual({ term: "Read", detail: "future.source" });
		expect(
			toEvidenceCheck({ citations: [], search: { ...search, consulted: [] } }).map(
				({ term }) => term,
			),
		).toStrictEqual(["Looked for", "How far it reached"]);
	});

	it("has nothing to say about an observation with no warrant at all", () => {
		expect(toEvidenceCheck(undefined)).toStrictEqual([]);
		expect(toEvidenceCheck({ citations: [] })).toStrictEqual([]);
	});
});
