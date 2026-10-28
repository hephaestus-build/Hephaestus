import { describe, expect, it } from "vitest";

import { mockFileItemType, mockIssueItemType, mockPullRequestItemType, mockScan } from "./fixtures";
import {
	emptyScanDraft,
	pathPatternProblem,
	patternsOf,
	scanDraftOf,
	scanOf,
	scanProblem,
} from "./scan-settings";

const fileSources = mockFileItemType.readSources;

describe("pathPatternProblem", () => {
	it.each(["src/**/*.ts", "**/*", "packages/app/src/main.ts"])("accepts %s", (pattern) => {
		expect(pathPatternProblem(pattern)).toBeUndefined();
	});

	it.each([
		"/etc/passwd",
		"C:/repo",
		String.raw`src\main.ts`,
		"../outside",
		"src/../../outside",
		".git/config",
		"src/.git/hooks",
		"src//main.ts",
		"./src",
		"src/{a,b}.ts",
		"src/\0.ts",
		"a".repeat(513),
	])("refuses %s", (pattern) => {
		expect(pathPatternProblem(pattern)).toBeDefined();
	});
});

describe("patternsOf", () => {
	it("reads one pattern per line without blanks or repeats", () => {
		expect(patternsOf(" src/**/*.ts\n\nsrc/**/*.ts\n**/*.tsx \n")).toStrictEqual([
			"src/**/*.ts",
			"**/*.tsx",
		]);
	});
});

describe("scan drafts", () => {
	it("round-trips a saved scan and writes the current source contract", () => {
		expect(scanOf(scanDraftOf(mockScan), "3.0.0")).toStrictEqual(mockScan);
		expect(scanOf(scanDraftOf(mockScan), "2.1.0").reads.contract).toBe("2.1.0");
	});

	it("starts a new scan on the first kind the item type scans", () => {
		expect(emptyScanDraft(mockPullRequestItemType).itemKind).toBe("scm.file");
	});
});

describe("scanProblem", () => {
	const valid = scanDraftOf(mockScan);

	it("accepts a complete scan", () => {
		expect(scanProblem(valid, mockPullRequestItemType, fileSources)).toBeUndefined();
	});

	it("requires an include pattern", () => {
		expect(
			scanProblem({ ...valid, include: " \n" }, mockPullRequestItemType, fileSources),
		).toStrictEqual({
			field: "include",
			message: "Include at least one path pattern, such as **/*.ts.",
		});
	});

	it("names the field whose pattern is refused", () => {
		expect(
			scanProblem({ ...valid, context: "../secrets" }, mockPullRequestItemType, fileSources)?.field,
		).toBe("context");
	});

	it("refuses more than 64 patterns in one field", () => {
		const many = Array.from({ length: 65 }, (_, index) => `src/${index}.ts`).join("\n");
		expect(
			scanProblem({ ...valid, exclude: many }, mockPullRequestItemType, fileSources),
		).toStrictEqual({ field: "exclude", message: "Use at most 64 patterns in Exclude." });
	});

	it("refuses a scanned kind the item type does not offer", () => {
		expect(scanProblem(valid, mockIssueItemType, fileSources)?.field).toBe("itemKind");
	});

	it("requires a source the scan must read", () => {
		expect(
			scanProblem(
				{ ...valid, reads: [{ source: "scm.repository.tree", need: "OPTIONAL" }] },
				mockPullRequestItemType,
				fileSources,
			)?.field,
		).toBe("reads");
	});
});
