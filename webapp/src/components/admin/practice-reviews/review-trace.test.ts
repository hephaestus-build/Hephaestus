import { assert, describe, expect, it } from "vitest";

import type { ReviewBoundFeedback } from "@/api/types.gen";

import { branchOutcome, lineageRecords, silentNotMet, stageCounts } from "./lineage-chain";
import {
	EARLIER_REVIEW_ID,
	LINEAGE_FEEDBACK,
	LINEAGE_RESULT,
	LINEAGE_REVIEW_ID,
	lineageFeedbackFocus,
	lineageProblemFocus,
	lineageResultFocus,
	wholeReview,
} from "./lineage-fixtures";
import { lineageStateCase, richReviewFixture } from "./lineage-state-fixtures";
import { reviewStepState } from "./lineage-story";
import { chainReview, ownResults, tracedBy } from "./review-trace";

const whole = lineageRecords(wholeReview);
const stateRecords = (key: string) => lineageRecords(lineageStateCase(key).lineage);

describe("the review a chain tells", () => {
	it("is the review that recorded the record the graph was asked for", () => {
		for (const lineage of [
			wholeReview,
			lineageResultFocus,
			lineageProblemFocus,
			lineageFeedbackFocus,
		]) {
			expect(chainReview(lineageRecords(lineage))?.id).toBe(LINEAGE_REVIEW_ID);
		}
	});

	it("is none once the review's record is gone", () => {
		expect(chainReview(stateRecords("record-gone"))).toBeUndefined();
	});

	it("leaves out a result an earlier review recorded", () => {
		const records = stateRecords("answered-earlier");
		const earlier = records.results.filter((result) => result.reviewId === EARLIER_REVIEW_ID);
		expect(earlier.length).toBeGreaterThan(0);
		expect(ownResults(records)).toHaveLength(records.results.length - earlier.length);
	});
});

describe("the path a pinned node draws strong", () => {
	it("keeps the rich review detail and trace on the same result and problem basis", () => {
		const records = lineageRecords(richReviewFixture.lineage);
		expect(records.results.map((result) => result.id)).toStrictEqual(
			richReviewFixture.results.map((result) => result.id),
		);
		for (const detail of richReviewFixture.feedback) {
			const linked = records.feedback.find((entry) => entry.id === detail.id);
			assert(linked !== undefined);
			expect(linked.basis).toStrictEqual([...new Set(detail.basis.map((basis) => basis.resultId))]);
			const problemBasis = detail.basis.filter((basis) => basis.problemId !== null);
			expect(linked.problemBasis).toStrictEqual(problemBasis.map((basis) => basis.problemId));
			for (const basis of detail.basis) {
				expect(richReviewFixture.resultsById.get(basis.resultId)?.id).toBe(basis.result.id);
			}
			for (const basis of problemBasis) {
				assert(basis.problemId !== null);
				expect(richReviewFixture.problemsById.get(basis.problemId)?.resultId).toBe(basis.resultId);
			}
		}
		const { runtime } = richReviewFixture.review;
		assert(runtime !== null);
		const [run] = runtime.jobs;
		assert(run !== undefined);
		assert(run.startedAt !== null);
		assert(run.completedAt !== null);
		for (const result of richReviewFixture.results) {
			expect(result.recordedAt.getTime()).toBeGreaterThanOrEqual(run.startedAt.getTime());
			expect(result.recordedAt.getTime()).toBeLessThanOrEqual(run.completedAt.getTime());
		}
	});

	it("keeps a problem-specific basis separate from its owning result and sibling problems", () => {
		const problem = wholeReview.nodes.find((node) => node.problem !== null);
		assert(problem !== undefined);
		assert(problem.problem !== null);
		const linked = lineageRecords({
			...wholeReview,
			edges: [
				...wholeReview.edges,
				{ from: problem.id, to: `feedback:${LINEAGE_FEEDBACK.onGitHub}`, relation: "BASIS" },
				{ from: "problem:absent", to: `feedback:${LINEAGE_FEEDBACK.onGitHub}`, relation: "BASIS" },
			],
		});
		expect(
			linked.feedback.find((entry) => entry.id === LINEAGE_FEEDBACK.onGitHub)?.problemBasis,
		).toStrictEqual([problem.problem.id]);
		expect(
			linked.feedback.find((entry) => entry.id === LINEAGE_FEEDBACK.praise)?.problemBasis,
		).toStrictEqual([]);
		expect(
			linked.feedback.find((entry) => entry.id === LINEAGE_FEEDBACK.onGitHub)?.basis,
		).toStrictEqual(whole.feedback.find((entry) => entry.id === LINEAGE_FEEDBACK.onGitHub)?.basis);
	});

	it("runs from a result to the feedback written from it", () => {
		const traced = tracedBy(whole, { kind: "result", id: LINEAGE_RESULT.cache404 });
		expect(traced?.results).toStrictEqual(new Set([LINEAGE_RESULT.cache404]));
		expect(traced?.feedback.has(LINEAGE_FEEDBACK.onGitHub)).toBe(true);
		expect(traced?.feedback.has(LINEAGE_FEEDBACK.withdrawn)).toBe(true);
		expect(traced?.feedback.has(LINEAGE_FEEDBACK.praise)).toBe(false);
	});

	it("runs from a piece of feedback to every result it rests on", () => {
		expect(tracedBy(whole, { kind: "feedback", id: LINEAGE_FEEDBACK.onGitHub })).toStrictEqual({
			results: new Set([LINEAGE_RESULT.cache404, LINEAGE_RESULT.testNames]),
			feedback: new Set([LINEAGE_FEEDBACK.onGitHub]),
		});
	});

	it("leaves out what another review recorded when the review is pinned", () => {
		const records = stateRecords("answered-earlier");
		const traced = tracedBy(records, { kind: "review" });
		for (const result of records.results) {
			expect(traced?.results.has(result.id)).toBe(result.reviewId !== EARLIER_REVIEW_ID);
		}
	});

	it("dims nothing for the work", () => {
		expect(tracedBy(whole, { kind: "work" })).toBeUndefined();
	});
});

