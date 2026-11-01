import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
	type Citation,
	type Outcome,
	OUTCOMES,
	parseCandidate,
} from "../../../../../../docker/agents/practice-scripts/lib/core/contract.ts";
import { folderCitationIndex } from "../../../main/resources/agent/pi-folder-index.ts";
import {
	CONVERSATION_THREAD,
	citesReviewedTurn,
	describeCitationMismatch,
	normalizeCitation,
	normalizeSubmission,
	resolveQuote,
	resolveCapturedText,
	submissionCitations,
	validateChangeBasis,
	validateEvidenceSources,
	validateInapplicabilityScope,
	validateSearchScope,
	withoutCoordinates,
} from "../../../main/resources/agent/pi-result-normalize.ts";

import { z } from "../../../../../../docker/agents/practice-scripts/lib/core/practice-script.ts";

const coordinateCase = z.object({
	name: z.string(),
	content: z.string(),
	quote: z.string(),
	startLine: z.int(),
	endLine: z.int(),
	expectedStartLine: z.int().nullable(),
});

const DIFF_CITATION = {
	sourceKind: "scm.pull-request.diff",
	sourcePath: "inputs/context/diff.patch",
	path: "src/Auth.java",
	side: "NEW",
	startLine: 10,
	endLine: 10,
	quote: "+ insecure();",
};

function citationFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
	return { ...DIFF_CITATION, ...overrides };
}

function problemFixture(overrides: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		severity: "MAJOR",
		summary: "Insecure call added to the auth path",
		explanation: "The change calls insecure() where a request is authorized.",
		citations: [citationFixture()],
		basis: { type: "introduced", head: 0, explanation: "The change adds the call." },
		...overrides,
	};
}

function baseSubmission(overrides: Record<string, unknown> = {}): Record<string, unknown> {
	return {
		outcome: "NOT_MET",
		summary: "PR mixes unrelated changes",
		explanation: "The diff touches auth and billing in one PR.",
		citations: [citationFixture()],
		problems: [problemFixture()],
		evidence: {},
		...overrides,
	};
}

function citing(citation: Record<string, unknown>): Record<string, unknown> {
	return baseSubmission({ citations: [citation] });
}

function onlyCitation<T extends object>(citations: readonly T[]): T {
	const [citation] = citations;
	if (!citation) {
		throw new Error("expected exactly one citation");
	}
	return citation;
}

function normalizedCitation(citation = citationFixture()): Citation {
	return onlyCitation(normalizeSubmission(citing(citation)).citations);
}

function refusal(raw: unknown, notes: string[] = []): string {
	try {
		normalizeSubmission(raw, notes);
	} catch (error) {
		return error instanceof Error ? error.message : String(error);
	}
	return assert.fail("expected the submission to be refused");
}

const goodSearch = {
	consulted: ["scm.review-threads"],
	lookedFor: "a review thread raising the migration",
	boundary: "only threads on this pull request; nothing in chat",
};

const goodInapplicability = {
	consulted: ["scm.pull-request.diff"],
	prerequisite: "error handling around outbound network calls",
	ruledOutBy: "the change touches only Markdown documentation and makes no network calls",
};

const UNDECIDABLE = {
	openQuestion: "Whether the body states a why, or only restates the title",
	wouldSettleIt: "Clarification of the contradictory acceptance requirements",
};

const EXAMPLE = {
	citation: {
		sourceKind: "scm.pull-request.core",
		sourcePath: "inputs/context/metadata.json",
		path: "inputs/context/metadata.json",
		startLine: 2,
		endLine: 2,
		quote: '"title" : "Split auth from billing"',
	},
	note: "The title names the one change that the pull request makes.",
};

function resultOf(outcome: Outcome): Record<string, unknown> {
	const question = {
		...UNDECIDABLE,
		citation: citationFixture({ path: "src/Billing.java", quote: "+ charge();" }),
	};
	switch (outcome) {
		case "MET": {
			return baseSubmission({
				outcome,
				problems: undefined,
				example: EXAMPLE,
				evidence: { search: goodSearch },
			});
		}
		case "NOT_MET": {
			return baseSubmission({
				citations: [],
				example: EXAMPLE,
				evidence: { search: goodSearch, openQuestions: [question] },
			});
		}
		case "DOES_NOT_APPLY": {
			return baseSubmission({
				outcome,
				problems: undefined,
				evidence: { inapplicability: goodInapplicability },
			});
		}
		case "COULD_NOT_DETERMINE": {
			return baseSubmission({
				outcome,
				problems: undefined,
				evidence: { search: goodSearch, openQuestions: [question] },
			});
		}
	}
}

void test("session outcomes retain their distinct evidence and problem bounds", () => {
	for (const outcome of OUTCOMES) {
		const submission = normalizeSubmission(resultOf(outcome));
		assert.equal(submission.outcome, outcome);
		assert.equal("problems" in submission, outcome === "NOT_MET");
		assert.equal("severity" in submission, false);
		const parsed = parseCandidate({
			...submission,
			coverage: {
				source: "host",
				reads: 1,
				truncatedReads: 0,
				failedReads: 0,
				receipts: [],
				complete: true,
			},
		});
		assert.ok(parsed.ok, parsed.ok ? undefined : parsed.message);
	}
});

void test("normalization preserves unknown fields for contract refusal and reports the first bad text field", () => {
	for (const key of ["severity", "confidence", "guidance", "assessment", "coverage"]) {
		assert.throws(
			() => normalizeSubmission(baseSubmission({ [key]: "untrusted" })),
			new RegExp(key, "u"),
		);
	}
	const error = refusal(
		baseSubmission({
			summary: "x".repeat(161),
			explanation: "",
			problems: [problemFixture({ severity: "HIGH" })],
		}),
	);
	assert.match(error, /^summary:.*160/u);
	assert.doesNotMatch(error, /; also:/u);
});

void test("an uncertain session cannot turn a missing warrant into a verdict", () => {
	assert.throws(
		() => normalizeSubmission({ ...resultOf("DOES_NOT_APPLY"), evidence: {} }),
		/inapplicability/u,
	);
	assert.throws(
		() => normalizeSubmission({ ...resultOf("COULD_NOT_DETERMINE"), evidence: {} }),
		/open questions/u,
	);
	assert.throws(
		() => normalizeSubmission({ ...resultOf("MET"), evidence: { openQuestions: [UNDECIDABLE] } }),
		/MET/u,
	);
	assert.throws(
		() => normalizeSubmission({ ...resultOf("MET"), problems: [problemFixture()] }),
		/problem/u,
	);
});

void test("normalization keeps the exact claim and only collapses summary whitespace", () => {
	const normalized = normalizeSubmission(
		baseSubmission({ summary: "PR mixes\n  unrelated   changes" }),
	);
	assert.equal(normalized.summary, "PR mixes unrelated changes");
	assert.deepEqual(normalized.problems?.[0]?.basis, problemFixture().basis);
	assert.deepEqual(normalized.problems?.[0]?.citations, [DIFF_CITATION]);
});

const citationAt = (path: string) => citationFixture({ path });

void test("submissionCitations lists the result's, each problem's, the example's, then each open question's", () => {
	const submission = normalizeSubmission(
		baseSubmission({
			citations: [citationAt("a.ts")],
			problems: [
				problemFixture({ citations: [citationAt("b.ts"), citationAt("c.ts")] }),
				problemFixture({ citations: [citationAt("d.ts")] }),
			],
			example: { ...EXAMPLE, citation: citationAt("e.ts") },
			evidence: {
				openQuestions: [UNDECIDABLE, { ...UNDECIDABLE, citation: citationAt("f.ts") }],
			},
		}),
	);
	const listed = submissionCitations(submission);
	assert.deepEqual(
		listed.map((citation) => citation.path),
		["a.ts", "b.ts", "c.ts", "d.ts", "e.ts", "f.ts"],
	);
	for (const citation of listed) {
		citation.quote = "repaired";
	}
	assert.equal(submission.example?.citation.quote, "repaired");
	assert.equal(submission.evidence.openQuestions?.[1]?.citation?.quote, "repaired");
});

const citingLines = (lines: Record<string, unknown>) =>
	citing(citationFixture({ startLine: undefined, endLine: undefined, ...lines }));

