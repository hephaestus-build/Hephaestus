import { describe, expect, it } from "vitest";

import type { OwnItemTrace } from "~/api/types.gen";
import { reviewedAt, reviewedVersion, summarizeReport } from "~/components/report/report-summary";
import type { ReadyContext, WorkFeedback } from "~/shared/review-context";

const NOW = "2026-09-26T12:00:00Z";

const ITEM = {
	id: "1",
	kind: "scm.issue",
	parentId: null,
	provider: "GITHUB",
	label: "#1",
	title: "Issue",
	url: null,
	container: null,
	path: null,
	removedAt: null,
} as const;

function trace(review: Partial<OwnItemTrace["review"]>): OwnItemTrace {
	return {
		item: ITEM,
		review: {
			id: "r",
			kind: "ITEM",
			item: ITEM,
			itemVersion: "4f2a9c1e8b7d",
			origin: "EVENT",
			status: "COMPLETE",
			createdAt: "2026-09-26T10:50:00Z",
			completedAt: "2026-09-26T11:00:00Z",
			...review,
		},
		events: [],
		results: [],
	};
}

const ready: ReadyContext = {
	status: "ready",
	instanceHost: "hephaestus.test",
	workspace: { slug: "team", displayName: "Team" },
	alternatives: [],
	item: ITEM,
	requestAllowed: true,
	canInspectReviewDetails: true,
	trace: trace({}),
	links: { trace: "https://hephaestus.test/w/team/feedback/1" },
	pageUrl: "https://github.com/org/repo/issues/1",
	view: "overview",
	fetchedAt: NOW,
};

function feedback(comments: number): WorkFeedback {
	return {
		comments: Array.from({ length: comments }, () => ({
			kind: "LINE_NOTE" as const,
			practices: ["Clear work"],
			postedAt: NOW,
		})),
		fetchedAt: NOW,
	};
}

const summarize = (data: WorkFeedback, overrides: Partial<ReadyContext> = {}) =>
	summarizeReport({
		state: { ...ready, ...overrides },
		activity: undefined,
		feedback: { status: "ready", data },
	});

describe("report summary", () => {
	it("leads with the reader's own comments and when a review answered", () => {
		expect(summarize(feedback(3))).toStrictEqual({
			text: "3 comments for you · reviewed 1 hr. ago",
			tone: "neutral",
			action: "request-review",
		});
		expect(summarize(feedback(1)).text).toBe("1 comment for you · reviewed 1 hr. ago");
	});

	it("says no comment is recorded for the reader, and nothing more than that", () => {
		const summary = summarize(feedback(0));
		expect(summary.text).toBe("No recorded comments for you · reviewed 1 hr. ago");
		expect(summary.text).not.toMatch(/clean|all good|no problems/iu);
	});

	it("does not treat an unavailable own trace as never reviewed", () => {
		expect(summarize(feedback(0), { trace: null }).text).toBe(
			"No recorded comments for you · review status unavailable",
		);
		expect(
			summarize(feedback(0), { trace: trace({ status: "CANCELLED", completedAt: null }) }).text,
		).toBe("No recorded comments for you");
	});

	it("says the own review is moving and still offers a request", () => {
		expect(
			summarizeReport({
				state: { ...ready, trace: trace({ status: "RUNNING", completedAt: null }) },
				activity: "RUNNING",
				feedback: { status: "ready", data: feedback(0) },
			}),
		).toStrictEqual({
			text: "Review running · No recorded comments for you",
			tone: "neutral",
			action: "request-review",
		});
	});

	it("offers no request the server does not allow", () => {
		expect(summarize(feedback(0), { requestAllowed: false }).action).toBeUndefined();
	});

	it("says the reader's feedback could not load, as an error", () => {
		expect(
			summarizeReport({
				state: ready,
				activity: undefined,
				feedback: { status: "error", message: "failed" },
			}),
		).toMatchObject({
			text: "We could not load your comments · reviewed 1 hr. ago",
			tone: "error",
		});
	});

	it("offers no request from a list row's preview", () => {
		expect(
			summarizeReport({
				state: ready,
				activity: undefined,
				feedback: { status: "ready", data: feedback(0) },
				readOnly: true,
			}).action,
		).toBeUndefined();
	});

	// A partial review may have run to the end with a practice failed or withheld, so the line names
	// its status and not a stop; a failed review still says it failed.
	it("names a partial review on the line without saying it stopped, and a failed one as failed", () => {
		expect(summarize(feedback(1), { trace: trace({ status: "PARTIAL" }) }).text).toBe(
			"1 comment for you · reviewed 1 hr. ago · review partial",
		);
		expect(
			summarize(feedback(1), { trace: trace({ status: "FAILED", completedAt: null }) }).text,
		).toBe("1 comment for you · review failed");
	});

	it("dates only a review that finished with results, and names its commit", () => {
		expect(reviewedAt(ready)).toBe("2026-09-26T11:00:00Z");
		expect(reviewedVersion(ready)).toBe("4f2a9c1");
		expect(reviewedAt({ trace: trace({ status: "FAILED" }) })).toBeUndefined();
		expect(reviewedVersion({ trace: trace({ itemVersion: "v2" }) })).toBeUndefined();
		expect(reviewedAt({ trace: null })).toBeUndefined();
	});
});
