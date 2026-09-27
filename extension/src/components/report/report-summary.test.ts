import { describe, expect, it } from "vitest";

import type { PracticeTraceEntry } from "~/api/types.gen";
import { blocksRequest, reviewedAt, summarizeReport } from "~/components/report/report-summary";
import type { ReadyContext, WorkFeedback } from "~/shared/review-context";

const NOW = "2026-09-26T12:00:00Z";

function practice(overrides: Partial<PracticeTraceEntry>): PracticeTraceEntry {
	return {
		autonomy: "AUTOMATIC",
		deliveredCount: 0,
		explanation: "",
		observationCount: 0,
		outcome: "REVIEWED",
		practiceName: "Clear work",
		practiceSlug: "clear-work",
		watches: [],
		withheldReasons: [],
		...overrides,
	};
}

const ready: ReadyContext = {
	status: "ready",
	instanceHost: "heph.test",
	workspace: { slug: "team", displayName: "Team" },
	alternatives: [],
	work: { id: "1", kind: "scm.issue", label: "#1" },
	canRequestReview: true,
	canInspectReviewDetails: true,
	trace: {
		artifactId: 1,
		artifactKind: "scm.issue",
		title: "Issue",
		signals: [],
		practices: [
			practice({ decidedAt: "2026-09-26T09:00:00Z" }),
			practice({ decidedAt: "2026-09-26T11:00:00Z" }),
			// A newer decision that did not review says nothing about when the work was reviewed.
			practice({ outcome: "SKIPPED", decidedAt: "2026-09-26T11:55:00Z" }),
		],
	},
	links: { trace: "https://heph.test/work" },
	pageUrl: "https://github.com/org/repo/issues/1",
	view: "overview",
	fetchedAt: NOW,
};

function feedback(comments: number, more = false): WorkFeedback {
	return {
		comments: Array.from({ length: comments }, () => ({
			kind: "INLINE" as const,
			practices: ["Clear work"],
		})),
		more,
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

	it("says a count is a floor when older feedback is not in the answer", () => {
		expect(summarize(feedback(1, true)).text).toBe(
			"At least 1 comment for you · reviewed 1 hr. ago",
		);
	});

	it("says no comment is recorded for the reader, and nothing more than that", () => {
		const summary = summarize(feedback(0));
		expect(summary.text).toBe("No recorded comments for you · reviewed 1 hr. ago");
		expect(summary.text).not.toMatch(/clean|all good|no problems/iu);
	});

	it("names a missing trace as no review recorded, and only then", () => {
		expect(summarize(feedback(0), { trace: null }).text).toBe(
			"No recorded comments for you · no review recorded",
		);
		expect(
			summarize(feedback(0), {
				trace: {
					artifactId: 1,
					artifactKind: "scm.issue",
					title: "Issue",
					signals: [],
					practices: [],
				},
			}).text,
		).toBe("No recorded comments for you");
	});

	it("puts authoritative activity first and blocks a request only while a review is active", () => {
		const running = summarizeReport({
			state: ready,
			activity: "queued-or-running",
			feedback: { status: "ready", data: feedback(1) },
		});
		expect(running).toMatchObject({
			text: "Review queued or running · 1 comment for you · reviewed 1 hr. ago",
			tone: "progress",
		});
		expect(running.action).toBeUndefined();
		for (const activity of ["pending", "deferred"] as const) {
			expect(blocksRequest(activity)).toBe(false);
			expect(
				summarizeReport({
					state: ready,
					activity,
					feedback: { status: "ready", data: feedback(0) },
				}).action,
			).toBe("request-review");
		}
	});

	it("says the reader's feedback could not load, as an error", () => {
		expect(
			summarizeReport({
				state: ready,
				activity: undefined,
				feedback: { status: "error", message: "failed" },
			}),
		).toMatchObject({ text: "Your feedback could not load · reviewed 1 hr. ago", tone: "error" });
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

	it("names a review that could not finish for a practice on the line itself", () => {
		const failed = practice({ practiceSlug: "tests", outcome: "FAILED", decidedAt: undefined });
		const trace = { artifactId: 1, artifactKind: "scm.issue", title: "Issue", signals: [] };
		expect(
			summarize(feedback(1), {
				trace: { ...trace, practices: [practice({ decidedAt: "2026-09-26T11:00:00Z" }), failed] },
			}).text,
		).toBe("1 comment for you · reviewed 1 hr. ago · 1 practice incomplete");
		expect(summarize(feedback(1), { trace: { ...trace, practices: [failed, failed] } }).text).toBe(
			"1 comment for you · 2 practices incomplete",
		);
	});

	it("takes the time a review answered, not the newest decision of any kind", () => {
		expect(reviewedAt(ready)).toBe("2026-09-26T11:00:00Z");
		expect(reviewedAt({ trace: null })).toBeUndefined();
	});
});