void test("a line-number refusal names what was received, and an omitted line as omitted", () => {
	assert.match(
		refusal(citingLines({})),
		/^citations\.0: evidence citation startLine is required: the 1-based line/u,
	);
	assert.match(refusal(citingLines({ startLine: null })), /startLine is required/u);
	assert.match(
		refusal(citingLines({ startLine: 0 })),
		/startLine must be a positive integer, received 0; lines are 1-based/u,
	);
	assert.match(refusal(citingLines({ startLine: "ten" })), /received "ten"; lines are 1-based/u);
	assert.match(
		refusal(citingLines({ startLine: 10, endLine: 4 })),
		/endLine must be an integer >= startLine, received 4 with startLine 10/u,
	);
});

void test("citation requires an exact sourceFile path and quote", () => {
	assert.match(
		refusal(citing(citationFixture({ sourcePath: undefined }))),
		/sourcePath is required/u,
	);
	for (const quote of [undefined, null, "", "  "]) {
		assert.match(
			refusal(citing(citationFixture({ quote }))),
			/^citations\.0: evidence citation quote is required/u,
		);
	}
	assert.match(
		refusal(citing(citationFixture({ quote: "x".repeat(4001) }))),
		/^citations\.0: quote must be at most 4000 characters/u,
	);
});

void test("normalization preserves side for source-catalog validation", () => {
	assert.equal(
		normalizedCitation(citationFixture({ sourceKind: "custom.source", side: "NEW" })).side,
		"NEW",
	);
	assert.equal(normalizedCitation(citationFixture({ side: undefined })).side, undefined);
});

void test("a citation of change.json is refused with what to quote instead", () => {
	assert.match(
		refusal(citing(citationFixture({ path: "inputs/context/change.json" }))),
		/^citations\.0: change\.json pins the reviewed range and is not quotable/u,
	);
	assert.match(
		refusal(
			citing(
				citationFixture({
					sourceKind: "scm.repository.tree",
					sourcePath: "inputs/context/tree.json",
					side: undefined,
				}),
			),
		),
		/^citations\.0: repository citations must use the captured \.git\/HEAD sourceFile/u,
	);
});

const CHECKOUT_WITNESSES = [
	["repos/reviewed/.git/HEAD", "scm.repository.tree"],
	["repos/reviewed/.git/hephaestus-captured-refs", "scm.repository.tree"],
] as const;

function withCheckout(
	sources: readonly string[],
	sourceFiles: readonly (readonly [string, string])[],
): [Set<string>, Map<string, string>] {
	return [
		new Set([...sources, "scm.repository.tree"]),
		new Map<string, string>([...sourceFiles, ...CHECKOUT_WITNESSES]),
	];
}

void test("a quote of code is refused unless the checkout admission verifies it against was captured", () => {
	const submission = normalizeSubmission(baseSubmission());
	const diff = [["inputs/context/diff.patch", "scm.pull-request.diff"]] as const;
	assert.doesNotThrow(() =>
		validateEvidenceSources(submission, ...withCheckout(["scm.pull-request.diff"], diff)),
	);
	assert.throws(
		() => validateEvidenceSources(submission, new Set(["scm.pull-request.diff"]), new Map(diff)),
		/a quote of code needs the captured checkout repos\/reviewed\//u,
	);
	const [available, sourceFiles] = withCheckout(["scm.pull-request.diff"], diff);
	sourceFiles.delete("repos/reviewed/.git/hephaestus-captured-refs");
	assert.throws(
		() => validateEvidenceSources(submission, available, sourceFiles),
		/needs the captured checkout/u,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				submission,
				new Set(["scm.pull-request.diff"]),
				new Map([...diff, ...CHECKOUT_WITNESSES]),
			),
		/needs the captured checkout/u,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				submission,
				...withCheckout(["scm.pull-request.diff"], diff),
				"repos/primary/",
			),
		/needs the captured checkout repos\/primary\//u,
	);
	assert.match(
		refusal(
			citing(
				citationFixture({
					sourceKind: "scm.repository.tree",
					sourcePath: "repos/re.viewed/.git/HEAD",
					side: undefined,
				}),
			),
		),
		/repository citations must use the captured \.git\/HEAD sourceFile/u,
	);
});

void test("a quote of the change requires its captured checkout witness", () => {
	const { availableSourceKinds, sourceFileSources } = folderCitationIndex(
		JSON.parse(
			readFileSync(new URL("../practice-results/live-manifest.json", import.meta.url), "utf8"),
		),
	);
	const cited = citationFixture({ sourcePath: "context/change.json", path: "src/cart.ts" });
	const submission = normalizeSubmission(
		baseSubmission({ citations: [cited], problems: [problemFixture({ citations: [cited] })] }),
	);
	assert.doesNotThrow(() =>
		validateEvidenceSources(submission, availableSourceKinds, sourceFileSources),
	);
	const withoutCheckout = new Map(
		[...sourceFileSources].filter(([, kind]) => kind !== "scm.repository.tree"),
	);
	assert.throws(
		() => validateEvidenceSources(submission, availableSourceKinds, withoutCheckout),
		/needs the captured checkout repos\/reviewed\//u,
	);
});

void test("a citation must name a source this run staged, and the sourceFile that source produced", () => {
	const submission = normalizeSubmission(baseSubmission());
	assert.doesNotThrow(() =>
		validateEvidenceSources(
			submission,
			...withCheckout(
				["scm.pull-request.diff"],
				[["inputs/context/diff.patch", "scm.pull-request.diff"]],
			),
		),
	);
	assert.doesNotThrow(() =>
		validateEvidenceSources(
			submission,
			...withCheckout(
				["scm.pull-request.diff", "workspace.project-inventory"],
				[["inputs/context/diff.patch", "scm.pull-request.diff"]],
			),
		),
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				submission,
				new Set(["scm.pull-request.core", "scm.review-threads"]),
				new Map(),
			),
		/was not available.*scm\.pull-request\.core, scm\.review-threads/u,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				submission,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/diff.patch", "scm.pull-request.core"]]),
			),
		/belongs to evidence source 'scm\.pull-request\.core', not 'scm\.pull-request\.diff'/u,
	);
	assert.throws(
		() =>
			validateEvidenceSources(
				submission,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/change.json", "scm.pull-request.diff"]]),
			),
		/was not staged by 'scm\.pull-request\.diff';/u,
	);
	const derived = normalizeSubmission(baseSubmission());
	onlyCitation(derived.citations).sourcePath = "work/change/files.json";
	assert.throws(
		() =>
			validateEvidenceSources(
				derived,
				new Set(["scm.pull-request.diff"]),
				new Map([["inputs/context/diff.patch", "scm.pull-request.diff"]]),
			),
		/work\/ is derived here and is not a source file: quote a changed line from work\/change\/diff\.patch/u,
	);
});

void test("the sources of every citation are checked, not only the result's", () => {
	const [available, staged] = withCheckout(
		["scm.pull-request.diff"],
		[["inputs/context/diff.patch", "scm.pull-request.diff"]],
	);
	assert.throws(
		() => validateEvidenceSources(normalizeSubmission(resultOf("MET")), available, staged),
		/evidence source 'scm\.pull-request\.core' was not available/u,
	);
	const inQuestion = normalizeSubmission({
		...resultOf("COULD_NOT_DETERMINE"),
		evidence: {
			openQuestions: [
				{ ...UNDECIDABLE, citation: citationFixture({ sourcePath: "elsewhere.patch" }) },
			],
		},
	});
	assert.throws(
		() => validateEvidenceSources(inQuestion, available, staged),
		/sourceFile 'elsewhere\.patch' was not staged/u,
	);
});

void test("an unstaged citation refuses its reference without disclosing every staged repository path", () => {
	const submission = normalizeSubmission(baseSubmission());
	const sources = new Map<string, string>(
		Array.from({ length: 10_000 }, (_, at) => [
			`repository/unrelated-${at}.swift`,
			"scm.repository.tree",
		]),
	);
	let refused: unknown;
	try {
		validateEvidenceSources(submission, new Set(["scm.pull-request.diff"]), sources);
	} catch (error) {
		refused = error;
	}
	assert.ok(refused instanceof Error);
	assert.ok(refused.message.includes(onlyCitation(submission.citations).sourcePath));
	assert.ok(refused.message.includes("scm.pull-request.diff"));
	assert.equal(
		[...sources.keys()].some((path) => refused.message.includes(path)),
		false,
	);
	assert.throws(
		() => validateEvidenceSources(submission, new Set(["scm.pull-request.diff"]), new Map()),
		{ message: refused.message },
	);
});

