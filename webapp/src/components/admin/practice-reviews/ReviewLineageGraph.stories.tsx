import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ReviewLineage } from "@/api/types.gen";

import { expectSettledVisible } from "@/stories/overlay";

import {
	LINEAGE_FEEDBACK,
	LINEAGE_RESULT,
	LINEAGE_REVIEW_ID,
	lineageFeedbackFocus,
	lineageResultFocus,
	wholeReview,
	wholeReviewManyFeedback,
} from "./lineage-fixtures";

import { lineageRecords, numberedResults } from "./lineage-chain";
import { lineageStateCase, richReviewFixture, STATE_FEEDBACK_ID } from "./lineage-state-fixtures";
import { connectorBox, LANE_PX } from "./LineageConnectors";
import type { TraceNode } from "./review-trace";
import { ReviewLineageGraph } from "./ReviewLineageGraph";
import { DEFAULT_TRACE_DISPLAY, FULL_TRACE_DISPLAY } from "./trace-display";

const meta = {
	component: ReviewLineageGraph,
	tags: ["autodocs"],
	decorators: [
		(Story) => (
			<div className="w-[34rem] max-w-full p-4">
				<Story />
			</div>
		),
	],
	args: {
		lineage: lineageResultFocus,
		here: { kind: "result", id: LINEAGE_RESULT.cache404 },
		onOpen: fn(),
	},
} satisfies Meta<typeof ReviewLineageGraph>;

export default meta;
type Story = StoryObj<typeof meta>;

/** One of the "Show" control's toggles. */
function showToggle(root: HTMLElement, name: string): HTMLElement {
	return within(within(root).getByRole("group", { name: "Show" })).getByRole("button", { name });
}

function stepsOf(root: HTMLElement): HTMLElement {
	return within(root).getByRole("list", { name: "Steps, oldest first" });
}

function hereOf(root: HTMLElement): HTMLElement | null {
	return root.querySelector<HTMLElement>('[data-here="true"]');
}

function verdictOf(root: HTMLElement): HTMLElement | null {
	return root.querySelector<HTMLElement>("[data-trace-verdict]");
}

function endOf(root: HTMLElement): HTMLElement | null {
	return root.querySelector<HTMLElement>('[data-step="end"]');
}

export const ResultStory: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(verdictOf(canvasElement)).toHaveTextContent(
			"This observation led to 4 pieces of feedback; two were delivered, and two did not arrive.",
		);
		await expect(hereOf(canvasElement)).toHaveAttribute(
			"data-lineage-node",
			`result:${LINEAGE_RESULT.cache404}`,
		);
		const stages = [...stepsOf(canvasElement).querySelectorAll("[data-stage-label]")];
		await expect(stages.map((stage) => stage.textContent)).toStrictEqual([
			"Started by",
			"Review",
			"Observations",
			"Feedback",
			"Effect",
		]);
		await expect(stepsOf(canvasElement).querySelector('[data-step="start"]')).toHaveTextContent(
			"Event",
		);
		await expect(stepsOf(canvasElement).querySelectorAll('[data-step="feedback"]')).toHaveLength(4);
		await expect(
			canvas.getByRole("button", { name: "3 other observations, 5 other pieces of feedback" }),
		).toBeVisible();
		await expect(endOf(canvasElement)).toHaveTextContent(
			"4 pieces of feedback: 2 delivered, 2 did not arrive.",
		);
		// The trace links nowhere: a step opens its record over it.
		await expect(within(stepsOf(canvasElement)).queryAllByRole("link")).toHaveLength(0);
	},
};

export const ResultStorySiblingsShown: Story = {
	play: async ({ canvas, canvasElement, userEvent }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "3 other observations, 5 other pieces of feedback" }),
		);
		await expect(showToggle(canvasElement, "Siblings")).toHaveAttribute("aria-pressed", "false");
		await expect(stepsOf(canvasElement).querySelectorAll('[data-step="feedback"]')).toHaveLength(9);
		await expect(canvas.getAllByText("Withheld")[0]).toBeVisible();
		await expect(endOf(canvasElement)).toHaveTextContent(
			"4 pieces of feedback: 2 delivered, 2 did not arrive.",
		);
	},
};

