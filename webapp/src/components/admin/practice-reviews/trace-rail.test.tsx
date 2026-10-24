import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import type { ItemEvent } from "@/api/types.gen";

import { isSlowGap, lineageRecords } from "./lineage-chain";
import {
	LINEAGE_FEEDBACK,
	LINEAGE_RESULT,
	LINEAGE_REVIEW_ID,
	reviewLineage,
	wholeReview,
} from "./lineage-fixtures";
import { feedbackLetters, storyFeedback, storyRail, storyResults } from "./lineage-story";
import type { TraceNode } from "./review-trace";
import { ReviewLineageGraph } from "./ReviewLineageGraph";
import { DEFAULT_TRACE_DISPLAY } from "./trace-display";

afterEach(cleanup);

const whole = lineageRecords(wholeReview);

function renderTrace(options: { here?: TraceNode } = {}) {
	return render(
		<ReviewLineageGraph
			lineage={wholeReview}
			here={options.here ?? { kind: "review" }}
			defaultDisplay={DEFAULT_TRACE_DISPLAY}
			onOpen={() => undefined}
		/>,
	);
}

const startedBy: ItemEvent = {
	id: "5e5e0010-0000-4000-8000-000000000001",
	itemId: "1",
	itemVersion: "27f4e88c9f5a",
	name: "scm.pull_request.ready_for_review",
	occurredAt: new Date("2026-07-28T13:34:40Z"),
	decidedAt: new Date("2026-07-28T13:34:41Z"),
	foundBy: "WEBHOOK",
	reason: null,
	reviewId: LINEAGE_REVIEW_ID,
	state: "STARTED",
};

describe("the rail's start", () => {
	it("starts at the event that started the review", () => {
		const [start] = storyRail(whole, whole.feedback, { startedBy });
		expect(start?.items).toStrictEqual([
			{
				kind: "start",
				key: "start",
				at: startedBy.occurredAt,
				origin: "EVENT",
				event: startedBy,
			},
		]);
	});

	it("starts at the review itself where no event is known", () => {
		const [start, review] = storyRail(whole, whole.feedback);
		const reviewStep = review?.items.find((item) => item.kind === "review");
		expect(start?.items[0]?.at).toStrictEqual(reviewStep?.at);
	});

	it("steps a piece of feedback at its delivery, and one never delivered at no time", () => {
		const feedback = storyRail(whole, whole.feedback).find((stage) => stage.stage === "feedback");
		const at = new Map(feedback?.items.map((item) => [item.key, item.at]));
		expect(at.has(`feedback:${LINEAGE_FEEDBACK.nothingNew}`)).toBe(true);
		expect(at.get(`feedback:${LINEAGE_FEEDBACK.nothingNew}`)).toBeUndefined();
		expect(at.get(`feedback:${LINEAGE_FEEDBACK.praise}`)).toStrictEqual(
			new Date("2026-07-28T13:43:00Z"),
		);
	});
});

function numbersOf(lineage: typeof wholeReview) {
	return storyResults(lineageRecords(lineage), undefined).numbered.map(
		({ result, number }) => `${number}:${result.id}`,
	);
}

function cacheNumber(list: ReturnType<typeof storyResults>["numbered"]) {
	return list.find((entry) => entry.result.id === LINEAGE_RESULT.cache404)?.number;
}

describe("the results' numbers and the feedback's letters", () => {
	it("stay the same in every trace of one review, in whatever order the graph lists them", () => {
		const reversed = { ...wholeReview, nodes: [...wholeReview.nodes].reverse() };
		expect(numbersOf(reversed)).toStrictEqual(numbersOf(wholeReview));
		expect(feedbackLetters(lineageRecords(reversed).feedback)).toStrictEqual(
			feedbackLetters(whole.feedback),
		);
	});

	it("keep their number when the trace is opened from one result", () => {
		const fromResult = lineageRecords(reviewLineage("RESULT", LINEAGE_RESULT.cache404));
		const kept = storyResults(fromResult, { kind: "result", id: LINEAGE_RESULT.cache404 }, whole);
		expect(cacheNumber(kept.numbered)).toBe(cacheNumber(storyResults(whole, undefined).numbered));
		expect(kept.self?.result.id).toBe(LINEAGE_RESULT.cache404);
	});
});

describe("the story around a record", () => {
	it("tells a result's feedback in full and folds the rest into siblings", () => {
		const { shown, siblings } = storyFeedback(
			whole,
			{ kind: "result", id: LINEAGE_RESULT.cache404 },
			false,
		);
		expect(shown.every((entry) => entry.basis.includes(LINEAGE_RESULT.cache404))).toBe(true);
		expect(siblings.some((entry) => entry.id === LINEAGE_FEEDBACK.praise)).toBe(true);
		expect(shown.length + siblings.length).toBe(whole.feedback.length);
	});

	it("tells every piece for the review", () => {
		expect(storyFeedback(whole, { kind: "review" }, false).siblings).toHaveLength(0);
	});
});

describe("where the reader started", () => {
	it("stays put when the toolbar's arrow keys move between the Show toggles", () => {
		const { container } = renderTrace();
		const before = container.querySelector('[aria-current="step"]');
		const times = within(screen.getByRole("group", { name: "Show" })).getByRole("button", {
			name: "Times",
		});
		fireEvent.keyDown(times, { key: "ArrowRight" });
		expect(container.querySelector('[aria-current="step"]')).toBe(before);
	});

	it("stays put when an arrow key is pressed on the rail: the trace is no walk", () => {
		const { container } = renderTrace();
		const before = within(container).getByRole("button", { current: "step" });
		fireEvent.keyDown(before, { key: "ArrowDown" });
		expect(container.querySelector('[aria-current="step"]')).toBe(before);
	});
});

describe("a long wait", () => {
	it("is coloured as soon as it reads as 30 min", () => {
		expect(isSlowGap(29 * 60 + 40)).toBe(true);
		expect(isSlowGap(29 * 60)).toBe(false);
	});
});

function tableRows(container: HTMLElement) {
	return container.querySelectorAll('[data-trace-table="results"] [data-lineage-node]');
}

describe("the results' table", () => {
	it("opens with every result and lets the reader fold and reopen the table", () => {
		const { container } = renderTrace();
		expect(tableRows(container)).toHaveLength(whole.results.length);
		fireEvent.click(screen.getByRole("button", { name: "Fold the table" }));
		expect(tableRows(container)).toHaveLength(0);
		fireEvent.click(
			screen.getByRole("button", { name: /^Show all \d+ observations in the table$/u }),
		);
		expect(tableRows(container)).toHaveLength(whole.results.length);
	});

	it("opens with Expand all and folds with Collapse all", () => {
		const { container } = renderTrace();
		fireEvent.click(screen.getByRole("button", { name: "Expand all" }));
		expect(tableRows(container)).toHaveLength(whole.results.length);
		fireEvent.click(screen.getByRole("button", { name: "Collapse all" }));
		expect(tableRows(container)).toHaveLength(0);
	});
});