/** One captured text of the source identity capture; every text shares one placeholder digest. */
function sourceIdentityText(
	source: string,
	path: string,
	side: "OLD" | "NEW",
	commit: string | null,
	stagedPath: string,
) {
	return { source, path, side, commit, stagedPath, sha256: "c".repeat(64) };
}

/** A pull request capture as the server stages it: one change record and many diff texts of the same kind. */
function sourceIdentityCapture(order: "test-first" | "record-last") {
	const DIFF = "scm.pull-request.diff";
	const CORE = "scm.pull-request.core";
	const head = "a".repeat(40);
	const base = "b".repeat(40);
	const code = "src/main/java/com/example/userapi/config/RetryPolicy.java";
	const smoke = "src/test/java/com/example/userapi/config/RetryPolicySmokeTest.java";
	const diffFiles: [string, string][] = [
		[`context/citable/new/${smoke}`, DIFF],
		["context/citable/new/README.md", DIFF],
		["context/citable/old/README.md", DIFF],
		[`context/citable/new/${code}`, DIFF],
		["context/citable-records/scm.pull-request.diff/1111.txt", DIFF],
		["context/change.json", DIFF],
	];
	const sourceFiles = new Map<string, string>([
		...(order === "test-first" ? diffFiles : diffFiles.toReversed()),
		["context/metadata.json", CORE],
		["context/description.md", CORE],
		["context/citable-records/scm.pull-request.core/2222.txt", CORE],
		["repos/reviewed/.git/HEAD", "scm.repository.tree"],
	]);
	const pinned = (path: string, side: "OLD" | "NEW") => {
		const stagedPath = `context/citable/${side.toLowerCase()}/${path}`;
		const commit = side === "NEW" ? head : base;
		return [
			sourceIdentityText(DIFF, path, side, commit, stagedPath),
			sourceIdentityText(DIFF, path, side, null, stagedPath),
		];
	};
	return {
		sourceFiles,
		capturedTexts: [
			...pinned(smoke, "NEW"),
			...pinned("README.md", "NEW"),
			...pinned("README.md", "OLD"),
			...pinned(code, "NEW"),
			sourceIdentityText(DIFF, "context/change.json", "NEW", null, "context/change.json"),
			sourceIdentityText(
				DIFF,
				"context/change.json#/diff",
				"NEW",
				null,
				"context/citable-records/scm.pull-request.diff/1111.txt",
			),
			sourceIdentityText(CORE, "context/metadata.json", "NEW", null, "context/metadata.json"),
			sourceIdentityText(CORE, "context/description.md", "NEW", null, "context/description.md"),
			sourceIdentityText(
				CORE,
				"context/metadata.json#/body",
				"NEW",
				null,
				"context/citable-records/scm.pull-request.core/2222.txt",
			),
		],
		sandboxRoot: "/sandbox",
		repositoryRoot: "repos/reviewed",
		headRevision: head,
		code,
		smoke,
	};
}

void test("a diff citation names the change record, never another file of its kind, in any capture order", () => {
	for (const order of ["test-first", "record-last"] as const) {
		const { code, smoke, ...sources } = sourceIdentityCapture(order);
		const cite = (fields: Record<string, unknown>) =>
			normalizeCitation({ startLine: 1, quote: "x", ...fields }, "", { sources });
		const unrelated = `context/citable/new/${smoke}`;
		for (const citation of [
			cite({
				sourceKind: "scm.pull-request.diff",
				sourcePath: "context/change.json",
				path: "README.md",
				side: "NEW",
			}),
			cite({ sourcePath: unrelated, path: "context/citable/new/README.md" }),
			cite({ sourcePath: "work/change/diff.patch", path: code, side: "NEW" }),
			cite({ sourceKind: "scm.pull-request.diff", path: "work/change/diff.patch" }),
			cite({
				sourceKind: "scm.pull-request.diff",
				sourcePath: unrelated,
				path: smoke,
				side: "NEW",
			}),
			cite({ sourcePath: unrelated, path: "context/change.json#/diff" }),
		]) {
			assert.equal(
				citation.sourcePath,
				"context/change.json",
				`${order}: ${JSON.stringify(citation)}`,
			);
			assert.equal(citation.sourceKind, "scm.pull-request.diff");
		}
		const readme = cite({ sourcePath: unrelated, path: "context/citable/new/README.md" });
		assert.equal(readme.path, "README.md");
		assert.equal(readme.side, "NEW");
		const old = cite({ sourcePath: unrelated, path: "context/citable/old/README.md" });
		assert.deepEqual(
			[old.path, old.side, old.sourcePath],
			["README.md", "OLD", "context/change.json"],
		);
	}
});

void test("a side on a file the change holds names the change when the citation names no source", () => {
	const { code, smoke: _smoke, ...sources } = sourceIdentityCapture("test-first");
	const cite = (fields: Record<string, unknown>) =>
		normalizeCitation({ startLine: 20, endLine: 20, quote: "x", ...fields }, "", { sources });
	// The live shape: optional identities omitted, the changed file, its side, lines and quote supplied.
	const live = cite({ path: code, side: "NEW" });
	assert.deepEqual(
		[live.sourceKind, live.sourcePath, live.path, live.side],
		["scm.pull-request.diff", "context/change.json", code, "NEW"],
	);
	const old = cite({ path: "README.md", side: "OLD" });
	assert.deepEqual([old.sourceKind, old.side], ["scm.pull-request.diff", "OLD"]);
	const pinned = cite({ path: code, side: "NEW", commit: "a".repeat(40) });
	assert.equal(pinned.sourceKind, "scm.pull-request.diff");
});

void test("a side names the change only for a file the change holds on that side, and never over a named source", () => {
	const { code, smoke: _smoke, ...sources } = sourceIdentityCapture("test-first");
	const cite = (fields: Record<string, unknown>) =>
		normalizeCitation({ startLine: 20, endLine: 20, quote: "x", ...fields }, "", { sources });
	for (const [name, fields] of [
		["named repository source", { sourceKind: "scm.repository.tree", path: code, side: "NEW" }],
		["named checkout witness", { sourcePath: "repos/reviewed/.git/HEAD", path: code, side: "NEW" }],
		["checkout path", { path: `repos/reviewed/${code}`, side: "NEW" }],
		["side the change does not hold", { path: code, side: "OLD" }],
		["file the change does not touch", { path: "docs/unchanged.md", side: "NEW" }],
		["another commit", { path: code, side: "NEW", commit: "f".repeat(40) }],
		["no side", { path: code }],
	] as const) {
		const citation = cite(fields);
		assert.deepEqual(
			[citation.sourceKind, citation.sourcePath],
			["scm.repository.tree", "repos/reviewed/.git/HEAD"],
			name,
		);
	}
	const record = cite({
		sourcePath: "context/description.md",
		path: "context/description.md",
		side: "NEW",
	});
	assert.equal(record.sourceKind, "scm.pull-request.core");
	const scan = normalizeCitation(
		{ path: "src/healthy.ts", side: "NEW", startLine: 1, quote: "x" },
		"",
		{
			sources: {
				sourceFiles: new Map([["context/item.json", "scm.repository.tree"]]),
				capturedTexts: [
					sourceIdentityText(
						"scm.repository.tree",
						"src/healthy.ts",
						"NEW",
						"a".repeat(40),
						"repos/reviewed/src/healthy.ts",
					),
				],
				sandboxRoot: "/sandbox",
				repositoryRoot: "repos/reviewed",
				headRevision: "a".repeat(40),
				treeSourceFile: "context/item.json",
			},
		},
	);
	assert.deepEqual(
		[scan.sourceKind, scan.sourcePath],
		["scm.repository.tree", "context/item.json"],
	);
});

void test("a literal # in a captured file name is part of the name, not a record fragment", () => {
	for (const order of ["test-first", "record-last"] as const) {
		const { code: _code, smoke: _smoke, ...capture } = sourceIdentityCapture(order);
		const CORE = "scm.pull-request.core";
		const text = (path: string, stagedPath: string) => ({
			source: CORE,
			path,
			side: "NEW" as const,
			commit: null,
			stagedPath,
			sha256: "e".repeat(64),
		});
		const sources = {
			...capture,
			sourceFiles: new Map([
				...capture.sourceFiles,
				["notes/a", CORE],
				["context/notes/a#b.md", CORE],
			]),
			capturedTexts: [
				...capture.capturedTexts,
				text("notes/a", "notes/a"),
				text("notes/a#b.md", "context/notes/a#b.md"),
			],
		};
		const citation = normalizeCitation(
			{
				sourcePath: "context/description.md",
				path: "context/notes/a#b.md",
				startLine: 1,
				quote: "x",
			},
			"",
			{ sources },
		);
		assert.deepEqual(
			[citation.sourcePath, citation.path],
			["context/notes/a#b.md", "notes/a#b.md"],
			order,
		);
	}
});