/**
 * "Legend" beside the Show control opens the legend over the trace, so the rail stays where it is:
 * each mark the trace draws, grouped by what it marks, the icons, and what the trace cannot show.
 */
export const LegendOpen: Story = {
	play: async ({ canvas, canvasElement, userEvent }) => {
		const rail = stepsOf(canvasElement);
		const { top } = rail.getBoundingClientRect();
		await userEvent.click(canvas.getByRole("button", { name: "Legend" }));
		const legend = within(await screen.findByRole("region", { name: "Legend" }));
		// The popover fades in, so its contents are asserted once it has settled.
		await expectSettledVisible(legend.getByRole("heading", { name: "How to read the trace" }));
		await expect(legend.getByRole("heading", { name: "What the trace cannot show" })).toBeVisible();
		await expect(legend.getByRole("button", { name: "coalesced" })).toBeVisible();
		await expect(rail.getBoundingClientRect().top).toBe(top);
	},
};

/**
 * Connections draw their lanes right of the steps, so turning them on moves no step sideways: the
 * words only wrap a little earlier.
 */
export const ConnectionsKeepTheSteps: Story = {
	args: { lineage: wholeReview, here: undefined },
	play: async ({ canvasElement, userEvent }) => {
		const content = () =>
			stepsOf(canvasElement)
				.querySelector<HTMLElement>("[data-step-content]")
				?.getBoundingClientRect().left;
		const before = content();
		await userEvent.click(showToggle(canvasElement, "Connections"));
		await waitFor(async () => {
			await expect(
				canvasElement.querySelectorAll("path[data-connector-line]").length,
			).toBeGreaterThan(0);
		});
		await expect(content()).toBe(before);
	},
};

export const FeedbackStory: Story = {
	args: {
		lineage: lineageFeedbackFocus,
		here: { kind: "feedback", id: LINEAGE_FEEDBACK.onGitHub },
	},
	play: async ({ canvasElement }) => {
		const here = hereOf(canvasElement);
		await expect(here).toHaveAttribute(
			"data-lineage-node",
			`feedback:${LINEAGE_FEEDBACK.onGitHub}`,
		);
		await expect(here?.querySelectorAll("[data-result-chip]")).toHaveLength(2);
		await expect(here).toHaveAttribute("data-here", "true");
	},
};

/** A piece of work's story: the work is "You started here", and its newest review tells the rest. */
export const WorkStory: Story = {
	args: { lineage: wholeReview, here: { kind: "work" } },
	play: async ({ canvasElement }) => {
		await expect(hereOf(canvasElement)).toHaveAttribute("data-lineage-node", "work");
		await expect(stepsOf(canvasElement).querySelectorAll('[data-step="feedback"]')).toHaveLength(9);
	},
};

