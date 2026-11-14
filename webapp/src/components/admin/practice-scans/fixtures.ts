import type {
	AccountRef,
	ItemRef,
	ScanCounts,
	ScanDetail,
	ScanPracticeReport,
	ScanReport,
	ScanReportItem,
	ScanReportProblem,
	ScanSummary,
} from "@/api/types.gen";
import { daysBefore, hoursBefore, minutesBefore } from "@/stories/story-clock";

export const ADA: AccountRef = {
	id: "7",
	displayName: "Ada Lovelace",
	avatarUrl: null,
	status: "ACTIVE",
	identities: [{ provider: "GITHUB", login: "ada", displayName: "Ada Lovelace", avatarUrl: null }],
};

export function repositoryRef(id = "300", name = "hephaestus"): ItemRef {
	return {
		id,
		kind: "scm.repository",
		label: `ls1intum/${name}`,
		container: "ls1intum",
		parentId: null,
		path: null,
		provider: "GITHUB",
		removedAt: null,
		title: name,
		url: `https://github.com/ls1intum/${name}`,
	};
}

export function fileRef(id: string, path: string, removedAt: Date | null = null): ItemRef {
	return {
		id,
		kind: "scm.file",
		label: path,
		container: "ls1intum/hephaestus",
		parentId: "300",
		path,
		provider: "GITHUB",
		removedAt,
		title: path,
		url: `https://github.com/ls1intum/hephaestus/blob/main/${path}`,
	};
}

export const REPOSITORIES: ItemRef[] = [repositoryRef("300"), repositoryRef("301", "artemis")];

export const COMPLETE_COUNTS: ScanCounts = {
	files: 3,
	practiceItemPairs: 6,
	reviewed: 4,
	reused: 1,
	met: 2,
	notMet: 1,
	doesNotApply: 1,
	couldNotDetermine: 0,
	problems: 3,
	openProblems: 2,
	withheld: 1,
	notReviewed: 1,
};

export const RUNNING_COUNTS: ScanCounts = {
	...COMPLETE_COUNTS,
	files: null,
	practiceItemPairs: null,
	reviewed: 2,
	reused: 0,
	met: 1,
	notMet: 1,
	doesNotApply: 0,
	problems: 2,
	openProblems: 2,
	withheld: 0,
	notReviewed: 0,
};

export const EMPTY_COUNTS: ScanCounts = {
	files: null,
	practiceItemPairs: null,
	reviewed: 0,
	reused: 0,
	met: 0,
	notMet: 0,
	doesNotApply: 0,
	couldNotDetermine: 0,
	problems: 0,
	openProblems: 0,
	withheld: 0,
	notReviewed: 0,
};

const COMMIT = "4f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39";

export function scanSummary(overrides: Partial<ScanSummary> = {}): ScanSummary {
	return {
		id: "6f1c2a7e-0d4b-4a8e-9b51-1a2b3c4d5e6f",
		repository: repositoryRef(),
		ref: "main",
		itemVersion: COMMIT,
		origin: "REQUEST",
		status: "COMPLETE",
		requestedBy: ADA,
		createdAt: hoursBefore(3),
		startedAt: hoursBefore(3),
		completedAt: hoursBefore(2),
		practiceCount: 2,
		counts: COMPLETE_COUNTS,
		baselineScanId: "0b0b0b0b-1111-4222-8333-444455556666",
		stopReason: null,
		...overrides,
	};
}