void test("a record citation keeps its own record, and repository text keeps the checkout witness", () => {
	for (const order of ["test-first", "record-last"] as const) {
		const { code, smoke: _smoke, ...sources } = sourceIdentityCapture(order);
		const cite = (fields: Record<string, unknown>) =>
			normalizeCitation({ startLine: 1, quote: "x", ...fields }, "", { sources });
		const fragment = cite({
			sourcePath: "context/description.md",
			path: "context/citable-records/scm.pull-request.core/2222.txt",
		});
		assert.deepEqual(
			[fragment.sourcePath, fragment.path],
			["context/metadata.json", "context/metadata.json#/body"],
		);
		const record = cite({ sourcePath: "context/description.md", path: "context/description.md" });
		assert.deepEqual(
			[record.sourcePath, record.path],
			["context/description.md", "context/description.md"],
		);
		const repository = cite({ sourcePath: "repos/reviewed/.git/HEAD", path: code });
		assert.deepEqual(
			[repository.sourceKind, repository.sourcePath, repository.path],
			["scm.repository.tree", "repos/reviewed/.git/HEAD", code],
		);
	}
});

void test("a path under another checkout keeps its prefix, and no note says otherwise", () => {
	const notes: string[] = [];
	const citation = normalizeCitation(
		{
			sourceKind: "scm.repository.tree",
			sourcePath: "repos/reviewed/.git/HEAD",
			path: "repos/linked/README.md",
			startLine: 3,
			quote: "Run make.",
		},
		"",
		{ notes },
	);
	assert.equal(citation.path, "repos/linked/README.md");
	assert.ok(!notes.some((note) => note.includes("its path inside the checkout")), notes.join("\n"));
});

void test("a checkout file named by its workspace path is read at its path inside the checkout, with a note", () => {
	const notes: string[] = [];
	const citation = normalizeCitation(
		{
			sourceKind: "scm.repository.tree",
			sourcePath: "repos/reviewed/.git/HEAD",
			path: "repos/reviewed/README.md",
			startLine: 3,
			quote: "Run make.",
		},
		"problem 1 citation 1: ",
		{ notes },
	);
	assert.equal(citation.path, "README.md");
	assert.deepEqual(notes, [
		"problem 1 citation 1: path repos/reviewed/README.md read as README.md, its path inside the checkout",
	]);
});

void test("historical citations preserve a full commit for trusted admission", () => {
	const citation = {
		sourceKind: "scm.repository.tree",
		sourcePath: "repos/repo/.git/HEAD",
		path: "deleted.ts",
		commit: "a".repeat(40),
		startLine: 3,
		quote: "historical text",
	};
	assert.equal(normalizeCitation(citation, "").commit, citation.commit);
	assert.throws(
		() => normalizeCitation({ ...citation, commit: "x".repeat(1025) }, "citation 1: "),
		/^Error: citation 1: commit must be an opaque nonempty version/u,
	);
	assert.equal(
		normalizeCitation({ ...citation, sourceKind: "scm.issue.core" }, "").commit,
		citation.commit,
	);
});

void test("a citation is completed from the manifest and its line numbers read as written", () => {
	const notes: string[] = [];
	const staged = new Map([
		["inputs/context/metadata.json", "scm.pull-request.core"],
		["inputs/context/change.json", "scm.pull-request.diff"],
	]);
	const submission = normalizeSubmission(
		baseSubmission({
			citations: [{ path: "inputs/context/metadata.json", startLine: "[L3]", quote: "x" }],
			problems: [
				problemFixture({
					citations: [
						{
							sourcePath: "inputs/context/change.json",
							path: "src/Auth.java",
							side: "NEW",
							startLine: "L10",
							endLine: "12",
							quote: "y",
						},
					],
				}),
			],
		}),
		notes,
		(sourceFile) => staged.get(sourceFile),
	);
	const [record, change] = submissionCitations(submission);
	assert.deepEqual(
		[record?.sourcePath, record?.sourceKind, record?.startLine],
		["inputs/context/metadata.json", "scm.pull-request.core", 3],
	);
	assert.deepEqual(
		[change?.sourceKind, change?.startLine, change?.endLine],
		["scm.pull-request.diff", 10, 12],
	);
	assert.deepEqual(notes, [
		"sourcePath filled in as inputs/context/metadata.json, the staged record the path names",
		"sourceKind filled in as scm.pull-request.core, the source that staged inputs/context/metadata.json",
		'startLine "[L3]" read as 3',
		"sourceKind filled in as scm.pull-request.diff, the source that staged inputs/context/change.json",
		'startLine "L10" read as 10',
		'endLine "12" read as 12',
	]);
	assert.throws(
		() =>
			normalizeSubmission(
				baseSubmission({
					citations: [
						{ path: "a.ts", startLine: 1 },
						{ path: "b.ts", startLine: 1 },
					],
				}),
				[],
				() => undefined,
			),
		/citations\.0: evidence citation sourceKind is required[\s\S]*Correction example/u,
	);
});

void test("normalization preserves the quoted source indentation and trailing spaces", () => {
	const quote = "    insecure();  ";
	assert.equal(normalizedCitation(citationFixture({ quote })).quote, quote);
});

void test("normalization preserves raw quote bytes and local preflight matches server line semantics", () => {
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10,2 +10,2 @@\n[L10] +  Trivio:  # TODO: Adjust Name\n[L11] +\n";
	for (const quote of [
		"  Trivio:  # TODO: Adjust Name",
		"  Trivio:  # TODO: Adjust Name\n",
		"+  Trivio:  # TODO: Adjust Name\r\n",
	]) {
		const citation = normalizedCitation(citationFixture({ quote }));
		assert.equal(citation.quote, quote);
		assert.equal(describeCitationMismatch(citation, diff), null);
	}
	const quote = "  Trivio:  # TODO: Adjust Name\r\n\r\n";
	const citation = normalizedCitation(citationFixture({ quote, endLine: 11 }));
	assert.equal(citation.quote, quote);
	assert.equal(describeCitationMismatch(citation, diff), null);
});

void test("citation coordinates are bounded", () => {
	for (const line of [2_147_483_648, 4_294_967_306, Number.MAX_SAFE_INTEGER + 1]) {
		assert.match(
			refusal(citing(citationFixture({ startLine: line, endLine: undefined }))),
			/startLine/u,
		);
		assert.match(refusal(citing(citationFixture({ endLine: line }))), /endLine/u);
	}
});

void test("normalization preserves nonblank citation path identifiers", () => {
	const citation = normalizedCitation(
		citationFixture({ path: " source file ", sourcePath: "inputs/context/ captured file " }),
	);
	assert.equal(citation.path, " source file ");
	assert.equal(citation.sourcePath, "inputs/context/ captured file ");
	for (const field of ["path", "sourcePath"]) {
		assert.match(
			refusal(citing(citationFixture({ [field]: " \t\n" }))),
			new RegExp(`${field} is required`, "u"),
		);
	}
});

void test("a quote copied with the brief's line coordinates is stored without them", () => {
	assert.equal(withoutCoordinates("[L15] to test the winner", 15), "to test the winner");
	assert.equal(withoutCoordinates("[L15] first\n[L16] second\n", 15), "first\nsecond\n");
	assert.equal(withoutCoordinates("[L9] elsewhere", 15), "[L9] elsewhere");
	assert.equal(withoutCoordinates("plain", 3), "plain");
	assert.equal(
		normalizedCitation(citationFixture({ quote: "[L10] + insecure();" })).quote,
		"+ insecure();",
	);
});

void test("diff citations bind the quote to the claimed file and side; a wrong line is corrected when the text is unique", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";
	assert.equal(describeCitationMismatch(citation, diff) === null, true);
	assert.equal(
		describeCitationMismatch({ ...citation, path: "src/Other.java" }, diff) === null,
		false,
	);
	assert.equal(describeCitationMismatch({ ...citation, side: "OLD" }, diff) === null, false);
	assert.deepEqual(resolveQuote({ ...citation, startLine: 11, endLine: 12 }, diff), {
		quote: " insecure();",
		startLine: 10,
		endLine: 10,
	});
});