export const AllShown: Story = {
	args: { lineage: wholeReview, here: undefined, defaultDisplay: FULL_TRACE_DISPLAY },
	play: async ({ canvas, canvasElement, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Expand all" }));
		await expect(verdictOf(canvasElement)).toHaveTextContent(
			"This review completed after 5 min and composed 9 pieces of feedback; 6 reached the developer.",
		);
		// The partial package adds two basis edges; the earlier review’s comment has none here.
		await waitFor(async () => {
			await expect(canvasElement.querySelectorAll("path[data-connector-line]")).toHaveLength(11);
		});
		await expect(canvas.getByRole("list", { name: "Observations of this review" })).toBeVisible();
		// Said once, in the facts "Expand all" opens under the piece, not again on its line.
		await expect(canvasElement.querySelectorAll("[data-step-facts]").length).toBeGreaterThan(0);
		await expect(canvas.queryByText("Composed by")).toBeNull();
		await expect(canvas.queryByText("Composed by an earlier review")).toBeNull();
	},
};

export const DeliveredFeedback: Story = {
	args: { lineage: wholeReview, here: { kind: "feedback", id: LINEAGE_FEEDBACK.onGitHub } },
	play: async ({ canvasElement }) => {
		await expect(verdictOf(canvasElement)).toHaveTextContent(
			"This feedback reached the developer on GitHub.",
		);
		await expect(endOf(canvasElement)).toHaveAttribute("data-end", "delivered");
	},
};

/**
 * A review's own trace opens on its observations table, every row listed: the observations are what the
 * review is, not siblings. The table folds to the numbered dots and opens again on its own button.
 */
export const ResultTable: Story = {
	args: { lineage: wholeReview, here: { kind: "review" } },
	play: async ({ canvas, canvasElement, userEvent }) => {
		const table = () => canvasElement.querySelector('[data-trace-table="results"]');
		const rows = () =>
			canvas
				.getByRole("list", { name: "Observations of this review" })
				.querySelectorAll(":scope > li");
		await expect(showToggle(canvasElement, "Siblings")).toHaveAttribute("aria-pressed", "false");
		await expect(rows()).toHaveLength(4);
		await expect(
			table()?.querySelector(`[data-lineage-node="result:${LINEAGE_RESULT.cache404}"]`),
		).toHaveTextContent(/Not met.*Major.*B, C, D, F/u);
		await userEvent.click(canvas.getByRole("button", { name: "Fold the table" }));
		await expect(table()).toBeNull();
		await expect(canvas.getByRole("list", { name: "Observations by number" })).toBeVisible();
		await userEvent.click(
			canvas.getByRole("button", { name: "Show all 4 observations in the table" }),
		);
		await expect(rows()).toHaveLength(4);
	},
};

/** A trace that starts on one result keeps the review's other results folded to their dots. */
export const ResultTraceFoldsTheOthers: Story = {
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByRole("list", { name: "Observations on this path" })).toBeVisible();
		await expect(
			canvasElement.querySelectorAll('[data-trace-table="results"] [data-lineage-node]'),
		).toHaveLength(1);
		await expect(
			canvas.getByRole("button", { name: /^Show all \d+ observations in the table$/u }),
		).toHaveAttribute("aria-expanded", "false");
	},
};

export const FeedbackTable: Story = {
	args: { lineage: wholeReview, here: { kind: "review" } },
	play: async ({ canvasElement }) => {
		const tables = [...canvasElement.querySelectorAll('[data-trace-table="feedback"]')];
		await expect(tables).toHaveLength(1);
		const card = canvasElement.querySelector(`[data-feedback-row="${LINEAGE_FEEDBACK.onGitHub}"]`);
		await expect(card).toHaveTextContent("On the work");
		await expect(card?.querySelectorAll("[data-result-chip]")).toHaveLength(2);
	},
};

/** On a phone, the tables stack their rows: no column runs off the sheet. */
export const NarrowSheetStacks: Story = {
	args: { lineage: wholeReview, here: { kind: "review" } },
	decorators: [
		(Story) => (
			<div className="w-[22rem] max-w-full">
				<Story />
			</div>
		),
	],
	play: async ({ canvas, canvasElement, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Expand all" }));
		for (const table of canvasElement.querySelectorAll<HTMLElement>("[data-trace-table]")) {
			await expect(table.scrollWidth).toBeLessThanOrEqual(table.clientWidth + 1);
		}
		await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth + 1);
	},
};

// ------------------------------------------------------------------------------------------------
// Where the reader started
// ------------------------------------------------------------------------------------------------

/** The step marked "You started here": its control is current. */
function startedAt(root: HTMLElement): HTMLElement | null {
	return root.querySelector<HTMLElement>('[data-step-key]:has(> [aria-current="step"])');
}

/**
 * With system info, the trace opens on the record it was opened for, ringed and marked "You started
 * here", with its facts under it. There is no walk through the chain: no previous or next step, and
 * the arrow keys move nothing.
 */
export const StartedHere: Story = {
	args: { defaultDisplay: { ...DEFAULT_TRACE_DISPLAY, system: true } },
	play: async ({ canvasElement, userEvent }) => {
		const start = `result:${LINEAGE_RESULT.cache404}`;
		await expect(startedAt(canvasElement)).toHaveAttribute("data-step-key", start);
		await expect(hereOf(canvasElement)).toHaveAttribute("data-step-key", start);
		await expect(
			within(hereOf(canvasElement) ?? canvasElement).getByText("You started here"),
		).toBeVisible();
		await expect(startedAt(canvasElement)?.querySelector("[data-step-facts]")).toHaveTextContent(
			/Recorded/u,
		);
		await userEvent.keyboard("{ArrowDown}");
		await expect(startedAt(canvasElement)).toHaveAttribute("data-step-key", start);
		await expect(canvasElement.querySelectorAll("[data-step-facts]")).toHaveLength(1);
	},
};

