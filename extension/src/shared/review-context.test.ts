import { describe, expect, it } from "vitest";

import type { PracticeTraceEntry, TracedSignal } from "~/api/types.gen";
import {
	isSettling,
	reviewActivity,
	type ReadyContext,
	refreshInterval,
	SETTLING_REFRESH_MS,
	VISIBLE_REFRESH_MS,
} from "~/shared/review-context";

const NOW = Date.parse("2026-09-26T12:00:00Z");

const reviewed: PracticeTraceEntry = {
	autonomy: "AUTOMATIC",
	deliveredCount: 0,
	explanation: "Measured.",
	observationCount: 1,
	outcome: "REVIEWED",
	practiceName: "Clear description",
	practiceSlug: "clear-description",
	watches: [],
	withheldReasons: [],
	reviewId: "old",
};

function signal(overrides: Partial<TracedSignal>): TracedSignal {
	return {
		id: "s",
		discoveredVia: "MANUAL",
		displayName: "Review requested",
		occurredAt: new Date(NOW - 60_000).toISOString(),
		revision: "abc",
		signal: "manual",
		state: "TRIGGERED",
		...overrides,
	};
}

function ready(
	signals: TracedSignal[],
	practices: PracticeTraceEntry[] = [reviewed],
): ReadyContext {
	return {
		status: "ready",
		instanceHost: "heph.example.test",
		workspace: { slug: "team", displayName: "Team" },
		alternatives: [],
		work: { id: "1", kind: "scm.pull_request", label: "!1" },
		canRequestReview: true,
		canInspectReviewDetails: true,
		trace: {
			artifactId: 1,
			artifactKind: "scm.pull_request",
			title: "t",
			practices,
			signals,
		},
		links: { trace: "https://heph.example.test/w/team/reviews/scm.pull_request/1" },
		pageUrl: "https://gitlab.example.test/team/app/-/merge_requests/1",
		view: "overview",
		fetchedAt: new Date(NOW).toISOString(),
	};
}

describe("isSettling", () => {
	it("is quiet for settled work", () => {
		const context = ready([
			signal({ occurredAt: "2026-09-01T00:00:00Z", reviewId: "old", reviewState: "COMPLETED" }),
		]);
		expect(isSettling(context)).toBe(false);
		expect(refreshInterval(context)).toBe(VISIBLE_REFRESH_MS);
	});

	it("watches a review in progress on work whose practices already read Reviewed", () => {
		const context = ready([signal({ reviewId: "new", reviewState: "IN_PROGRESS" })]);
		expect(isSettling(context)).toBe(true);
		expect(refreshInterval(context)).toBe(SETTLING_REFRESH_MS);
	});

	it("watches an occurrence that is queued or deferred", () => {
		expect(isSettling(ready([signal({ state: "PENDING" })]))).toBe(true);
		expect(isSettling(ready([signal({ state: "DEFERRED" })]))).toBe(true);
	});

	it("never polls fast for a state that is not about work", () => {
		expect(isSettling({ status: "signed-out", instanceHost: "h" })).toBe(false);
	});
});

describe("reviewActivity", () => {
	it("reports a queued or running review while its practice keeps its earlier results", () => {
		const context = ready([
			signal({ id: "earlier", reviewId: "old", reviewState: "COMPLETED" }),
			signal({ id: "requested", reviewId: "new", reviewState: "IN_PROGRESS" }),
		]);
		expect(reviewActivity(context)).toBe("queued-or-running");
	});

	it("reads nothing into a trigger whose review has ended", () => {
		expect(
			reviewActivity(ready([signal({ reviewId: "new", reviewState: "COMPLETED" })])),
		).toBeUndefined();
		expect(
			reviewActivity(ready([signal({ reviewId: "new", reviewState: "FAILED" })])),
		).toBeUndefined();
	});

	it("does not turn gate waiting or a deferred occasion into a queued review", () => {
		expect(reviewActivity(ready([signal({ state: "PENDING" })]))).toBe("pending");
		expect(reviewActivity(ready([signal({ state: "DEFERRED" })]))).toBe("deferred");
		const context = ready([], [{ ...reviewed, outcome: "PENDING", reviewId: undefined }]);
		expect(reviewActivity(context)).toBe("pending");
	});

	it("says nothing about work that is not ready", () => {
		expect(reviewActivity({ status: "signed-out", instanceHost: "h" })).toBeUndefined();
	});
});
