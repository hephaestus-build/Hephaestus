import type {
	ConnectionSyncStatus,
	FeedbackModeRollup,
	IntegrationCatalogEntry,
} from "@/api/types.gen";
import {
	type DataHandlingTier,
	tierMembersPhrase,
} from "@/components/practice-vocabulary/data-handling-defs";
import type { WorkspaceSetupStatus } from "@/components/practice-vocabulary/workspace-setup-status-defs";
import {
	FEEDBACK_MODE_DESCRIPTIONS,
	FEEDBACK_MODE_LABELS,
	type FeedbackMode,
} from "@/lib/practice-feedback-mode";
import { andList } from "@/lib/text";

/** A fact whose read failed. Its row says so and never reads as set up. */
interface Unknown {
	status: "unknown";
}

export type RepositoriesFact =
	| Unknown
	| { status: "not-connected" }
	| { status: "empty" }
	| { status: "syncing"; percent?: number }
	/** Connected and never synced, with no sync running now. */
	| { status: "waiting" }
	| { status: "synced"; repositories: number };

/**
 * The repositories fact, claimed only as far as the server's sync status proves it. "Synced" needs
 * every repository synced and none failing; a successful job alone does not prove it, since it can
 * finish with warnings or predate a repository added later.
 */
export function repositoriesFact(
	catalog: readonly IntegrationCatalogEntry[] | undefined,
	scmKind: IntegrationCatalogEntry["kind"] | undefined,
	status: ConnectionSyncStatus | undefined,
): RepositoriesFact {
	if (catalog === undefined || scmKind === undefined) {
		return { status: "unknown" };
	}
	const entry = catalog.find((candidate) => candidate.kind === scmKind);
	if (entry?.connected !== true || entry.connectionId === undefined) {
		return { status: "not-connected" };
	}
	if (status === undefined) {
		return { status: "unknown" };
	}
	const { total, pending, errored } = status.resourceCounts;
	if (total > 0 && pending === 0 && errored === 0) {
		return { status: "synced", repositories: total };
	}
	if (status.activeJob?.status === "RUNNING") {
		return { status: "syncing", percent: status.backfill?.percent };
	}
	if (status.activeJob?.status === "PENDING" || status.lastSuccessfulSyncAt === undefined) {
		return { status: "waiting" };
	}
	// A sync finished and found nothing to sync. Pending or failing repositories after an earlier
	// sync cannot be judged from the counts alone.
	return total === 0 ? { status: "empty" } : { status: "unknown" };
}

export type PracticeReviewsFact =
	| Unknown
	| { status: "off" }
	| { status: "on"; automatic: boolean; onRequest: boolean };

export type PracticesFact = Unknown | { status: "known"; chosen: number };

/** The member tiers that no review model serves, from `unservedReviewTiers`. */
export type ReviewModelFact =
	| Unknown
	| { status: "known"; unserved: readonly DataHandlingTier[]; memberTiers: number };

export type FeedbackModeFact = Unknown | ({ status: "known" } & FeedbackModeRollup);

export type FirstReviewFact = Unknown | { status: "none" } | { status: "done" };

/** What decides whether members get value from the workspace, each fact read from the server. */
export interface WorkspaceSetupFacts {
	repositories: RepositoriesFact;
	practiceReviews: PracticeReviewsFact;
	practices: PracticesFact;
	reviewModel: ReviewModelFact;
	feedbackMode: FeedbackModeFact;
	firstReview: FirstReviewFact;
}

export type WorkspaceSetupRowId = keyof WorkspaceSetupFacts;

export interface WorkspaceSetupRow {
	id: WorkspaceSetupRowId;
	title: string;
	status: WorkspaceSetupStatus;
	/** The fact in a sentence or two, such as "Syncing · 40%. Work appears after the first sync." */
	summary: string;
}

const UNKNOWN_SUMMARY = "We could not read this.";

function row(
	id: WorkspaceSetupRowId,
	title: string,
	status: WorkspaceSetupStatus,
	summary: string,
): WorkspaceSetupRow {
	return { id, title, status, summary };
}

function repositoriesRow(fact: RepositoriesFact): WorkspaceSetupRow {
	const title = "Repositories";
	switch (fact.status) {
		case "unknown": {
			return row("repositories", title, "unknown", UNKNOWN_SUMMARY);
		}
		case "not-connected": {
			return row(
				"repositories",
				title,
				"to-do",
				"Not connected. Connect GitHub or GitLab so members’ work can sync.",
			);
		}
		case "empty": {
			return row("repositories", title, "to-do", "Connected, but no repository is added yet.");
		}
		case "waiting": {
			return row(
				"repositories",
				title,
				"in-progress",
				"Waiting for the first sync. Work appears after it.",
			);
		}
		case "syncing": {
			return row(
				"repositories",
				title,
				"in-progress",
				`${fact.percent === undefined ? "Syncing" : `Syncing · ${fact.percent}%`}. Work appears after the first sync.`,
			);
		}
		case "synced": {
			return row(
				"repositories",
				title,
				"done",
				`${fact.repositories} ${fact.repositories === 1 ? "repository" : "repositories"} synced.`,
			);
		}
	}
}

