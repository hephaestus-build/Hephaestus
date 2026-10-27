import { assert, describe, expect, it } from "vitest";

import type { ItemEvent, ReviewLineage } from "@/api/types.gen";

import { lineageRecords } from "./lineage-chain";
import { lineageItemFocus, storyLineage } from "./lineage-fixtures";
import {
	LINEAGE_STATE_CASES,
	lineageStateCase,
	STATE_FEEDBACK_ID,
	STATE_RESULT_ID,
} from "./lineage-state-fixtures";
import type { TraceNode } from "./review-trace";
import { traceVerdict, unreviewedVerdict } from "./trace-verdict";

const REVIEW: TraceNode = { kind: "review" };
const feedbackNode = (id: string): TraceNode => ({ kind: "feedback", id });
const resultNode = (id: string): TraceNode => ({ kind: "result", id });

function verdictOf(key: string, node: TraceNode = REVIEW) {
	return traceVerdict(lineageStateCase(key).lineage, node);
}

function nodesOf(lineage: ReviewLineage): TraceNode[] {
	const { results, feedback } = lineageRecords(lineage);
	return [
		REVIEW,
		{ kind: "work" },
		...results.map((entry) => resultNode(entry.id)),
		...feedback.map((entry) => feedbackNode(entry.id)),
	];
}

describe("traceVerdict on every state", () => {
	const cases = Object.entries(LINEAGE_STATE_CASES).flatMap(([group, entries]) =>
		entries.map((entry) => [group, entry.key, entry] as const),
	);

	it.each(cases)("%s / %s says one sentence and ends the path", (_group, _key, entry) => {
		for (const node of nodesOf(entry.lineage)) {
			const { sentence, end } = traceVerdict(entry.lineage, node);
			expect(sentence).toMatch(/^[A-Z].*\.$/u);
			// One sentence: no full stop before the last one.
			expect(sentence.slice(0, -1)).not.toMatch(/\.\s/u);
			expect(end.reason.length).toBeGreaterThan(0);
		}
	});

	it("says the same for the same graph, with no clock and no model", () => {
		const { lineage } = lineageStateCase("delivery-states");
		expect(traceVerdict(lineage, REVIEW)).toStrictEqual(traceVerdict(lineage, REVIEW));
	});
});

describe("traceVerdict on a review", () => {
	it("says where a review waits or runs", () => {
		expect(verdictOf("queued").sentence).toBe(
			"This review waits in the queue and has recorded nothing yet.",
		);
		expect(verdictOf("running")).toMatchObject({
			sentence: "This review remains open and has recorded 2 observations so far.",
			end: { kind: "open", label: "Open" },
		});
	});

	it("says how long a review took and how much of its feedback reached the developer", () => {
		expect(verdictOf("complete")).toMatchObject({
			sentence:
				"This review completed after 9 min and composed 1 piece of feedback; it reached the developer.",
			end: { kind: "delivered", reason: "it reached the developer on GitHub" },
		});
		expect(verdictOf("many-feedback").sentence).toBe(
			"This review completed after 9 min and composed 10 pieces of feedback; all 10 reached the developer.",
		);
	});

	it("says a stopped review ended before it composed feedback", () => {
		expect(verdictOf("failed").sentence).toBe(
			"This review failed after 9 min and recorded 1 observation, but composed no feedback.",
		);
		expect(verdictOf("cancelled")).toMatchObject({
			sentence: "This review was cancelled after 1 min with nothing recorded.",
			end: { kind: "lost" },
		});
	});

	it("says a partial review closed as partial, not that it stopped or what it left out", () => {
		expect(verdictOf("scan-many-jobs").sentence).toMatch(
			/^This review closed as partial after .+ and recorded \d+ observations, but composed no feedback\.$/u,
		);
	});

	it("says a review with no result recorded none", () => {
		expect(verdictOf("empty")).toMatchObject({
			sentence: "This review completed after 9 min and recorded no observation.",
			end: { kind: "none" },
		});
	});

	it("counts only the review's own results, not an earlier review's", () => {
		expect(verdictOf("answered-earlier").sentence).toBe(
			"This review completed after 9 min and composed 1 piece of feedback; it reached the developer.",
		);
	});

	it("says it cannot tell when the record of the review is gone", () => {
		expect(verdictOf("record-gone").end.label).toBe("Not known");
	});

	it("never calls the reviews of work with several of them gone", () => {
		const { item, reviews } = lineageRecords(lineageItemFocus);
		const [review] = reviews;
		assert(item !== undefined);
		assert(review !== undefined);
		const lineage = storyLineage({
			root: { kind: "ITEM", id: item.id },
			item,
			reviews: [review, { ...review, id: "11111111-1111-1111-1111-222222222222" }],
			results: [],
			feedback: [],
		});
		expect(traceVerdict(lineage, { kind: "work" })).toMatchObject({
			sentence: "This work has 2 reviews, so open one to see what it did.",
			end: { kind: "none", label: "Several reviews" },
		});
	});
});

describe("traceVerdict on a result", () => {
	it("says a not met result with no feedback left the developer untold", () => {
		expect(verdictOf("not-met-silent", resultNode(STATE_RESULT_ID))).toMatchObject({
			sentence:
				"The practice was not met, but no feedback was written, so the developer was not told.",
			end: { kind: "lost" },
		});
	});

	it("counts many different ends by kind, with the best one naming the path", () => {
		expect(verdictOf("delivery-states", resultNode(STATE_RESULT_ID))).toMatchObject({
			sentence:
				"This observation led to 8 pieces of feedback; one was delivered, two are still open, four did not arrive, and one has no known end.",
			end: { kind: "delivered" },
		});
	});

	it("says a met result needed no feedback", () => {
		const { results } = lineageRecords(lineageStateCase("not-met-silent").lineage);
		const met = results.find((entry) => entry.outcome === "MET");
		assert(met !== undefined);
		expect(verdictOf("not-met-silent", resultNode(met.id)).end).toMatchObject({
			kind: "none",
			reason: "it was met, so nothing had to be sent",
		});
	});
});

