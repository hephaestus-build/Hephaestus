import type { ArtifactTrace, ObservationDetail, ReviewedWorkRef } from "~/api/types.gen";
import type { WorkPageView } from "~/shared/work-url";

/**
 * Everything a view can be told about the tab it is looking at. One closed union, so each state has
 * its own words and none of them can be mistaken for another: work Hephaestus cannot see is not
 * "nothing happened", and nothing recorded is not "no problems".
 *
 * Dates are the wire's ISO strings — this crosses `chrome.runtime` messaging as JSON.
 */
export interface WorkspaceChoice {
	slug: string;
	displayName: string;
}

export interface ReadyContext {
	status: "ready";
	instanceHost: string;
	workspace: WorkspaceChoice;
	/** Other workspaces that know the same work; the reader may switch to one. */
	alternatives: WorkspaceChoice[];
	work: ReviewedWorkRef;
	canRequestReview: boolean;
	canInspectReviewDetails: boolean;
	/** `null` when no own review trace is available; other reviews may exist. */
	trace: ArtifactTrace | null;
	links: { trace: string; reviewDetails?: string };
	/** The page this answers for, canonical: a view shows it only while its tab shows the same. */
	pageUrl: string;
	/** Which view of the work the tab shows, from the tab's own address; a list row is its overview. */
	view: WorkPageView;
	fetchedAt: string;
}

/**
 * What a report is about. `page`: the work the tab shows. `list-row`: one row of the list the tab
 * shows, named by its canonical address — only a selector, which the worker accepts solely when the
 * tab really shows that repository's list of that kind, and which the server then resolves.
 */
export type WorkSubject = { kind: "page" } | { kind: "list-row"; url: string };

export const THE_PAGE: WorkSubject = { kind: "page" };

/**
 * The comments Hephaestus recorded posting for the reader on one work. A recorded comment may since
 * have been edited or deleted on the provider; the provider's page is where it is read.
 */
export interface WorkFeedback {
	/** One per provider comment, newest feedback first. */
	comments: WorkComment[];
	/** Older feedback exists beyond these, so the count is a floor. */
	more: boolean;
	fetchedAt: string;
}

export interface WorkComment {
	/** The summary comment on the work, or a comment on a line of its changes. */
	kind: "SUMMARY" | "INLINE";
	path?: string;
	startLine?: number;
	endLine?: number;
	/** The practices its feedback named, by name; empty when only part of it was posted. */
	practices: string[];
	/** When feedback in it was last delivered, if Hephaestus recorded that. */
	deliveredAt?: string;
	/** The comment's address on this work's pages, when the server recorded one; otherwise no link. */
	permalink?: string;
}

export type ReviewContext =
	| { status: "unsupported-page" }
	| { status: "not-configured" }
	| { status: "signed-out"; instanceHost: string }
	| { status: "consent-required"; instanceHost: string; webAppUrl: string }
	| { status: "no-workspace"; instanceHost: string; siteOrigin: string }
	| { status: "not-found"; instanceHost: string; workLabel: string }
	| {
			status: "choose-workspace";
			instanceHost: string;
			workLabel: string;
			candidates: WorkspaceChoice[];
	  }
	| ReadyContext
	| { status: "error"; message: string };

/** One of the reader's own observations on the work, as the report lists it. */
export type ObservationRow = Pick<
	ObservationDetail,
	| "id"
	| "practiceName"
	| "practiceSlug"
	| "summary"
	| "outcome"
	| "severity"
	| "claimCurrentness"
	| "observedAt"
>;

export interface ObservationPage {
	/** The first page, most severe first. */
	rows: ObservationRow[];
	/** Every observation about the reader's work here; counts over `rows` are exact only up to it. */
	total: number;
	fetchedAt: string;
}

/** How often a visible view asks again while something is moving, and otherwise. */
export const SETTLING_REFRESH_MS = 10_000;
export const VISIBLE_REFRESH_MS = 60_000;

/** The named own review's recorded decision states, never another developer's run activity. */
export type ReviewActivity = "deferred" | "pending";

export function reviewActivity(context: ReviewContext): ReviewActivity | undefined {
	if (context.status !== "ready") {
		return undefined;
	}
	const signals = context.trace?.signals ?? [];
	const practices = context.trace?.practices ?? [];
	if (signals.some((signal) => signal.state === "PENDING")) {
		return "pending";
	}
	if (signals.some((signal) => signal.state === "DEFERRED")) {
		return "deferred";
	}
	if (practices.some((entry) => entry.outcome === "PENDING")) {
		return "pending";
	}
	return undefined;
}

/** Whether the server reports anything about this work still moving, which sets how often a view asks again. */
export function isSettling(context: ReviewContext): boolean {
	return reviewActivity(context) !== undefined;
}

export function refreshInterval(context: ReviewContext): number {
	return isSettling(context) ? SETTLING_REFRESH_MS : VISIBLE_REFRESH_MS;
}