/**
 * A press on a step opens that record's sheet over the trace. The ring and "You started here" stay
 * on the record the trace was opened for.
 */
export const OpenAStep: Story = {
	play: async ({ args, canvasElement, userEvent }) => {
		const feedback = stepsOf(canvasElement).querySelector<HTMLElement>(
			'[data-step="feedback"] [data-step-control]',
		);
		if (feedback === null) {
			throw new Error("The story has a piece of feedback");
		}
		await userEvent.click(feedback);
		await expect(args.onOpen).toHaveBeenCalledWith(expect.objectContaining({ kind: "feedback" }));
		await expect(startedAt(canvasElement)).toHaveAttribute(
			"data-step-key",
			`result:${LINEAGE_RESULT.cache404}`,
		);
		const review = stepsOf(canvasElement).querySelector<HTMLElement>('[data-step="review"]');
		await userEvent.click(within(review ?? canvasElement).getByRole("button"));
		await expect(args.onOpen).toHaveBeenLastCalledWith({ kind: "review", id: LINEAGE_REVIEW_ID });
		await expect(startedAt(canvasElement)).toHaveAttribute(
			"data-step-key",
			`result:${LINEAGE_RESULT.cache404}`,
		);
	},
};

export const OnlyFeedback: Story = {
	args: { lineage: wholeReview, here: { kind: "review" }, filter: { kind: "feedback" } },
	play: async ({ canvasElement }) => {
		await expect(showToggle(canvasElement, "Siblings")).toBeDisabled();
		await expect(
			stepsOf(canvasElement).querySelectorAll('[data-lineage-node^="result:"]'),
		).toHaveLength(0);
		await expect(
			stepsOf(canvasElement).querySelectorAll('[data-step="feedback"]').length,
		).toBeGreaterThan(0);
	},
};

/** Only what was lost: the feedback that never reached the developer, and nothing that arrived. */
export const OnlyLosses: Story = {
	args: { lineage: wholeReview, here: { kind: "review" }, filter: { kind: "losses" } },
	play: async ({ canvasElement }) => {
		const pieces = [...stepsOf(canvasElement).querySelectorAll('[data-step="feedback"]')];
		for (const piece of pieces) {
			await expect(piece).not.toHaveTextContent(/^Delivered/u);
		}
		// The verdict still speaks of the whole review.
		await expect(verdictOf(canvasElement)).toHaveTextContent(/composed 9 pieces of feedback/u);
	},
};

export const OnlyOnePractice: Story = {
	args: {
		lineage: wholeReview,
		here: { kind: "review" },
		filter: { kind: "practice", practiceSlug: "errors-carry-context" },
	},
	play: async ({ canvasElement }) => {
		const results = [...stepsOf(canvasElement).querySelectorAll('[data-lineage-node^="result:"]')];
		await expect(results.length).toBeGreaterThan(0);
	},
};

/**
 * Only one outcome's results, and the feedback written from them. The verdict still speaks of the
 * whole review, the results step says how many of them the filter shows, and the select offers
 * only the outcomes the review has, each with its count.
 */
export const OnlyOneOutcome: Story = {
	args: {
		lineage: wholeReview,
		here: { kind: "review" },
		filter: { kind: "outcome", outcome: "MET" },
	},
	play: async ({ canvas, userEvent }) => {
		await expect(canvas.getByText(/^It recorded 4 observations: /u)).toBeVisible();
		await expect(canvas.getByText("The filter shows 1 of them.")).toBeVisible();
		await userEvent.click(canvas.getByRole("combobox", { name: "Steps" }));
		await expectSettledVisible(await screen.findByRole("option", { name: "Only met (1)" }));
		await expect(screen.getByRole("option", { name: "Only not met (3)" })).toBeVisible();
		await expect(screen.queryByRole("option", { name: /^Only does not apply/u })).toBeNull();
		await expect(screen.queryByRole("option", { name: /^Only could not determine/u })).toBeNull();
	},
};