export const QUEUED_SCAN = scanSummary({
	id: "a0000000-0000-4000-8000-000000000001",
	status: "QUEUED",
	startedAt: null,
	completedAt: null,
	createdAt: minutesBefore(1),
	counts: EMPTY_COUNTS,
});
export const RUNNING_SCAN = scanSummary({
	id: "a0000000-0000-4000-8000-000000000002",
	status: "RUNNING",
	completedAt: null,
	createdAt: minutesBefore(12),
	startedAt: minutesBefore(11),
	counts: RUNNING_COUNTS,
});
export const COMPLETE_SCAN = scanSummary();
export const PARTIAL_SCAN = scanSummary({
	id: "a0000000-0000-4000-8000-000000000004",
	status: "PARTIAL",
	stopReason: "BUDGET",
	createdAt: daysBefore(2),
	startedAt: daysBefore(2),
	completedAt: daysBefore(2),
});
export const CANCELLED_SCAN = scanSummary({
	id: "a0000000-0000-4000-8000-000000000005",
	status: "CANCELLED",
	stopReason: "CANCELLED_BY_ADMIN",
	requestedBy: null,
	origin: "SCHEDULE",
	repository: repositoryRef("301", "artemis"),
	createdAt: daysBefore(5),
	counts: EMPTY_COUNTS,
});
/** A first scan that failed while it listed the repository: it reached no file and has no earlier scan. */
export const FAILED_SCAN = scanSummary({
	id: "a0000000-0000-4000-8000-000000000006",
	status: "FAILED",
	stopReason: "ENUMERATION_FAILED",
	baselineScanId: null,
	practiceCount: 0,
	counts: EMPTY_COUNTS,
});

export const SCANS: ScanSummary[] = [RUNNING_SCAN, COMPLETE_SCAN, PARTIAL_SCAN, CANCELLED_SCAN];

export function scanDetail(scan: ScanSummary, overrides: Partial<ScanDetail> = {}): ScanDetail {
	const active = scan.status === "QUEUED" || scan.status === "RUNNING";
	return {
		...scan,
		practices: [
			{
				practiceId: "11",
				practiceSlug: "error-handling",
				practiceName: "Errors are handled where they occur",
				practiceVersionId: "111",
				number: 3,
				reviewDigest: "sha256:aa",
			},
			{
				practiceId: "12",
				practiceSlug: "comment-quality",
				practiceName: "Comments explain why",
				practiceVersionId: "121",
				number: 1,
				reviewDigest: "sha256:bb",
			},
		],
		budgetUsd: "10",
		chargedCostUsd: active ? null : "1.42",
		progress: {
			listedPairs: scan.counts.practiceItemPairs,
			terminalPairs: scan.counts.reviewed + scan.counts.withheld + scan.counts.notReviewed,
			activeJobs: active ? 2 : 0,
			queuedJobs: scan.status === "QUEUED" ? 1 : 0,
		},
		...overrides,
	};
}

function practiceReport(
	slug: string,
	name: string,
	counts: ScanCounts,
	overrides: Partial<ScanPracticeReport> = {},
): ScanPracticeReport {
	return {
		practiceId: slug === "error-handling" ? "11" : "12",
		practiceSlug: slug,
		practiceName: name,
		practiceVersionId: "111",
		number: 3,
		reviewDigest: "sha256:aa",
		counts,
		listingComplete: true,
		method: { epoch: 1, modelDigest: "sha256:model", models: [] },
		comparison: "COMPARABLE",
		reason: null,
		status: "DONE",
		...overrides,
	};
}

export function scanReport(scan: ScanSummary, overrides: Partial<ScanReport> = {}): ScanReport {
	const terminal = scan.status !== "QUEUED" && scan.status !== "RUNNING";
	return {
		scan,
		complete: scan.status === "COMPLETE",
		listingComplete: scan.counts.files !== null,
		baselineScanId: scan.baselineScanId,
		comparison: "COMPARABLE",
		counts: scan.counts,
		recordedCounts: terminal ? scan.counts : null,
		problemChanges: { new: 1, unchanged: 1, updated: 1, absent: 2 },
		practices: [
			practiceReport("error-handling", "Errors are handled where they occur", {
				...scan.counts,
				files: scan.counts.files,
				practiceItemPairs: scan.counts.files,
				reviewed: 2,
				reused: 1,
				met: 1,
				notMet: 1,
				doesNotApply: 0,
				problems: 3,
				openProblems: 2,
				withheld: 1,
				notReviewed: 0,
			}),
			practiceReport(
				"comment-quality",
				"Comments explain why",
				{
					...scan.counts,
					practiceItemPairs: scan.counts.files,
					reviewed: 2,
					reused: 0,
					met: 1,
					notMet: 0,
					doesNotApply: 1,
					problems: 0,
					openProblems: 0,
					withheld: 0,
					notReviewed: 1,
				},
				{ practiceVersionId: "121", number: 1, reviewDigest: "sha256:bb" },
			),
		],
		withheldReasons:
			scan.counts.withheld > 0 ? [{ reason: "BUDGET", description: "budget", count: 1 }] : [],
		notReviewedReasons:
			scan.counts.notReviewed > 0
				? [{ reason: "DEADLINE", description: "deadline", count: 1 }]
				: [],
		exclusions: terminal ? [] : null,
		removalGuard: { state: "NOT_NEEDED", previousFiles: 3, wouldRemove: 0 },
		availability: {
			expired: false,
			rawRetentionEndsAt: null,
			withheldProofPairs: 0,
			expiredProofPairs: 0,
		},
		...overrides,
	};
}

