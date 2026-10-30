import { describe, expect, it } from "vitest";

import type { ReviewRef } from "~/api/types.gen";
import {
	isSettling,
	reviewActivity,
	type ReadyContext,
	refreshInterval,
	SETTLING_REFRESH_MS,
	VISIBLE_REFRESH_MS,
} from "~/shared/review-context";

const ITEM = {
	id: "1",
	kind: "scm.pull_request",
	parentId: null,
	provider: "GITLAB",
	label: "!1",
	title: "t",
	url: null,
	container: null,
	path: null,
	removedAt: null,
} as const;

function ready(status: ReviewRef["status"] | undefined): ReadyContext {
	return {
		status: "ready",
		instanceHost: "hephaestus.example.test",
		workspace: { slug: "team", displayName: "Team" },
		alternatives: [],
		item: ITEM,
		requestAllowed: true,
		canInspectReviewDetails: true,
		trace:
			status === undefined
				? null
				: {
						item: ITEM,
						review: {
							id: "r",
							kind: "ITEM",
							item: ITEM,
							itemVersion: "abc1234",
							origin: "REQUEST",
							status,
							createdAt: "2026-09-26T11:59:00Z",
							completedAt: null,
						},
						events: [],
						results: [],
					},
		links: { trace: "https://hephaestus.example.test/w/team/feedback/1" },
		pageUrl: "https://gitlab.example.test/team/app/-/merge_requests/1",
		view: "overview",
		fetchedAt: "2026-09-26T12:00:00Z",
	};
}

describe("isSettling", () => {
	it("is quiet for a finished review or no own review", () => {
		for (const status of ["COMPLETE", "PARTIAL", "FAILED", "CANCELLED", undefined] as const) {
			expect(isSettling(ready(status))).toBe(false);
			expect(refreshInterval(ready(status))).toBe(VISIBLE_REFRESH_MS);
		}
	});

	it("watches the reader's own review while it is queued or running", () => {
		expect(refreshInterval(ready("QUEUED"))).toBe(SETTLING_REFRESH_MS);
		expect(refreshInterval(ready("RUNNING"))).toBe(SETTLING_REFRESH_MS);
	});

	it("never polls fast for a state that is not about work", () => {
		expect(isSettling({ status: "signed-out", instanceHost: "h" })).toBe(false);
	});
});

describe("reviewActivity", () => {
	it("is the own review's status while it moves, and nothing after", () => {
		expect(reviewActivity(ready("QUEUED"))).toBe("QUEUED");
		expect(reviewActivity(ready("RUNNING"))).toBe("RUNNING");
		expect(reviewActivity(ready("COMPLETE"))).toBeUndefined();
		expect(reviewActivity(ready(undefined))).toBeUndefined();
	});

	it("says nothing about work that is not ready", () => {
		expect(reviewActivity({ status: "signed-out", instanceHost: "h" })).toBeUndefined();
	});
});