function stepOf(key: string) {
	const entry = lineageStateCase(key);
	const review = chainReview(lineageRecords(entry.lineage));
	assert(review !== undefined);
	return reviewStepState(review, entry.review);
}

describe("the review's step", () => {
	it("tells a withheld practice by its recorded reason, never as a review that saw nothing", () => {
		const step = stepOf("withheld-practice");
		expect(step.title).toBe("Review complete");
		expect(step.assessedNothing).toMatch(/^2 practices were withheld with a recorded reason/u);
		expect(step.assessedNothing).not.toMatch(/evidence/u);
	});

	it("says why a failed review stopped", () => {
		const failed = stepOf("failed");
		expect(failed.title).toBe("Review failed");
		expect(failed.note).toBe("Running the review failed.");
		expect(stepOf("timed-out").note).toBe("It ran past the time it is allowed and was stopped.");
	});

	it("gives an empty complete review, a failed one and a cancelled one distinct labels", () => {
		const labels = ["empty", "failed", "cancelled"].map((key) => stepOf(key).def.label);
		expect(new Set(labels).size).toBe(labels.length);
		expect(stepOf("empty").assessedNothing).toBe(
			"The review ran to the end and recorded no observation.",
		);
	});
});

const piece = (overrides: Partial<ReviewBoundFeedback>): ReviewBoundFeedback => ({
	id: "5e5e0009-0000-4000-8000-000000000001",
	destination: "ON_WORK",
	state: "DELIVERED",
	deliveredAt: new Date("2026-07-28T13:41:30Z"),
	withdrawn: false,
	...overrides,
});

describe("what became of a piece of feedback", () => {
	it("names an admin's withdrawal before the stored state", () => {
		expect(branchOutcome(piece({ withdrawn: true }), undefined)).toMatchObject({
			text: "Withdrawn by an admin",
			kind: "withheld",
		});
		expect(branchOutcome(piece({ state: "REJECTED" }), undefined).text).toBe(
			"Rejected by an admin",
		);
	});

	it("says a withdrawn piece was delivered only where a delivery is recorded", () => {
		expect(branchOutcome(piece({ withdrawn: true }), undefined).detail).toMatch(
			/^Delivered .+, then taken off them\.$/u,
		);
		expect(
			branchOutcome(piece({ withdrawn: true, state: "READY", deliveredAt: null }), undefined)
				.detail,
		).toBe("Taken back before it reached the developer.");
	});

	it("says a piece was delivered before it was withheld", () => {
		expect(branchOutcome(piece({ state: "WITHHELD" }), undefined).text).toBe(
			"Delivered, later withheld",
		);
	});

	it("tells a replaced piece apart from a lost one", () => {
		expect(branchOutcome(piece({ state: "REPLACED" }), undefined).kind).toBe("replaced");
	});
});

describe("what the chain lost", () => {
	it("counts a not-met result with no feedback, and nothing met", () => {
		expect(silentNotMet(stateRecords("not-met-silent"))).toHaveLength(1);
	});

	it("counts every piece the graph holds and those that reached the developer", () => {
		const counts = stageCounts(whole);
		expect(counts.results).toBe(whole.results.length);
		expect(counts.feedback).toBe(whole.feedback.length);
		expect(counts.reached).toBeLessThan(counts.feedback);
	});
});