void test("a quote may drop the diff marker and read the spacing differently; the text must match", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] +    insecure();\n";

	assert.deepEqual(resolveQuote({ ...citation, quote: "insecure();" }, diff), {
		quote: "    insecure();",
	});
	assert.equal(describeCitationMismatch({ ...citation, quote: "    insecure();" }, diff), null);
	assert.equal(describeCitationMismatch({ ...citation, quote: "+    insecure();" }, diff), null);
	assert.match(
		describeCitationMismatch({ ...citation, quote: "secure();" }, diff) ?? "",
		/\[L10\] reads/u,
	);

	const flagDiff =
		"diff --git a/run.sh b/run.sh\n+++ b/run.sh\n@@ -10 +10 @@\n[L10] +    -flag --now\n";
	assert.equal(
		describeCitationMismatch({ ...citation, path: "run.sh", quote: "    -flag --now" }, flagDiff),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...citation, path: "run.sh", quote: "+    -flag --now" }, flagDiff),
		null,
	);
});

const mismatch = (result: ReturnType<typeof resolveQuote>) =>
	"mismatch" in result ? result.mismatch : "";

const serializedFields = (values: string[]) =>
	values.map((value, index) => `    "text" : ${value}${index < values.length - 1 ? "," : ""}`);

const cite = (quote: string): Citation => ({
	sourceKind: "scm.pull-request.core",
	sourcePath: "inputs/context/core.md",
	path: "title",
	startLine: 1,
	endLine: 1,
	quote,
});

void test("a matching diff quote is recorded as the content its lines carry, markers dropped", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/run.sh b/run.sh\n+++ b/run.sh\n@@ -10,2 +10,2 @@\n[L10] +    -flag --now\n[L11] +  next\n";
	const cited = { ...citation, path: "run.sh", endLine: 11 };
	assert.deepEqual(resolveQuote({ ...cited, quote: "+    -flag --now\n+  next" }, diff), {
		quote: "    -flag --now\n  next",
	});
	assert.deepEqual(resolveQuote({ ...cited, quote: "    -flag --now\n  next" }, diff), {
		quote: "    -flag --now\n  next",
	});
	// Coordinates alone verify nothing, so a citation without a quote is refused.
	assert.match(mismatch(resolveQuote({ ...cited, quote: "" }, diff)), /has no quote/u);
	assert.match(
		mismatch(resolveQuote({ ...cited, path: "work/change/diff.patch", quote: "" }, diff)),
		/has no quote/u,
	);
	const blankContext =
		"diff --git a/a.md b/a.md\n+++ b/a.md\n@@ -9,2 +10,2 @@\n[L10] +text\n[L11]  \n";
	assert.deepEqual(
		resolveQuote(
			{ ...citation, path: "a.md", startLine: 10, endLine: 11, quote: "+text\n+" },
			blankContext,
		),
		{ quote: "text\n" },
	);
	const blankDiff = "diff --git a/a.md b/a.md\n+++ b/a.md\n@@ -9,0 +10,2 @@\n[L10] +\n[L11] +\n";
	assert.match(
		mismatch(
			resolveQuote(
				{ ...citation, path: "a.md", startLine: 10, endLine: 11, quote: "+\n+" },
				blankDiff,
			),
		),
		/cited lines are blank/u,
	);
	assert.match(
		mismatch(resolveQuote({ ...cited, quote: "+    -flag --now\n+  next\n+x", endLine: 12 }, diff)),
		/no \[L12\]/u,
	);
	assert.match(
		mismatch(resolveQuote({ ...cited, quote: "+    -flag\n+  next" }, diff)),
		/\[L10\] reads/u,
	);
});

void test("a quote is recorded as the sourceFile spells it: JSON escapes and non-breaking spaces", () => {
	const { side: _side, ...plain } = normalizedCitation();
	const citation = { ...plain, sourceKind: "scm.linked-work-items", startLine: 2, endLine: 2 };
	const serialized =
		'{\n  "body" : "## Criteria\\n- [x] stored in `diagrams/`\\n- say \\"hi\\""\n}\n';
	assert.deepEqual(resolveQuote({ ...citation, quote: '- say "hi"' }, serialized), {
		quote: String.raw`- say \"hi\"`,
	});
	assert.deepEqual(
		resolveQuote({ ...citation, quote: "- [x] stored in `diagrams/`" }, serialized),
		{
			quote: "- [x] stored in `diagrams/`",
		},
	);
	const nbsp = "* Launch app and open\u00A0`Venues`.\n";
	const first = { ...citation, startLine: 1, endLine: 1 };
	assert.deepEqual(resolveQuote({ ...first, quote: "* Launch app and open `Venues`." }, nbsp), {
		quote: "* Launch app and open\u00A0`Venues`.",
	});
	const steps = "## Testing\n\n1. Launch app and open\u00A0**Coupons**\u00A0tab.\n2. Verify.\n";
	assert.deepEqual(
		resolveQuote(
			{
				...citation,
				startLine: 1,
				endLine: 4,
				quote: "## Testing\n \n1. Launch app and open **Coupons** tab.\n2. Verify.",
			},
			steps,
		),
		{ quote: "## Testing\n\n1. Launch app and open\u00A0**Coupons**\u00A0tab.\n2. Verify." },
	);
	// Coordinates alone verify nothing, so a citation without a quote is refused.
	assert.match(mismatch(resolveQuote({ ...first, quote: "" }, nbsp)), /has no quote/u);
	assert.match(mismatch(resolveCapturedText({ ...first, quote: " " }, nbsp)), /has no quote/u);
	assert.deepEqual(resolveQuote({ ...citation, quote: "## Criteria\n- [x] stored" }, serialized), {
		quote: String.raw`## Criteria\n- [x] stored`,
	});
});

void test("spacing reads as spacing only where the quote has some, and line breaks stay line breaks", () => {
	const line: Citation = {
		sourceKind: "scm.linked-work-items",
		sourcePath: "inputs/context/issue.json",
		path: "inputs/context/issue.json",
		startLine: 1,
		endLine: 1,
		quote: "",
	};
	assert.deepEqual(resolveQuote({ ...line, quote: "return  null;" }, "return null;\n"), {
		quote: "return null;",
	});
	assert.match(
		mismatch(resolveQuote({ ...line, quote: "returnnull;" }, "return null;\n")),
		/reads/u,
	);
	assert.match(mismatch(resolveQuote({ ...line, quote: "foo" }, "f oo\n")), /reads/u);
	const crlf = "if (a) {\r\n  return null;\r\n}\r\n";
	assert.deepEqual(resolveQuote({ ...line, endLine: 2, quote: "if (a) {\nreturn null;" }, crlf), {
		quote: "if (a) {\r\n  return null;",
	});
	assert.match(
		mismatch(resolveQuote({ ...line, endLine: 2, quote: "if (a) { return null;" }, crlf)),
		/reads/u,
	);
	assert.match(
		mismatch(resolveQuote({ ...line, quote: "return\nnull;" }, "return null;\n")),
		/reads/u,
	);
	assert.deepEqual(resolveQuote({ ...line, quote: "end\r" }, "none\nend\r\n"), {
		quote: "end\r",
		startLine: 2,
		endLine: 2,
	});
	assert.deepEqual(resolveQuote({ ...line, quote: "end \r" }, "none\nend  \r\n"), {
		quote: "end  \r",
		startLine: 2,
		endLine: 2,
	});
	assert.match(mismatch(resolveQuote({ ...line, quote: "end\r" }, "none\nend \r\n")), /reads/u);
	assert.match(
		mismatch(resolveQuote({ ...line, quote: "end\r \nnext" }, "none\nend\r\nnext\n")),
		/reads/u,
	);
});