function practiceReviewsRow(fact: PracticeReviewsFact): WorkspaceSetupRow {
	const title = "Practice reviews";
	if (fact.status === "unknown") {
		return row("practiceReviews", title, "unknown", UNKNOWN_SUMMARY);
	}
	if (fact.status === "off") {
		return row("practiceReviews", title, "to-do", "Off. Members get no practice reviews.");
	}
	if (fact.automatic) {
		return row(
			"practiceReviews",
			title,
			"done",
			fact.onRequest
				? "On. Reviews start by themselves and on request."
				: "On. Reviews start by themselves.",
		);
	}
	return fact.onRequest
		? row(
				"practiceReviews",
				title,
				"incomplete",
				"On request only. A review starts only when someone asks for one.",
			)
		: row(
				"practiceReviews",
				title,
				"to-do",
				"On, but nothing starts a review. Turn on automatic reviews or reviews on request.",
			);
}

function practicesRow(fact: PracticesFact): WorkspaceSetupRow {
	const title = "Practices";
	if (fact.status === "unknown") {
		return row("practices", title, "unknown", UNKNOWN_SUMMARY);
	}
	return fact.chosen === 0
		? row("practices", title, "to-do", "None chosen. Choose practices from the catalog.")
		: row("practices", title, "done", `${fact.chosen} chosen.`);
}

function reviewModelRow(fact: ReviewModelFact): WorkspaceSetupRow {
	const title = "Review model";
	if (fact.status === "unknown") {
		return row("reviewModel", title, "unknown", UNKNOWN_SUMMARY);
	}
	if (fact.unserved.length === 0) {
		return row("reviewModel", title, "done", "Ready for every AI choice.");
	}
	if (fact.unserved.length >= fact.memberTiers) {
		return row("reviewModel", title, "to-do", "No review model is ready, so no review can start.");
	}
	return row(
		"reviewModel",
		title,
		"incomplete",
		`No review model for ${tierMembersPhrase(fact.unserved)}. They get no reviews.`,
	);
}

function feedbackModeRow(fact: FeedbackModeFact): WorkspaceSetupRow {
	const title = "Feedback mode";
	if (fact.status === "unknown") {
		return row("feedbackMode", title, "unknown", UNKNOWN_SUMMARY);
	}
	const counts: [FeedbackMode, number][] = [
		["APPROVE_FIRST", fact.approveFirst],
		["AUTOMATIC", fact.automatic],
		["OFF", fact.off],
	];
	const used = counts.filter(([, count]) => count > 0);
	if (used.length <= 1) {
		// With no practice yet, the workspace default is what the first practices get.
		const mode = used[0]?.[0] ?? fact.defaultMode;
		const summary = `${FEEDBACK_MODE_LABELS[mode]}. ${FEEDBACK_MODE_DESCRIPTIONS[mode]}`;
		return row("feedbackMode", title, mode === "OFF" ? "incomplete" : "done", summary);
	}
	const parts = used.map(
		([mode, count]) =>
			`${FEEDBACK_MODE_LABELS[mode].toLowerCase()} for ${count} ${count === 1 ? "practice" : "practices"}`,
	);
	const summary = andList.format(parts);
	return row(
		"feedbackMode",
		title,
		"done",
		`${summary.charAt(0).toUpperCase()}${summary.slice(1)}.`,
	);
}

function firstReviewRow(fact: FirstReviewFact): WorkspaceSetupRow {
	const title = "First review";
	switch (fact.status) {
		case "unknown": {
			return row("firstReview", title, "unknown", UNKNOWN_SUMMARY);
		}
		case "none": {
			return row("firstReview", title, "to-do", "None yet.");
		}
		case "done": {
			return row(
				"firstReview",
				title,
				"done",
				"A review has finished. Results appear on members’ Practice profiles.",
			);
		}
	}
}

/** The task list, one row for each fact, in the order an admin sets them up. */
export function workspaceSetupRows(facts: WorkspaceSetupFacts): WorkspaceSetupRow[] {
	return [
		repositoriesRow(facts.repositories),
		practiceReviewsRow(facts.practiceReviews),
		practicesRow(facts.practices),
		reviewModelRow(facts.reviewModel),
		feedbackModeRow(facts.feedbackMode),
		firstReviewRow(facts.firstReview),
	];
}

/** Whether no fact could be read at all, so the summary has nothing to show. */
export function setupUnreadable(facts: WorkspaceSetupFacts): boolean {
	return workspaceSetupRows(facts).every((setupRow) => setupRow.status === "unknown");
}