export const COMPLETE_REPORT = scanReport(COMPLETE_SCAN);

export const FIRST_BASELINE_REPORT = scanReport(scanSummary({ baselineScanId: null }), {
	baselineScanId: null,
	comparison: "FIRST_SCAN",
	problemChanges: { new: 3, unchanged: 0, updated: 0, absent: 0 },
});

export const METHOD_CHANGED_REPORT = scanReport(COMPLETE_SCAN, {
	comparison: "METHOD_CHANGED",
	problemChanges: { new: 0, unchanged: 0, updated: 0, absent: 0 },
});

export const PARTIAL_REPORT = scanReport(PARTIAL_SCAN, {
	comparison: "INCOMPLETE",
	listingComplete: false,
	removalGuard: { state: "INCOMPLETE_LISTING", previousFiles: 3, wouldRemove: 0 },
});

export const SUSPECT_LISTING_REPORT = scanReport(COMPLETE_SCAN, {
	removalGuard: { state: "SUSPECT_LISTING", previousFiles: 40, wouldRemove: 12 },
});

export const WITHHELD_NO_PROBLEMS_REPORT = scanReport(
	scanSummary({
		counts: {
			...COMPLETE_COUNTS,
			reviewed: 2,
			reused: 0,
			met: 2,
			notMet: 0,
			doesNotApply: 0,
			problems: 0,
			openProblems: 0,
			withheld: 3,
			notReviewed: 1,
		},
	}),
	{
		problemChanges: { new: 0, unchanged: 0, updated: 0, absent: 0 },
		withheldReasons: [
			{ reason: "MODEL_UNAVAILABLE", description: "model unavailable", count: 2 },
			{ reason: "SOMETHING_NEW", description: "something new", count: 1 },
		],
	},
);

export const RUNNING_REPORT = scanReport(RUNNING_SCAN, {
	comparison: "INCOMPLETE",
	listingComplete: false,
	problemChanges: { new: 2, unchanged: 0, updated: 0, absent: 0 },
});

export const CANCELLED_REPORT = scanReport(CANCELLED_SCAN, {
	comparison: "INCOMPLETE",
	listingComplete: false,
	problemChanges: { new: 0, unchanged: 0, updated: 0, absent: 0 },
	practices: [],
});

export const FAILED_REPORT = scanReport(FAILED_SCAN, {
	comparison: "INCOMPLETE",
	problemChanges: { new: 0, unchanged: 0, updated: 0, absent: 0 },
	practices: [],
	removalGuard: { state: "INCOMPLETE_LISTING", previousFiles: 0, wouldRemove: 0 },
});

const entryBase = (path: string, slug = "error-handling", fileId = "901") => ({
	item: fileRef(fileId, path),
	practiceSlug: slug,
	practiceName:
		slug === "error-handling" ? "Errors are handled where they occur" : "Comments explain why",
	itemVersion: COMMIT,
});

export const JUDGED_ENTRY: ScanReportItem = {
	...entryBase("server/src/ScanService.java"),
	status: "JUDGED",
	resultId: "r-1",
	sourceReviewId: "6f1c2a7e-0d4b-4a8e-9b51-1a2b3c4d5e6f",
	outcome: "NOT_MET",
	severity: "MAJOR",
	summary: "A failed upload is logged and then ignored.",
	problemCount: 2,
	currentness: "CURRENT",
	recordedAt: hoursBefore(2),
};