void test("a quote of many indented lines resolves promptly, and so does a near miss", () => {
	const block = serializedFields([
		"0",
		'"text"',
		"0",
		'"text"',
		'"text"',
		'"text"',
		"false",
		'"text"',
	]);
	const short = serializedFields(['"text"', '"text"', '"text"']);
	const threads = [
		"{",
		'  "text" : [ {',
		...block,
		"  }, {",
		...block,
		"  } ],",
		'  "text" : [ {',
		...short,
		"  }, {",
		...short,
		"  } ],",
		'  "text" : false',
		"}",
	].join("\n");
	const quote = ['  "text" : [ {', ...block, "  }, {", ...block, "  } ]"].join("\n");
	const citation: Citation = {
		sourceKind: "scm.review-threads",
		sourcePath: "threads.json",
		path: "threads.json",
		startLine: 2,
		endLine: 19,
		quote,
	};
	assert.deepEqual(resolveQuote(citation, threads), { quote, startLine: 2, endLine: 20 });
	const unindented = quote
		.split("\n")
		.map((quoted) => quoted.trimStart())
		.join("\n");
	assert.deepEqual(resolveQuote({ ...citation, quote: unindented }, threads), {
		quote: quote.trimStart(),
		startLine: 2,
		endLine: 20,
	});
	assert.match(mismatch(resolveQuote({ ...citation, quote: `${quote}]` }, threads)), /reads/u);
	assert.match(
		mismatch(
			resolveQuote(
				{ ...citation, startLine: 1, endLine: 1, quote: `${"a ".repeat(1024)}z` },
				`${"a ".repeat(131_072)}y`,
			),
		),
		/reads/u,
	);
});

void test("a quote from the other side of the change is refused, however it is written", () => {
	const citation: Citation = {
		...normalizedCitation(),
		side: "NEW",
		startLine: 47,
		endLine: 47,
		quote: '-@RequestMapping({ "api/legacy/" })',
	};
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -47 +47 @@\n" +
		'[L47] -@RequestMapping({ "api/legacy/" })\n[L47] +@RequestMapping("api/passkeys/")\n';

	assert.match(describeCitationMismatch(citation, diff) ?? "", /\[L47\] reads/u);
});

void test("a quote may carry its exact displayed coordinate", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";

	assert.equal(describeCitationMismatch({ ...citation, quote: "[L10] + insecure();" }, diff), null);
	assert.match(
		describeCitationMismatch({ ...citation, quote: "[L11] + insecure();" }, diff) ?? "",
		/\[L10\] reads/u,
	);
});

void test("a refused citation says which of the coordinate, the side and the text was wrong", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10 @@\n[L10] + insecure();\n";

	assert.equal(describeCitationMismatch(citation, diff), null);
	assert.deepEqual(resolveQuote({ ...citation, startLine: 11, endLine: 11 }, diff), {
		quote: " insecure();",
		startLine: 10,
		endLine: 10,
	});
	assert.match(
		describeCitationMismatch({ ...citation, startLine: 11, endLine: 11, quote: "gone();" }, diff) ??
			"",
		/no \[L11\] on that side of that path/u,
	);
	assert.match(
		describeCitationMismatch({ ...citation, quote: "+ secure();" }, diff) ?? "",
		/\[L10\] reads "\+ insecure\(\);", not "\+ secure\(\);"/u,
	);
	assert.deepEqual(resolveQuote({ ...citation, endLine: 12 }, diff), {
		quote: " insecure();",
		startLine: 10,
		endLine: 10,
	});
	assert.match(
		describeCitationMismatch({ ...citation, endLine: 12, quote: "gone();\nalso();" }, diff) ?? "",
		/quote is 2 line\(s\) and the citation covers 3/u,
	);
});

void test("a citation of the diff view itself is placed at the changed line its quote names, when that is one line", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -10 +10,2 @@\n" +
		"[L10] -    secure();\n[L10] +    insecure();\n[L11] +    audit();\n" +
		"diff --git a/src/Log.java b/src/Log.java\n+++ b/src/Log.java\n@@ -3 +3 @@\n[L3] +    audit();\n";
	const ofView = { ...citation, path: "work/change/diff.patch", startLine: 92, endLine: 92 };
	assert.deepEqual(resolveQuote({ ...ofView, quote: "insecure();" }, diff), {
		quote: "    insecure();",
		startLine: 10,
		endLine: 10,
		path: "src/Auth.java",
		side: "NEW",
	});
	assert.deepEqual(resolveQuote({ ...ofView, quote: "secure();" }, diff), {
		quote: "    secure();",
		startLine: 10,
		endLine: 10,
		path: "src/Auth.java",
		side: "OLD",
	});
	assert.match(
		describeCitationMismatch({ ...ofView, quote: "audit();" }, diff) ?? "",
		/work\/change\/diff\.patch is the view of the change, not a file in it: cite the changed file's path[\s\S]*occurs 2 times/u,
	);
	assert.match(
		describeCitationMismatch({ ...ofView, quote: "absent();" }, diff) ?? "",
		/is not a line of the change/u,
	);
});

void test("a coordinate copied from a numbered view of diff.patch is read as the line it names", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n" +
		"@@ -10,0 +10,1 @@\n[L10] + insecure();\n@@ -20,0 +21,1 @@\n[L21] + insecure();\n";
	assert.deepEqual(
		resolveQuote({ ...citation, startLine: 7, endLine: 7, quote: "insecure();" }, diff),
		{ quote: " insecure();", startLine: 21, endLine: 21 },
	);
	assert.match(
		describeCitationMismatch(
			{ ...citation, startLine: 4, endLine: 4, quote: "insecure();" },
			diff,
		) ?? "",
		/occurs at \[L10\], \[L21\] — cite the one you mean/u,
	);
});

void test("a file the diff shows without numbered lines is refused with what to cite instead", () => {
	const citation = normalizedCitation();
	const diff =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n" +
		"@@ -10 +10 @@\n[L10] + insecure();\n" +
		"diff --git a/logo.png b/logo.png\nnew file mode 100644\nBinary files /dev/null and b/logo.png differ\n" +
		"diff --git a/old.txt b/new.txt\nsimilarity index 100%\nrename from old.txt\nrename to new.txt\n" +
		"diff --git a/gone.txt b/gone.txt\ndeleted file mode 100644\n--- a/gone.txt\n+++ /dev/null\n" +
		"@@ -1 +0,0 @@\n[L1] -bye\n";
	const at = (path: string, side: "OLD" | "NEW" = "NEW") =>
		describeCitationMismatch({ ...citation, path, side, startLine: 361, endLine: 361 }, diff) ?? "";

	assert.match(at("logo.png"), /no numbered lines in the diff[\s\S]*commits\.json/u);
	assert.match(at("new.txt"), /no numbered lines in the diff/u);
	assert.match(at("old.txt", "OLD"), /no numbered lines in the diff/u);
	assert.match(at("gone.txt"), /no lines on the NEW side[\s\S]*on the OLD side/u);
	assert.match(at("elsewhere.txt"), /the change does not touch elsewhere\.txt/u);
});

void test("removed-line citations use old-side coordinates", () => {
	const citation: Citation = {
		...normalizedCitation(),
		side: "OLD",
		startLine: 8,
		endLine: 8,
		quote: "- requireAdmin();",
	};
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -8 +8 @@\n[L8] - requireAdmin();\n[L8] + allowAll();\n";
	assert.equal(describeCitationMismatch(citation, diff) === null, true);
	assert.equal(describeCitationMismatch({ ...citation, side: "NEW" }, diff) === null, false);
});

void test("a claim about an earlier review is bound to the staged history like any other citation", () => {
	const submission = normalizeSubmission(
		citing({
			sourceKind: "hephaestus.problem-history",
			sourcePath: "inputs/history/problems.json",
			path: "inputs/history/problems.json",
			startLine: 1,
			endLine: 1,
			quote: '"recurrenceKey": "rec-1"',
		}),
	);
	const [staged, sourceFiles] = withCheckout(
		["scm.pull-request.diff", "hephaestus.problem-history"],
		[
			["inputs/history/problems.json", "hephaestus.problem-history"],
			["inputs/context/diff.patch", "scm.pull-request.diff"],
		],
	);

	assert.doesNotThrow(() => validateEvidenceSources(submission, staged, sourceFiles));
	const cited = onlyCitation(submission.citations);
	const bytes = '{"problems":[{"recurrenceKey": "rec-1","title":"Caught and ignored"}]}';
	assert.equal(describeCitationMismatch(cited, bytes) === null, true);
	const invented = { ...cited, quote: '"recurrenceKey": "invented"' };
	assert.equal(describeCitationMismatch(invented, bytes) === null, false);
	assert.equal(
		describeCitationMismatch(invented, bytes),
		`[L1] reads ${JSON.stringify(bytes)}, not ${JSON.stringify(invented.quote)}`,
	);
	assert.equal(
		describeCitationMismatch({ ...invented, startLine: 3, endLine: 3 }, `${bytes}\n`),
		"the sourceFile has 1 line(s), so there is no [L3]",
	);
	const serialized = '{"body": "## Criteria\\n- [x] stored in `diagrams/`\\n- [x] embedded"}\n';
	assert.deepEqual(
		resolveQuote({ ...invented, quote: "## Criteria\n- [x] stored in `diagrams/`" }, serialized),
		{ quote: "## Criteria\\n- [x] stored in `diagrams/`" },
	);
	assert.match(
		describeCitationMismatch({ ...invented, quote: "## Criteria\n- [x] missing" }, serialized) ??
			"",
		/^\[L1\] reads .*; this is a JSON string whose line breaks are the two characters \\n, so quote a fragment from between two of them, or spell them as the line does$/u,
	);
	assert.equal(
		describeCitationMismatch({ ...invented, quote: "- [x] stored in `diagrams/`" }, serialized),
		null,
	);
});

