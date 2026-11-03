import { OWN_REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";

import { formatRelative } from "~/components/common/format";
import type {
	ReviewActivity,
	ReviewContext,
	WorkFeedback,
	ReadyContext,
} from "~/shared/review-context";

/** What the report's one collapsed line says, and how it looks. */
export interface ReportSummary {
	text: string;
	tone: "neutral" | "progress" | "error";
	/**
	 * The one next step the line offers beside itself, when the state has one. The rest of what the
	 * reader can do is inside the opened report.
	 */
	action?: "retry" | "set-up" | "sign-in" | "finish-in-app" | "request-review";
}

export type ReportState =
	| { status: "loading" }
	| { status: "failed"; message: string }
	| ReviewContext;

export type Loadable<T> =
	| { status: "loading" }
	| { status: "error"; message: string }
	| { status: "ready"; data: T };

export interface SummaryFacts {
	state: ReportState;
	/** The reader's own feedback posted on the work. */
	feedback?: Loadable<WorkFeedback>;
	/** What the server says is happening to a review now (`reviewActivity`), if anything. */
	activity: ReviewActivity | undefined;
	/** A list row's preview only reads; it offers no request. */
	readOnly?: boolean;
}

/** Only completed own reviews with results supply a review date. */
export function reviewedAt(context: Pick<ReadyContext, "trace">): string | undefined {
	const review = context.trace?.review;
	return review !== undefined &&
		(review.status === "COMPLETE" || review.status === "PARTIAL") &&
		review.completedAt !== null
		? review.completedAt
		: undefined;
}

const COMMIT = /^[0-9a-f]{7,64}$/u;

export function reviewedVersion(context: Pick<ReadyContext, "trace">): string | undefined {
	const version = context.trace?.review.itemVersion ?? undefined;
	return reviewedAt(context) !== undefined && version !== undefined && COMMIT.test(version)
		? version.slice(0, 7)
		: undefined;
}

/** An incomplete review claims nothing about practices it could not finish. */
export function unfinishedReview(context: Pick<ReadyContext, "trace">): string | undefined {
	const status = context.trace?.review.status;
	return status === "PARTIAL" || status === "FAILED"
		? `review ${OWN_REVIEW_STATUS_DEFS[status].label.toLowerCase()}`
		: undefined;
}

// No recorded receipt does not prove that the provider has no comment.
function feedbackText(feedback: WorkFeedback): string {
	const comments = feedback.comments.length;
	if (comments === 0) {
		return "No recorded comments for you";
	}
	return comments === 1 ? "1 comment for you" : `${comments} comments for you`;
}

/**
 * The report's line for every state, for every reader: what is happening to a review, the reader's
 * own comments on this work, and when a review last recorded a result. Nothing here claims more than the
 * server said — no feedback for you is not "all good", and a review is only "running" when the server
 * says so.
 */
export function summarizeReport({
	state,
	feedback,
	activity,
	readOnly = false,
}: SummaryFacts): ReportSummary {
	switch (state.status) {
		case "loading": {
			return { text: "Loading…", tone: "neutral" };
		}
		case "failed":
		case "error": {
			return { text: "We could not load", tone: "error", action: "retry" };
		}
		case "not-configured": {
			return { text: "Connect the extension to Hephaestus", tone: "neutral", action: "set-up" };
		}
		case "server-too-old": {
			return { text: "This server is too old for this extension", tone: "error" };
		}
		case "signed-out": {
			return {
				text: `Sign in to ${state.instanceHost} to see it`,
				tone: "neutral",
				action: "sign-in",
			};
		}
		case "consent-required": {
			return { text: "One step left in Hephaestus", tone: "neutral", action: "finish-in-app" };
		}
		case "unsupported-page":
		case "no-workspace":
		case "not-found": {
			return { text: "Not available for this work", tone: "neutral" };
		}
		case "choose-workspace": {
			return {
				// A list row cannot choose; the work's own page can.
				text: readOnly
					? `Followed in ${state.candidates.length} workspaces · open it to choose one`
					: `Followed in ${state.candidates.length} workspaces · choose one`,
				tone: "neutral",
			};
		}
		case "ready": {
			break;
		}
	}
	const request = state.requestAllowed && !readOnly ? ("request-review" as const) : undefined;
	const parts: string[] = [];
	let tone: ReportSummary["tone"] = "neutral";
	if (activity !== undefined) {
		parts.push(`Review ${OWN_REVIEW_STATUS_DEFS[activity].label.toLowerCase()}`);
	}
	if (feedback?.status === "error") {
		parts.push("We could not load your comments");
		tone = "error";
	} else if (feedback?.status === "ready") {
		parts.push(feedbackText(feedback.data));
	} else if (parts.length === 0) {
		parts.push("Loading…");
	}
	const reviewed = reviewedAt(state);
	const relative = reviewed === undefined ? undefined : formatRelative(reviewed, state.fetchedAt);
	if (relative !== undefined) {
		parts.push(`reviewed ${relative}`);
	} else if (state.trace === null && activity === undefined) {
		parts.push("review status unavailable");
	}
	const unfinished = unfinishedReview(state);
	if (unfinished !== undefined) {
		parts.push(unfinished);
	}
	return { text: parts.join(" · "), tone, action: request };
}