const richRecords = lineageRecords(richReviewFixture.lineage);
const richNumber = new Map(
	numberedResults(richRecords.results).map(({ result, number }) => [result.id, number]),
);
const richOutcome = new Map(richRecords.results.map((result) => [result.id, result.outcome]));
const acrossOutcomes = richRecords.feedback.find(
	(entry) =>
		entry.basis.some((id) => richOutcome.get(id) === "MET") &&
		entry.basis.some((id) => richOutcome.get(id) === "NOT_MET"),
);
if (acrossOutcomes === undefined) {
	throw new Error("The rich review has feedback resting on a Met and a Not met result");
}

/**
 * Only Met results, on a review whose feedback can rest on a Met and a Not met result at once. The
 * table lists the Met results only, but that piece still names both results it rests on, by their
 * numbers in the whole review: a filter narrows what shows, never what a record says.
 */
export const FilterKeepsWhatFeedbackRestsOn: Story = {
	args: {
		lineage: richReviewFixture.lineage,
		here: { kind: "review" },
		filter: { kind: "outcome", outcome: "MET" },
	},
	play: async ({ canvas, canvasElement }) => {
		const met = richRecords.results.filter((result) => result.outcome === "MET");
		await expect(
			canvas
				.getByRole("list", { name: "Observations of this review" })
				.querySelectorAll(":scope > li"),
		).toHaveLength(met.length);
		const row = canvasElement.querySelector(`[data-feedback-row="${acrossOutcomes.id}"]`);
		const chips = [...(row?.querySelectorAll<HTMLElement>("[data-result-chip]") ?? [])];
		await expect(
			chips
				.map((chip) => chip.dataset.resultId ?? "")
				.sort((left, right) => left.localeCompare(right)),
		).toStrictEqual([...acrossOutcomes.basis].sort((left, right) => left.localeCompare(right)));
		await expect(chips.map((chip) => Number(chip.dataset.resultChip))).toStrictEqual(
			acrossOutcomes.basis
				.map((id) => richNumber.get(id))
				.filter((number) => number !== undefined)
				.sort((a, b) => a - b),
		);
	},
};

const PROBLEM_TOGGLE = /^\d+ problems?: .+, of observation \d+$/u;
const PROBLEM_LIST = /^Problems of observation \d+$/u;

/**
 * A Not met row folds its problems to their count and severities, so many problems do not bury the
 * other results. Each opens and folds again on its own, and "Expand all" opens every one.
 */
export const ProblemsFoldUnderTheirResult: Story = {
	args: { lineage: wholeReview, here: { kind: "review" } },
	play: async ({ canvas, userEvent }) => {
		const toggles = canvas.getAllByRole("button", { name: PROBLEM_TOGGLE });
		await expect(canvas.queryAllByRole("list", { name: PROBLEM_LIST })).toHaveLength(0);
		const [toggle] = toggles;
		if (toggle === undefined) {
			throw new Error("The review has a Not met result with problems");
		}
		await expect(toggle).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(toggle);
		await expect(toggle).toHaveAttribute("aria-expanded", "true");
		const number = /of observation (?<number>\d+)$/u.exec(toggle.getAttribute("aria-label") ?? "")
			?.groups?.number;
		const list = canvas.getByRole("list", { name: `Problems of observation ${String(number)}` });
		await expect(
			within(list).getAllByRole("button", { name: /^Problem \d+ of observation \d+: /u }).length,
		).toBeGreaterThan(0);
		await userEvent.click(toggle);
		await expect(toggle).toHaveAttribute("aria-expanded", "false");

		await userEvent.click(canvas.getByRole("button", { name: "Expand all" }));
		await expect(canvas.getAllByRole("list", { name: PROBLEM_LIST })).toHaveLength(toggles.length);
	},
};

/** A trace that stands on a Not met result opens with that result's problems shown. */
export const TracedResultShowsItsProblems: Story = {
	play: async ({ canvas }) => {
		const [list, ...others] = canvas.getAllByRole("list", { name: PROBLEM_LIST });
		await expect(others).toHaveLength(0);
		await expect(list).toBeVisible();
	},
};

// ------------------------------------------------------------------------------------------------
// Connections
// ------------------------------------------------------------------------------------------------