void test("a citation rejects typographic substitutions not present in the sourceFile", () => {
	const content = 'Resolve "Connect data between screens" — see the plan';
	assert.equal(
		describeCitationMismatch(cite('Resolve "Connect data between screens"'), content) === null,
		true,
	);
	assert.equal(
		describeCitationMismatch(cite("Resolve “Connect data between screens”"), content) === null,
		false,
	);
	assert.equal(describeCitationMismatch(cite("see the plan"), content) === null, true);
});

void test("a citation rejects invented sourceFile text", () => {
	const content = 'Resolve "Connect data between screens"';
	assert.equal(
		describeCitationMismatch(cite("Resolve “Disconnect data between screens”"), content) === null,
		false,
	);
	assert.equal(
		describeCitationMismatch(cite("a rationale the author never wrote"), content) === null,
		false,
	);
});

void test("live practice fixture permits a citation of its planted credential", () => {
	const diff = readFileSync(new URL("live-practice/diff.patch", import.meta.url), "utf8");
	assert.equal(
		describeCitationMismatch(
			{
				sourceKind: "scm.pull-request.diff",
				sourcePath: "inputs/context/diff.patch",
				path: "LoginService.swift",
				side: "NEW",
				startLine: 4,
				endLine: 4,
				quote: '    private let apiKey = "sk-live-AKIAIOSFODNN7EXAMPLE-prod-2026"',
			},
			diff,
		),
		null,
	);
});

void test("a serialized-source quote at the wrong lines is recorded where it occurs, when that is one place", () => {
	const citation: Citation = {
		sourceKind: "scm.pull-request.core",
		sourcePath: "inputs/context/metadata.json",
		path: "inputs/context/metadata.json",
		startLine: 1,
		endLine: 1,
		quote: '"changed_files" : 1',
	};
	const content = '{\n  "changed_files" : 1\n}\n';
	assert.deepEqual(resolveQuote(citation, content), {
		quote: '"changed_files" : 1',
		startLine: 2,
		endLine: 2,
	});
	assert.match(
		describeCitationMismatch(citation, `${content}  "changed_files" : 1\n`) ?? "",
		/it occurs at \[L2\], \[L4\] — cite the one you mean/u,
	);
	assert.equal(
		describeCitationMismatch({ ...citation, startLine: 2, endLine: 2 }, content) === null,
		true,
	);
	assert.equal(
		describeCitationMismatch(
			{ ...citation, startLine: 2, endLine: 2, quote: '  "changed_files" : 1\n' },
			content,
		) === null,
		true,
	);
});

void test("annotated source text is not parsed as a header and Unicode separators remain source text", () => {
	const cited = normalizedCitation();
	const diff =
		"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] --- SQL comment\n[L10] +++ value\u2028tail\u2029end\n";
	assert.equal(
		describeCitationMismatch({ ...cited, side: "OLD", quote: "-- SQL comment" }, diff),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...cited, quote: "++ value\u2028tail\u2029end" }, diff),
		null,
	);
	assert.notEqual(
		describeCitationMismatch(
			{ ...cited, endLine: 11, quote: "x\n\n" },
			"--- a/src/Auth.java\n+++ b/src/Auth.java\n[L10] +x\n[L11] ",
		),
		null,
	);
	assert.equal(
		describeCitationMismatch({ ...cited, path: '"', quote: "x" }, '--- "\n+++ "\n[L10] +x\n'),
		null,
	);
});

void test("a decided claim preserves its submitted bounded search", () => {
	assert.equal("search" in normalizeSubmission(baseSubmission()).evidence, false);
	const withSearch = normalizeSubmission(baseSubmission({ evidence: { search: goodSearch } }));
	assert.deepEqual(withSearch.evidence.search, goodSearch);
	assert.equal(withSearch.citations.length, 1);
});

void test("a direct-evidence claim does not require a search warrant", () => {
	assert.doesNotThrow(() =>
		validateSearchScope(
			normalizeSubmission(baseSubmission()),
			new Set(["scm.review-threads"]),
			new Set(),
		),
	);
});

void test("an absence-based MET claim needs exhaustive captured evidence", () => {
	const submission = normalizeSubmission(resultOf("MET"));
	assert.throws(
		() => validateSearchScope(submission, new Set(), new Set(goodSearch.consulted)),
		/^Error: MET for this practice rests on an absence claim, and the practice declares no source it searches exhaustively, so no search can bound that claim\. Record what the evidence does show: MET from cited evidence, DOES_NOT_APPLY when the work gives the practice no applicability, or COULD_NOT_DETERMINE with what would settle it$/u,
	);
	assert.throws(
		() => validateSearchScope(submission, new Set(["unread"]), new Set(goodSearch.consulted)),
		/^Error: an absence claim for this practice rests on searching unread as well: add it to evidence\.search\.consulted once you have searched it$/u,
	);
	assert.throws(
		() => validateSearchScope(submission, new Set(goodSearch.consulted), new Set()),
		/^Error: searched source 'scm\.review-threads' was not available/u,
	);
	validateSearchScope(submission, new Set(goodSearch.consulted), new Set(goodSearch.consulted));
});

void test("a NOT_MET or COULD_NOT_DETERMINE search names every exhaustive source it left out", () => {
	const available = new Set(["scm.review-threads", "scm.linked-work-items", "scm.issue.core"]);
	for (const submission of [
		normalizeSubmission(baseSubmission({ evidence: { search: goodSearch } })),
		normalizeSubmission(resultOf("COULD_NOT_DETERMINE")),
	]) {
		assert.doesNotThrow(() => validateSearchScope(submission, new Set(), available));
		assert.throws(
			() => validateSearchScope(submission, available, available),
			/rests on searching scm\.issue\.core, scm\.linked-work-items as well: add them to evidence\.search\.consulted once you have searched them$/u,
		);
	}
});

void test("an inapplicability names only sources the run staged", () => {
	const submission = normalizeSubmission(resultOf("DOES_NOT_APPLY"));
	assert.throws(
		() => validateInapplicabilityScope(submission, new Set()),
		/^Error: consulted source 'scm\.pull-request\.diff' was not available/u,
	);
	assert.doesNotThrow(() =>
		validateInapplicabilityScope(submission, new Set(["scm.pull-request.diff"])),
	);
	assert.doesNotThrow(() =>
		validateInapplicabilityScope(normalizeSubmission(resultOf("MET")), new Set()),
	);
});

const THREAD = `{
  "channel" : "C123",
  "messages" : [ {
    "ts" : "1700000000.100000",
    "text" : "Can someone look at the {failing} build?",
    "accountId" : "42"
  }, {
    "ts" : "1700000000.200000",
    "text" : "It is \\"broken\\". Fix it.",
    "accountId" : "9007199254740993"
  } ]
}
`;

function threadCitation(line: number): Citation {
	return {
		...normalizedCitation(),
		sourceKind: "slack.conversation.thread",
		sourcePath: CONVERSATION_THREAD,
		path: "conversation_thread.json",
		startLine: line,
		endLine: line,
	};
}

