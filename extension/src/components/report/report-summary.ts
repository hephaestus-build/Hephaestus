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

const ACTIVITY_TEXT: Record<ReviewActivity, string> = {
	"queued-or-running": "Review queued or running",
	pending: "Review decision pending",
	deferred: "Review decision deferred",
};

/** A request is refused while a review of this work is queued or running. */
export function blocksRequest(activity: ReviewActivity | undefined): boolean {
	return activity === "queued-or-running";
}

/**
 * When a review last recorded a result for this work: the newest time a practice's review decided,
 * among practices it reviewed — not when an event on the work was noticed.
 */
export function reviewedAt(context: Pick<ReadyContext, "trace">): string | undefined {
	let latest: string | undefined;
	for (const entry of context.trace?.practices ?? []) {
		if (
			entry.outcome === "REVIEWED" &&
			entry.decidedAt !== undefined &&
			(latest === undefined || Date.parse(entry.decidedAt) > Date.parse(latest))
		) {
			latest = entry.decidedAt;
		}
	}
	return latest;
}

/**
 * How many provider comments Hephaestus recorded posting for the reader. Empty says only that none is
 * recorded, not that the provider shows none.
 */
const COMMIT = /^[0-9a-f]{7,64}$/u;

/**
 * The commit the newest reviewed result looked at, shortened: the occasion that started that review,
 * when the trace ties the two together and names a commit. Otherwise nothing.
 */
export function reviewedRevision(context: Pick<ReadyContext, "trace">): string | undefined {
	const reviewed = reviewedAt(context);
	const { trace } = context;
	if (reviewed === undefined || trace === null) {
		return undefined;
	}
	const reviewId = trace.practices.find(
		(entry) => entry.outcome === "REVIEWED" && entry.decidedAt === reviewed,
	)?.reviewId;
	const revision = trace.signals.find(
		(signal) => signal.reviewId !== undefined && signal.reviewId === reviewId,
	)?.revision;
	return revision !== undefined && COMMIT.test(revision) ? revision.slice(0, 7) : undefined;
}

/** How many practices a review could not finish, which the report names and claims nothing more of. */
export function incompletePractices(context: Pick<ReadyContext, "trace">): number {
	return (context.trace?.practices ?? []).filter((entry) => entry.outcome === "FAILED").length;
}

function feedbackText(feedback: WorkFeedback): string {
	const comments = feedback.comments.length;
	if (comments === 0 && !feedback.more) {
		return "No recorded comments for you";
	}
	const counted = comments === 1 ? "1 comment" : `${comments} comments`;
	return feedback.more ? `At least ${counted} for you` : `${counted} for you`;
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
			return { text: "Could not load", tone: "error", action: "retry" };
		}
		case "not-configured": {
			return { text: "Connect the extension to Hephaestus", tone: "neutral", action: "set-up" };
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
					? `Followed in ${state.candidates.length} workspaces; open it to choose one`
					: `Followed in ${state.candidates.length} workspaces; choose one`,
				tone: "neutral",
			};
		}
		case "ready": {
			break;
		}
	}
	const request =
		state.canRequestReview && !readOnly && !blocksRequest(activity)
			? ("request-review" as const)
			: undefined;
	const parts: string[] = [];
	let tone: ReportSummary["tone"] = "neutral";
	if (activity !== undefined) {
		parts.push(ACTIVITY_TEXT[activity]);
		if (blocksRequest(activity)) {
			tone = "progress";
		}
	}
	if (feedback?.status === "error") {
		parts.push("Your feedback could not load");
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
		parts.push("no review recorded");
	}
	// A review that could not finish for a practice is said on the line, not only inside.
	const incomplete = incompletePractices(state);
	if (incomplete > 0) {
		parts.push(incomplete === 1 ? "1 practice incomplete" : `${incomplete} practices incomplete`);
	}
	return { text: parts.join(" · "), tone, action: request };
}