/** The many-feedback review, with cards of every height: one summary wraps over several lines. */
const mixedHeights: ReviewLineage = {
	...wholeReviewManyFeedback,
	nodes: wholeReviewManyFeedback.nodes.map((node) =>
		node.result?.id === LINEAGE_RESULT.testNames
			? {
					...node,
					result: {
						...node.result,
						summary:
							"Three of the new tests are named after the method they call, not the behaviour they check. A failing run says which method broke, not what a reader expected. The names drift as the methods are renamed.",
					},
				}
			: node,
	),
};

/** A frame whose width a story changes, to see the lines follow the cards. */
function frameOf(root: HTMLElement): HTMLElement {
	const frame = root.querySelector<HTMLElement>("[data-connector-frame]");
	if (frame === null) {
		throw new Error("The story has a frame");
	}
	return frame;
}

/** Where a connector starts and ends, in the drawing's own coordinates. */
function endsOf(path: SVGPathElement) {
	const start = path.getPointAtLength(0);
	const end = path.getPointAtLength(path.getTotalLength());
	return { start, end };
}

/** The card a line must meet: the box around the chip that marks its end. */
function cardOf(root: HTMLElement, selector: string): DOMRect {
	const chip = root.querySelector(selector);
	if (chip === null) {
		throw new Error(`No chip for ${selector}`);
	}
	return connectorBox(chip).getBoundingClientRect();
}

async function expectConnectorsCentred(root: HTMLElement) {
	const paths = [...root.querySelectorAll<SVGPathElement>("path[data-connector-line]")];
	await expect(paths.length).toBeGreaterThan(0);
	const drawing = paths[0]?.ownerSVGElement?.getBoundingClientRect();
	if (drawing === undefined) {
		throw new Error("The lines are drawn in an svg");
	}
	const lanes = new Set<number>();
	for (const path of paths) {
		const result = CSS.escape(path.dataset.fromResult ?? "");
		const feedback = CSS.escape(path.dataset.toFeedback ?? "");
		const source = cardOf(root, `[data-connector="source"][data-result-id="${result}"]`);
		const target = cardOf(root, `[data-connector="use:${feedback}"][data-result-id="${result}"]`);
		const { start, end } = endsOf(path);
		await expect(Math.abs(start.y - (source.top - drawing.top + source.height / 2))).toBeLessThan(
			1,
		);
		await expect(Math.abs(end.y - (target.top - drawing.top + target.height / 2))).toBeLessThan(1);
		await expect(start.x).toBeLessThanOrEqual(source.right - drawing.left + 1);
		await expect(end.x).toBeLessThanOrEqual(target.right - drawing.left + 1);
		lanes.add(Number(path.dataset.laneX));
	}
	const sorted = [...lanes].sort((a, b) => a - b);
	for (const [index, x] of sorted.entries()) {
		const first = sorted[0] ?? x;
		await expect(Math.abs((x - first) / LANE_PX - index)).toBeLessThan(0.01);
	}
}

/** Waits for the lines to be redrawn after a change, then checks every end meets its card's centre. */
async function expectCentredSoon(root: HTMLElement) {
	await waitFor(async () => {
		await expectConnectorsCentred(root);
	});
}

export const ConnectionsMeetEveryCardInTheMiddle: Story = {
	args: { lineage: mixedHeights, here: undefined, defaultDisplay: FULL_TRACE_DISPLAY },
	decorators: [
		(Story) => (
			<div data-connector-frame className="w-(--frame) max-w-full" style={{ "--frame": "34rem" }}>
				<Story />
			</div>
		),
	],
	play: async ({ canvas, canvasElement, userEvent }) => {
		await expectCentredSoon(canvasElement);
		await userEvent.click(canvas.getByRole("button", { name: "Expand all" }));
		await expectCentredSoon(canvasElement);
		await userEvent.click(showToggle(canvasElement, "Times"));
		await expectCentredSoon(canvasElement);
		await userEvent.click(showToggle(canvasElement, "System info"));
		await expectCentredSoon(canvasElement);
		await userEvent.click(canvas.getByRole("button", { name: "Collapse all" }));
		await expectCentredSoon(canvasElement);
		frameOf(canvasElement).style.setProperty("--frame", "24rem");
		await expectCentredSoon(canvasElement);
		await userEvent.click(showToggle(canvasElement, "System info"));
		await userEvent.click(showToggle(canvasElement, "Times"));
		await expectCentredSoon(canvasElement);
	},
};

