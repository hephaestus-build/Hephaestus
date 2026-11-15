import { HttpResponse, http, type JsonBodyType } from "msw";
import type {
	Practice,
	PracticeDefinition,
	ScanCounts,
	CursorPageDtoScanReportItem,
	CursorPageDtoScanReportProblem,
	ScanDetail,
	ScanReport,
	ScanReportProblem,
	ScanSummary,
} from "@/api/types.gen";
import {
	ADA,
	COMPLETE_COUNTS,
	RUNNING_COUNTS as SHARED_RUNNING_COUNTS,
	ITEM_ENTRIES,
	repositoryRef,
	fileRef,
	scanSummary,
	scanDetail,
	scanReport,
	problemEntry,
} from "@/components/admin/practice-scans/fixtures";
import { asDate, type Wire } from "@/lib/dates";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { toWire } from "@/test/to-wire";

export const RUNNING_COUNTS: ScanCounts = {
	...SHARED_RUNNING_COUNTS,
	reviewed: 1,
	notMet: 0,
	problems: 0,
	openProblems: 0,
};
export const SCAN_ID = scanSummary().id;
export const COMMIT = scanSummary().itemVersion;
export const COUNTS: ScanCounts = { ...COMPLETE_COUNTS, openProblems: 3 };
export function wireRepository(id = "300", name = "hephaestus") {
	return toWire(repositoryRef(id, name));
}
export function wireFile(id: string, path: string, removedAt: string | null = null) {
	return toWire(fileRef(id, path, asDate(removedAt)));
}
export function wireScan(overrides: Partial<Wire<ScanSummary>> = {}): Wire<ScanSummary> {
	return {
		...toWire(
			scanSummary({
				counts: COUNTS,
				createdAt: new Date("2026-10-14T09:00:00Z"),
				startedAt: new Date("2026-10-14T09:00:05Z"),
				completedAt: new Date("2026-10-14T09:20:00Z"),
			}),
		),
		...overrides,
	};
}
function scanFromWire(scan: Wire<ScanSummary>): ScanSummary {
	return {
		...scan,
		createdAt: new Date(scan.createdAt),
		startedAt: asDate(scan.startedAt),
		completedAt: asDate(scan.completedAt),
		repository: { ...scan.repository, removedAt: asDate(scan.repository.removedAt) },
	};
}
export function wireDetail(scan: Wire<ScanSummary>): Wire<ScanDetail> {
	return toWire(scanDetail(scanFromWire(scan), { practices: [] }));
}
export function wireReport(
	scan: Wire<ScanSummary>,
	overrides: Partial<Wire<ScanReport>> = {},
): Wire<ScanReport> {
	return {
		...toWire(
			scanReport(scanFromWire(scan), {
				practices: [],
				withheldReasons: [],
				notReviewedReasons: [],
				problemChanges: { new: 1, unchanged: 1, updated: 0, absent: 2 },
			}),
		),
		...overrides,
	};
}
export type WireItemsPage = Wire<CursorPageDtoScanReportItem>;
export type WireProblemsPage = Wire<CursorPageDtoScanReportProblem>;
export type WireProblem = Wire<ScanReportProblem>;

export const ITEMS_PAGE: WireItemsPage = {
	content: toWire(ITEM_ENTRIES).map((item) =>
		item.status === "NOT_REVIEWED" ? { ...item, reason: "SOMETHING_THE_PAGE_DOES_NOT_KNOW" } : item,
	),
};

export function wireProblem(
	id: string,
	summary: string,
	baselineState: WireProblem["baselineState"],
	absentReason: "REPAIRED" | null = null,
): WireProblem {
	return toWire(problemEntry(id, summary, baselineState, { absentReason }));
}

export const PROBLEMS: WireProblem[] = [
	wireProblem("p-1", "A failed upload is logged and then ignored.", "NEW"),
	wireProblem("p-2", "The retry swallows the timeout.", "UNCHANGED"),
	wireProblem("p-4", "The parser returns null on bad input.", "ABSENT"),
	wireProblem("p-5", "The upload ignores the server refusal.", "ABSENT", "REPAIRED"),
];

function definition(
	name: string,
	scanned: boolean,
	by: "HEPHAESTUS" | "PEOPLE",
): PracticeDefinition {
	return {
		name,
		about: "AUTHOR",
		appliesWhen: null,
		criteria: "Handle the error where it occurs.",
		guidance: {
			goingWell: null,
			guide: null,
			visual: null,
			whatGoodLooksLike: null,
			whyItMatters: null,
		},
		lineNotes: false,
		reads: { contract: "scm.file@1", sources: [] },
		reviewOn: { events: [], state: {} },
		reviewedBy: { by, humanReason: null, limits: [] },
		scan: scanned
			? {
					itemKind: "scm.file",
					include: ["**/*.java"],
					exclude: [],
					context: [],
					reads: { contract: "scm.file@1", sources: [] },
				}
			: null,
		script: null,
	};
}

export function wirePractice(
	slug: string,
	name: string,
	{ scanned = true, by = "HEPHAESTUS" }: { scanned?: boolean; by?: "HEPHAESTUS" | "PEOPLE" } = {},
): Wire<Practice> {
	const practiceDefinition = definition(name, scanned, by);
	return {
		id: `p-${slug}`,
		slug,
		kind: "scm.file",
		groupSlug: null,
		position: 0,
		currentVersion: {
			id: `v-${slug}`,
			number: 1,
			name,
			definition: practiceDefinition,
			reviewDigest: `sha256:${slug}`,
			createdAt: "2026-10-01T09:00:00Z",
			createdBy: toWire(ADA),
			changed: [],
			reviewChanged: false,
		},
		feedbackMode: { effective: "OFF", override: "OFF", source: "WORKSPACE" },
		catalogOrigin: { catalogSlug: null, localEdits: [], offeredDigest: null, state: "YOURS" },
		createdAt: "2026-09-01T09:00:00Z",
		updatedAt: null,
	};
}

/** Install base handlers first so each test can prepend its own overrides. */
export function adminHandlers() {
	return [
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled: true })]),
		),
	];
}

export function scanPage(scans: Wire<ScanSummary>[]) {
	return {
		content: scans,
		page: 0,
		size: 20,
		totalElements: scans.length,
		totalPages: 1,
	};
}

export function failingUntilRecovered(body: JsonBodyType) {
	let failing = true;
	return {
		recover: () => {
			failing = false;
		},
		respond: () =>
			failing
				? HttpResponse.json({ status: 500, title: "Internal Server Error" }, { status: 500 })
				: HttpResponse.json(body),
	};
}

export function firstThen(first: () => Response, rest: () => Response) {
	let answered = 0;
	return () => {
		answered += 1;
		return answered === 1 ? first() : rest();
	};
}

export function rowOf(element: Element | undefined): HTMLElement {
	const row = element?.closest("tr");
	if (!row) {
		throw new Error("The element is not in a table row");
	}
	return row;
}

export function wireScanIn(
	status: ScanSummary["status"],
	overrides: Partial<Wire<ScanSummary>> = {},
): Wire<ScanSummary> {
	return status === "QUEUED" || status === "RUNNING"
		? wireScan({ status, completedAt: undefined, counts: RUNNING_COUNTS, ...overrides })
		: wireScan({ status, ...overrides });
}