void test("a conversation citation counts only inside a turn of the participant under review", () => {
	assert.equal(citesReviewedTurn([threadCitation(5)], THREAD, "9007199254740993"), false);
	assert.equal(citesReviewedTurn([threadCitation(9)], THREAD, "9007199254740993"), true);
	assert.equal(
		citesReviewedTurn([threadCitation(5), threadCitation(9)], THREAD, "9007199254740993"),
		true,
	);
	assert.equal(
		citesReviewedTurn(
			[{ ...threadCitation(9), sourcePath: "context/comments.json" }],
			THREAD,
			"9007199254740993",
		),
		false,
	);
	assert.equal(citesReviewedTurn([threadCitation(9)], "not json", "9007199254740993"), false);
});

const messageTextCitation = (index: number) => ({
	...threadCitation(1),
	path: `${CONVERSATION_THREAD}#/messages/${index}/text`,
});

void test("logical message text citations use the frozen native account rather than another author", () => {
	assert.equal(citesReviewedTurn([messageTextCitation(0)], THREAD, "9007199254740993"), false);
	assert.equal(citesReviewedTurn([messageTextCitation(1)], THREAD, "9007199254740993"), true);
	assert.equal(citesReviewedTurn([messageTextCitation(1)], THREAD, "42"), false);
	assert.equal(citesReviewedTurn([messageTextCitation(0)], THREAD, "42"), true);
	assert.equal(citesReviewedTurn([messageTextCitation(1)], THREAD, "7"), false);
});

void test("a thread whose messages are not all objects is refused, never read with shifted turns", () => {
	const thread = `{
  "messages" : [ "joined", {
    "accountId" : "42"
  }, {
    "accountId" : "7"
  } ]
}
`;
	assert.equal(citesReviewedTurn([threadCitation(5)], thread, "42"), false);
	assert.equal(citesReviewedTurn([threadCitation(3)], thread, "42"), false);
	const text = { ...threadCitation(1), path: `${CONVERSATION_THREAD}#/messages/1/text` };
	assert.equal(citesReviewedTurn([text], thread, "42"), false);
});

void test("a registered logical text resolves plaintext independently of source naming", () => {
	const citation = {
		sourceKind: "scm.pull-request.diff",
		sourcePath: "context/change.json",
		path: "src/a.ts",
		side: "NEW" as const,
		startLine: 2,
		endLine: 2,
		quote: "  checked();",
	};
	assert.deepEqual(resolveCapturedText(citation, "first\n  checked();\n"), {
		quote: "  checked();",
	});
});

void test("quotation coordinates follow the shared Java admission contract", () => {
	const fixtures: unknown = JSON.parse(
		readFileSync(new URL("citation-coordinate-cases.json", import.meta.url), "utf8"),
	);
	assert.ok(Array.isArray(fixtures));
	for (const fixture of fixtures) {
		const parsed = coordinateCase.parse(fixture);
		const result = resolveCapturedText(
			{
				sourceKind: "docs.document.core",
				sourcePath: "context/body.txt",
				path: "document/body",
				startLine: parsed.startLine,
				endLine: parsed.endLine,
				quote: parsed.quote,
			},
			parsed.content,
		);
		if (parsed.expectedStartLine === null) {
			assert.ok("mismatch" in result, parsed.name);
		} else {
			assert.ok("quote" in result, parsed.name);
			assert.equal(result.startLine ?? parsed.startLine, parsed.expectedStartLine, parsed.name);
		}
	}
});

void test("a repeated diff quote uses supplied view coordinates before relocation", () => {
	const fixtures: unknown = JSON.parse(
		readFileSync(new URL("citation-coordinate-cases.json", import.meta.url), "utf8"),
	);
	const fixture = z
		.array(coordinateCase)
		.parse(fixtures)
		.find((entry) => entry.name === "second repeated quote");
	assert.ok(fixture !== undefined);
	const diff = `diff --git a/src/a.ts b/src/a.ts\n--- a/src/a.ts\n+++ b/src/a.ts\n@@ -0,0 +1,4 @@\n${fixture.content
		.trimEnd()
		.split("\n")
		.map((line, index) => `[L${index + 1}] +${line}`)
		.join("\n")}`;
	const result = resolveQuote(
		{
			sourceKind: "scm.pull-request.diff",
			sourcePath: "context/change.json",
			path: "src/a.ts",
			side: "NEW",
			startLine: fixture.startLine + 4,
			endLine: fixture.endLine + 4,
			quote: fixture.quote,
		},
		diff,
	);
	assert.ok("quote" in result);
	assert.equal(result.startLine, fixture.expectedStartLine);
	assert.equal(result.quote, fixture.quote);
});

const basisRange = { base: "b".repeat(40), head: "a".repeat(40) };
const basisDiff = `diff --git a/src/Auth.java b/src/Auth.java
--- a/src/Auth.java
+++ b/src/Auth.java
@@ -9,2 +9,2 @@
[L9] - before();
[L9] + after();
[L10]   unchanged();
`;

void test("a code change basis needs an added head witness and permits an unchanged base", () => {
	const introduced = normalizeSubmission(
		baseSubmission({
			problems: [
				problemFixture({
					citations: [citationFixture({ startLine: 10, endLine: 10, quote: "unchanged();" })],
				}),
			],
		}),
	);
	assert.throws(
		() => validateChangeBasis(introduced, basisDiff, basisRange),
		/head witness must intersect added lines/u,
	);
	const changed = normalizeSubmission(
		baseSubmission({
			problems: [
				problemFixture({
					citations: [
						citationFixture({ startLine: 9, endLine: 10, quote: "after();\nunchanged();" }),
					],
				}),
			],
		}),
	);
	assert.doesNotThrow(() => validateChangeBasis(changed, basisDiff, basisRange));
	const worsened = normalizeSubmission(
		baseSubmission({
			problems: [
				problemFixture({
					citations: [
						citationFixture({ startLine: 9, endLine: 9, quote: "after();" }),
						citationFixture({ side: "OLD", startLine: 10, endLine: 10, quote: "unchanged();" }),
					],
					basis: {
						type: "worsened",
						head: 0,
						base: 1,
						explanation: "The change makes this worse.",
					},
				}),
			],
		}),
	);
	assert.doesNotThrow(() => validateChangeBasis(worsened, basisDiff, basisRange));
});

void test("additive worsening needs an added head witness; deletion and preexisting witnesses are refused", () => {
	const citation = {
		sourceKind: "scm.pull-request.diff",
		sourcePath: "context/change.json",
		path: "src/Auth.java",
		startLine: 1,
		endLine: 1,
		quote: "unsafe();",
	};
	const candidate = normalizeSubmission(
		baseSubmission({
			problems: [
				problemFixture({
					citations: [
						{ ...citation, side: "OLD" },
						{ ...citation, side: "NEW", startLine: 2, endLine: 2 },
					],
					basis: {
						type: "worsened",
						base: 0,
						head: 1,
						explanation: "The existing unsafe operation now runs twice.",
					},
				}),
			],
		}),
	);
	const additive =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -1 +1,2 @@\n[L1]  unsafe();\n[L2] +unsafe();\n";
	assert.doesNotThrow(() => validateChangeBasis(candidate, additive, basisRange));
	const base = candidate.problems?.[0]?.citations[0];
	assert.ok(base !== undefined);
	base.commit = "c".repeat(40);
	assert.throws(
		() => validateChangeBasis(candidate, additive, basisRange),
		/base witness must belong to the pinned base revision/u,
	);
	base.commit = basisRange.base;
	base.side = "NEW";
	assert.throws(
		() => validateChangeBasis(candidate, additive, basisRange),
		/base witness must belong to the pinned base revision/u,
	);
	base.side = "OLD";

	const head = candidate.problems?.[0]?.citations[1];
	assert.ok(head !== undefined);
	head.startLine = 1;
	head.endLine = 1;
	assert.throws(
		() => validateChangeBasis(candidate, additive, basisRange),
		/head witness must intersect added lines/u,
	);
	const deletion =
		"diff --git a/src/Auth.java b/src/Auth.java\n--- a/src/Auth.java\n+++ b/src/Auth.java\n@@ -1,2 +1 @@\n[L1]  unsafe();\n[L2] -unsafe();\n";
	assert.throws(
		() => validateChangeBasis(candidate, deletion, basisRange),
		/head witness must intersect added lines/u,
	);
});

void test("null problems normalize to absence for every outcome without problems", () => {
	for (const outcome of ["MET", "DOES_NOT_APPLY", "COULD_NOT_DETERMINE"] as const) {
		const candidate = resultOf(outcome);
		assert.deepEqual(
			normalizeSubmission({ ...candidate, problems: null }),
			normalizeSubmission(candidate),
		);
	}
});
