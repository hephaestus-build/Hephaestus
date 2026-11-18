import { describe, expect, it } from "vitest";

import type {
	MemberReviewReadiness,
	PracticeProfileOverview,
	WorkspaceOnboarding,
} from "@/api/types.gen";
import { firstRunContent } from "@/components/practice-profile/ProfileFirstRun";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import {
	LATEST_REVIEW,
	pullRequest,
	READY_REVIEW_READINESS,
} from "@/stories/practice-profile-story-mock-data";
import { minutesBefore } from "@/stories/story-clock";

import { reviewFromOverview } from "./get-started-open";
import { getStartedState } from "./get-started-rows";

const LINKS = {
	setup: <a href="/setup" />,
	startChapter: () => <button type="button" />,
	conversation: <a href="/conversation" />,
};

const RUNNING = {
	reviewId: "7a1e2a3b-0000-4000-8000-000000000102",
	at: minutesBefore(1),
	item: pullRequest(12),
	status: "RUNNING" as const,
	progress: null,
};

type Overview = Pick<PracticeProfileOverview, "latestReview" | "activeReview" | "reviewReadiness">;

const overviewOf = (
	blocker: MemberReviewReadiness["blocker"],
	{ reviewed = false, running = false } = {},
): Overview => ({
	latestReview: reviewed ? LATEST_REVIEW : null,
	activeReview: running ? RUNNING : null,
	reviewReadiness: { ...READY_REVIEW_READINESS, blocker },
});

/** Get started as the Practice profile builds it, from the onboarding read and the overview. */
function listFor(
	overview: Overview | undefined,
	onboarding: WorkspaceOnboarding = workspaceOnboarding(),
	finished: readonly string[] = [],
) {
	const state = getStartedState({
		workspaceName: "Acme",
		onboarding: { state: { status: "ready" }, data: onboarding },
		review:
			overview === undefined
				? undefined
				: {
						state: { status: "ready" },
						facts: reviewFromOverview(overview),
						// What the Practice profile passes: the first-run state's own words.
						content: firstRunContent(
							{
								workspaceName: "Acme",
								readiness: overview.reviewReadiness ?? READY_REVIEW_READINESS,
								activeReview: overview.activeReview ?? undefined,
								reviewed: false,
							},
							"GITHUB",
						),
					},
		finished,
		links: LINKS,
	});
	if (state.status !== "ready") {
		throw new Error("Get started did not settle");
	}
	return state;
}

const reviewStatus = (overview: Overview, onboarding?: WorkspaceOnboarding) =>
	listFor(overview, onboarding).setup.find((row) => row.id === "first-review")?.status;

const GITLAB = {
	available: true,
	connectionId: 3,
	displayName: "GitLab",
	linked: false,
	providerType: "GITLAB",
	required: true,
};

describe("Get started's rows", () => {
	it("marks the first review done only once a review has recorded something", () => {
		expect(reviewStatus(overviewOf(null, { reviewed: true }))).toBe("done");
		expect(reviewStatus(overviewOf(null))).toBe("to-do");
		expect(reviewStatus(overviewOf(null, { running: true }))).toBe("in-progress");
	});

	it.each([
		"NO_AI",
		"REVIEWS_OFF",
		"NO_MODEL",
		"OUTSIDE_REVIEW_SCOPE",
		"WORKSPACE_INACTIVE",
	] as const)("leaves no review step for a decision the list cannot wait for: %s", (decided) => {
		expect(reviewStatus(overviewOf(decided))).toBeUndefined();
	});

	it("keeps a review step for a review the reader can ask for", () => {
		expect(reviewStatus(overviewOf("ON_REQUEST_ONLY"))).toBe("to-do");
	});

	it("lets a review that waits for a step above it offer no second way on", () => {
		const { setup } = listFor(overviewOf("CHOICE_REQUIRED"), {
			...workspaceOnboarding(),
			aiChoiceRequired: true,
		});
		const review = setup.find((row) => row.id === "first-review");
		expect(review?.status).toBe("cannot-start");
		expect(review?.action).toBeUndefined();
		expect(setup.find((row) => row.id === "ai-choice")?.status).toBe("to-do");
	});

	it("shows one account step for each account the workspace requires and offers", () => {
		const { setup } = listFor(undefined, {
			...workspaceOnboarding(),
			links: [
				{ ...GITLAB, connectionId: 1, displayName: "GitHub", linked: true },
				{ ...GITLAB, connectionId: 2, displayName: "Slack", required: false },
				{ ...GITLAB, connectionId: 4, available: false },
			],
		});
		expect(setup.map((row) => [row.id, row.status])).toStrictEqual([["account:1", "done"]]);
	});

	it("marks a chapter finished only from this account's record, and has no tour where practices are off", () => {
		const { learn } = listFor(overviewOf(null), workspaceOnboarding(), ["your-profile"]);
		expect(learn.map((row) => [row.id, row.finished])).toStrictEqual([
			["how-it-works", false],
			["your-profile", true],
			["conversation", false],
		]);
		expect(listFor(undefined).learn.map((row) => row.id)).toStrictEqual(["conversation"]);
	});

	it("offers Ask Hephaestus only where Hephaestus can answer", () => {
		const refused = listFor(undefined, {
			...workspaceOnboarding(),
			conversationRefusal: "UNAVAILABLE",
		});
		expect(refused.learn).toStrictEqual([]);
	});
});