describe("traceVerdict on a piece of feedback", () => {
	const piece = feedbackNode(STATE_FEEDBACK_ID);

	it("names where a delivered piece reached the developer", () => {
		expect(verdictOf("destination-on-work", piece).sentence).toBe(
			"This feedback reached the developer on GitHub.",
		);
		expect(verdictOf("destination-conversation", piece).sentence).toBe(
			"This feedback reached the developer in conversation.",
		);
	});

	it.each([
		["destination-on-work", "it is ready to be posted on the work"],
		["destination-practice-page", "it is ready for their practice pages"],
		["destination-conversation", "it is ready for their next conversation with Hephaestus"],
	] as const)("names where a ready piece goes in %s", (key, sentence) => {
		const lineage = withFeedback(lineageStateCase(key).lineage, {
			state: "READY",
			deliveredAt: null,
		});
		expect(traceVerdict(lineage, piece).sentence).toBe(
			`This feedback has not reached the developer yet: ${sentence}.`,
		);
	});

	it("says an admin withdrew a piece, before or after it reached the developer", () => {
		expect(verdictOf("withdrawn", piece).sentence).toBe(
			"This feedback reached the developer on the practice page, then an admin withdrew it.",
		);
		const unsent = withFeedback(lineageStateCase("state-ready").lineage, { withdrawn: true });
		expect(traceVerdict(unsent, piece)).toMatchObject({
			sentence: "This feedback did not reach the developer: an admin withdrew it first.",
			end: { kind: "lost", reason: "an admin withdrew it before it reached the developer" },
		});
	});

	it("says what stopped a piece on its way", () => {
		expect(verdictOf("state-rejected", piece).sentence).toBe(
			"This feedback did not reach the developer: an admin rejected it.",
		);
		expect(verdictOf("state-awaiting-approval", piece).end).toMatchObject({
			kind: "open",
			reason: "it awaits an admin’s approval",
		});
		expect(verdictOf("state-unconfirmed", piece).end.label).toBe("Not known");
	});

	it.each([
		["FAILED", "another part failed to post"],
		["UNCONFIRMED", "the rest is not confirmed yet"],
	] as const)(
		"says a %s piece that partly reached the work was partly delivered",
		(state, rest) => {
			const lineage = withFeedback(lineageStateCase("destination-on-work").lineage, { state });
			expect(traceVerdict(lineage, piece)).toMatchObject({
				sentence: `Part of this feedback reached the developer on GitHub, and ${rest}.`,
				end: { kind: "delivered", label: "Partly delivered" },
			});
		},
	);

	it("falls back to the review for a record the graph does not hold", () => {
		const { lineage } = lineageStateCase("complete");
		expect(traceVerdict(lineage, feedbackNode("missing"))).toStrictEqual(
			traceVerdict(lineage, REVIEW),
		);
	});
});

function withFeedback(
	lineage: ReviewLineage,
	change: Partial<NonNullable<ReviewLineage["nodes"][number]["feedback"]>>,
): ReviewLineage {
	return {
		...lineage,
		nodes: lineage.nodes.map((node) =>
			node.feedback === null ? node : { ...node, feedback: { ...node.feedback, ...change } },
		),
	};
}

function event(overrides: Partial<ItemEvent>): ItemEvent {
	return {
		decidedAt: null,
		foundBy: "WEBHOOK",
		id: "event-1",
		itemId: "1",
		itemVersion: "abc",
		name: "pull_request.ready_for_review",
		occurredAt: new Date("2026-10-08T08:00:00Z"),
		reason: null,
		reviewId: null,
		state: "WITHHELD",
		...overrides,
	};
}

describe("unreviewedVerdict", () => {
	const off = { reason: "PRACTICES_DISABLED" } as const;

	it("says a waiting event waits, not that practice review is off, while it is on", () => {
		const verdict = unreviewedVerdict(event({ state: "WAITING", ...off }), true);
		expect(verdict.sentence).toBe(
			"No review has started on this work yet: it waits before a review starts.",
		);
		expect(verdict.end).toMatchObject({ kind: "open", label: "Waiting" });
		expect(verdict.end.reason).not.toMatch(/turned off/u);
	});

	it("says why a waiting event waits where the reason still holds", () => {
		const verdict = unreviewedVerdict(event({ state: "WAITING", ...off }), false);
		expect(verdict.end.reason).toMatch(/^Practice review is turned off/u);
	});

	it("says practice review was off for a withheld event once it is on again", () => {
		const verdict = unreviewedVerdict(event(off), true);
		expect(verdict.sentence).toBe(
			"No review started on this work: practice review was turned off for this workspace when it happened.",
		);
		expect(verdict.end.kind).toBe("lost");
	});

	it("says practice review is off while it is off", () => {
		expect(unreviewedVerdict(event(off), false).sentence).toBe(
			"No review started on this work: practice review is turned off for this workspace.",
		);
	});

	it("says what the event's state means where no reason is recorded", () => {
		expect(unreviewedVerdict(event({ state: "EXPIRED" }), true).sentence).toBe(
			"No review started on this work: the event waited too long and no review started.",
		);
	});
});