export const REUSED_ENTRY: ScanReportItem = {
	...entryBase("server/src/ScanController.java", "error-handling", "902"),
	status: "REUSED",
	resultId: "r-2",
	sourceReviewId: "0b0b0b0b-1111-4222-8333-444455556666",
	outcome: "MET",
	severity: null,
	summary: null,
	problemCount: 0,
	currentness: "CURRENT",
	recordedAt: daysBefore(7),
};

export const WITHHELD_ENTRY: ScanReportItem = {
	...entryBase("webapp/src/main.tsx", "error-handling", "903"),
	status: "WITHHELD",
	reason: "MODEL_UNAVAILABLE",
};

export const NOT_REVIEWED_ENTRY: ScanReportItem = {
	...entryBase("webapp/src/main.tsx", "comment-quality", "903"),
	status: "NOT_REVIEWED",
	reason: null,

	lastResult: {
		id: "r-0",
		reviewId: "0b0b0b0b-1111-4222-8333-444455556666",
		item: fileRef("903", "webapp/src/main.tsx"),
		practiceSlug: "comment-quality",
		practiceName: "Comments explain why",
		origin: "SCHEDULE",
		outcome: "MET",
		severity: null,
		summary: "Comments say why the retry waits.",
		problems: [],
		currentness: "STALE",
		unverifiableReason: null,
		capturedAt: null,
		markedIncorrectAt: null,
		acceptedAt: null,
		recordedAt: daysBefore(14),
	},
};

export const LISTED_ENTRY: ScanReportItem = {
	...entryBase("server/src/ScanReport.java", "comment-quality", "904"),
	status: "LISTED",
	reason: null,

	lastResult: null,
};

export const REMOVED_ENTRY: ScanReportItem = {
	...JUDGED_ENTRY,
	item: fileRef("905", "server/src/LegacyExporter.java", daysBefore(1)),
	outcome: "MET",
	severity: null,
	summary: null,
	problemCount: 0,
	resultId: "r-5",
};

export const ITEM_ENTRIES: ScanReportItem[] = [
	JUDGED_ENTRY,
	REUSED_ENTRY,
	WITHHELD_ENTRY,
	NOT_REVIEWED_ENTRY,
	REMOVED_ENTRY,
];

export function problemEntry(
	id: string,
	summary: string,
	baselineState: ScanReportProblem["baselineState"],
	overrides: Partial<ScanReportProblem> = {},
): ScanReportProblem {
	return {
		problem: {
			id,
			resultId: "r-1",
			position: 0,
			key: null,
			basis: null,
			severity: "MAJOR",
			summary,
			explanation: null,
			nextStep: null,
			citations: [
				{
					sourceKind: "scm.file",
					sourcePath: null,
					path: "server/src/ScanService.java",
					startLine: 42,
					endLine: 48,
					side: null,
					quote: null,
					commit: null,
					verification: null,
				},
			],
		},
		resultId: "r-1",
		item: fileRef("901", "server/src/ScanService.java"),
		practiceSlug: "error-handling",
		practiceName: "Errors are handled where they occur",
		baselineState,
		previousProblemId: baselineState === "NEW" ? null : `prev-${id}`,
		absentReason: null,
		...overrides,
	};
}

export const NEW_PROBLEM = problemEntry(
	"p-1",
	"A failed upload is logged and then ignored.",
	"NEW",
);
export const UNCHANGED_PROBLEM = problemEntry(
	"p-2",
	"The retry swallows the timeout.",
	"UNCHANGED",
	{
		problem: {
			...problemEntry("p-2", "", null).problem,
			severity: "MINOR",
			summary: "The retry swallows the timeout.",
		},
	},
);
export const UPDATED_PROBLEM = problemEntry(
	"p-3",
	"The export catches every exception.",
	"UPDATED",
);
export const ABSENT_PROBLEM = problemEntry(
	"p-4",
	"The parser returns null on bad input.",
	"ABSENT",
);
export const REPAIRED_PROBLEM = problemEntry(
	"p-5",
	"The upload ignores the server's refusal.",
	"ABSENT",
	{ absentReason: "REPAIRED" },
);

export const PROBLEM_ENTRIES: ScanReportProblem[] = [
	NEW_PROBLEM,
	UNCHANGED_PROBLEM,
	UPDATED_PROBLEM,
	ABSENT_PROBLEM,
	REPAIRED_PROBLEM,
];