export const ConnectionsManyLines: Story = {
	args: {
		lineage: wholeReviewManyFeedback,
		here: { kind: "review" },
		defaultDisplay: { ...DEFAULT_TRACE_DISPLAY, siblings: true, connections: true },
	},
	decorators: [
		(Story) => (
			<div data-connector-frame className="w-(--frame) max-w-full" style={{ "--frame": "28rem" }}>
				<Story />
			</div>
		),
	],
	play: async ({ canvasElement }) => {
		await expectCentredSoon(canvasElement);
	},
};

// ------------------------------------------------------------------------------------------------
// Where it was lost, and where the time went
// ------------------------------------------------------------------------------------------------

function bottleneckOf(root: HTMLElement): HTMLElement | null {
	return root.querySelector<HTMLElement>("[data-trace-bottleneck]");
}

const STATE_PIECE: TraceNode = { kind: "feedback", id: STATE_FEEDBACK_ID };

function stateCaseArgs(key: string, here?: TraceNode) {
	const entry = lineageStateCase(key);
	return { lineage: entry.lineage, here: here ?? STATE_PIECE, review: entry.review };
}

export const BottleneckReviewFailed: Story = {
	args: stateCaseArgs("failed", { kind: "review" }),
	play: async ({ canvasElement }) => {
		await expect(bottleneckOf(canvasElement)).toHaveTextContent(/^Review failed/u);
		await expect(canvasElement.querySelector('[data-stage="review"]')).toHaveTextContent(
			"Stopped here",
		);
	},
};

/** The withheld-practice case, closed partial with the reason the server records for it. */
function partialCaseArgs() {
	const entry = lineageStateCase("withheld-practice");
	const { review } = entry;
	if (review === undefined) {
		throw new Error("The withheld-practice case has no review detail");
	}
	return {
		lineage: {
			...entry.lineage,
			nodes: entry.lineage.nodes.map((node) =>
				node.review === null
					? node
					: { ...node, review: { ...node.review, status: "PARTIAL" as const } },
			),
		},
		here: { kind: "review" } satisfies TraceNode,
		review: { ...review, status: "PARTIAL" as const, stopReason: "EXECUTION_INCOMPLETE" as const },
	};
}

/**
 * A partial review that ran to the end with one practice withheld. The line gives the recorded reason
 * and marks the results that are missing; it does not say the review stopped.
 */
export const BottleneckReviewPartial: Story = {
	args: partialCaseArgs(),
	play: async ({ canvasElement }) => {
		const line = bottleneckOf(canvasElement);
		await expect(line).toHaveTextContent(/^Partial review/u);
		await expect(line).toHaveTextContent(
			"The review did not record an observation for every selected practice.",
		);
		await expect(line).not.toHaveTextContent(/did not finish|stopped before/u);
		await expect(canvasElement.querySelector('[data-stage="results"]')).toHaveTextContent(
			"Lost here",
		);
		await expect(canvasElement.querySelector('[data-stage="review"]')).not.toHaveTextContent(
			"Stopped here",
		);
	},
};

/** At phone width the line and its strip wrap, and nothing scrolls sideways. */
export const BottleneckNarrow: Story = {
	args: stateCaseArgs("failed"),
	parameters: { chromatic: { viewports: [390] } },
	decorators: [
		(Story) => (
			<div className="w-[22rem] max-w-full">
				<Story />
			</div>
		),
	],
	play: async ({ canvas, canvasElement, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Expand all" }));
		await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth + 1);
	},
};

export const ExpandAllSaysEachThingOnce: Story = {
	args: { lineage: wholeReview, here: { kind: "review" } },
	play: async ({ canvas, canvasElement, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Expand all" }));
		const boxes = [...canvasElement.querySelectorAll<HTMLElement>("[data-step-facts]")];
		await expect(boxes.length).toBeGreaterThan(0);
		const texts = boxes.map((box) => box.textContent);
		await expect(new Set(texts).size).toBe(texts.length);
		for (const step of [
			'[data-step="start"]',
			'[data-step="end"]',
			'[data-step="results"] > [data-step-content] > [data-step-key="results"]',
		]) {
			const node = canvasElement.querySelector(step);
			await expect(node?.querySelector(":scope > [data-step-facts]") ?? null).toBeNull();
		}
	},
};
