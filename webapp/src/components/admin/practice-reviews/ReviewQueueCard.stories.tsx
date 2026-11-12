import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { withWidePage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { hoursBefore, minutesBefore } from "@/stories/story-clock";

import {
	busyReviewQueue,
	idleReviewQueue,
	practiceReviewTiming,
	reviewQueueHead,
} from "./fixtures";
import type { ReviewListTarget } from "./review-outcomes";
import { ReviewQueueCard } from "./ReviewQueueCard";

type Canvas = StoryContext["canvas"];

/** The two parts of the card, now and the range, by their regions. */
/** A line of figures as each figure's words: "2 running". */
function figuresIn(canvas: Canvas, name: string) {
	return [...canvas.getByRole("list", { name }).querySelectorAll("li")].map(
		(figure) => figure.textContent,
	);
}

function partsOf(canvas: Canvas) {
	return [
		canvas.getByRole("region", { name: "The queue" }).getBoundingClientRect(),
		canvas.getByRole("region", { name: "Review time" }).getBoundingClientRect(),
	] as const;
}

const FINISHED_LIST: ReviewListTarget = {
	list: "reviews",
	search: { from: "2026-09-10", to: "2026-10-09", status: ["COMPLETE", "PARTIAL", "FAILED"] },
};

/**
 * The Queue card of the overview: the queue now on the left, the selected range on the right, side
 * by side from a medium width and one above the other below it.
 */
const meta = {
	component: ReviewQueueCard,
	decorators: [withWidePage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		queue: { status: "ready", queue: busyReviewQueue },
		timing: { status: "ready", timing: practiceReviewTiming, stale: false },
		finished: { state: { status: "ready", total: 34, stale: false }, list: FINISHED_LIST },
		label: "Last 30 days",
		during: "in the last 30 days",
	},
	argTypes: { queue: { control: false }, timing: { control: false }, finished: { control: false } },
} satisfies Meta<typeof ReviewQueueCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Two running, five waiting, the oldest for four minutes; the range's median and its stages. The
 * two parts sit side by side, the range on the right, level with the queue.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const [now, range] = partsOf(canvas);
		await expect(range.left).toBeGreaterThan(now.right);
		await expect(Math.abs(range.top - now.top)).toBeLessThan(2);
	},
};

/**
 * The test workspace as an admin sees it: two reviews run, one is held for budget and nothing
 * waits; 272 reviews finished, three comments were lost to the merge and twelve reviews finished
 * after it, two of them started by the merge.
 */
export const TestWorkspace: Story = {
	args: {
		queue: {
			status: "ready",
			queue: {
				...idleReviewQueue,
				running: 2,
				heldForBudget: 1,
				finishedLastDay: 30,
				finishedPerHourLastDay: 1.25,
				head: [
					{ ...reviewQueueHead.runningLong, since: hoursBefore(39) },
					{ ...reviewQueueHead.runningShort, since: hoursBefore(38) },
					{ ...reviewQueueHead.waitingLong, heldForBudget: true, since: hoursBefore(42) },
				],
			},
		},
		timing: {
			status: "ready",
			stale: false,
			timing: {
				...practiceReviewTiming,
				total: { count: 120, medianSeconds: 660, p90Seconds: 1500 },
				totalShares: {
					asked: 17 / 660,
					queue: 60 / 660,
					preparation: 60 / 660,
					model: 511 / 660,
					runUnsplit: 0,
					posted: 12 / 660,
				},
				finishedAfterMerge: 12,
				finishedAfterMergeStartedByMerge: 2,
				feedbackWithheldAsMerged: 3,
			},
		},
		finished: { state: { status: "ready", total: 272, stale: false }, list: FINISHED_LIST },
	},
	play: async ({ canvas }) => {
		await expect(figuresIn(canvas, "Queue now")).toEqual([
			"0 waiting",
			"2 running",
			"Needs a look: 1 on hold for budget",
		]);
		// Nothing waits, so there is no oldest wait to tell.
		await expect(canvas.queryByText(/oldest wait/u)).toBeNull();
		// A held review says how long it has waited since its request, not "0 s".
		await expect(
			canvas.getByRole("table", { name: "First in the queue" }).querySelectorAll("tbody tr")[2],
		).toHaveTextContent("On hold1 d 18 h");
		// Only what opens its reviews is a figure; the merge's share is said in the (i).
		await expect(figuresIn(canvas, "Review time figures")).toEqual(["272 finished"]);
		// Running and finished counts open their reviews.
		for (const name of ["2 running", "272 finished"]) {
			canvas.getByRole("link", { name });
		}
	},
};

/** Nothing waits or runs: the figures are quiet and one line says so. */
export const Idle: Story = {
	args: {
		queue: { status: "ready", queue: idleReviewQueue },
	},
};

/** The oldest has waited three quarters of an hour: the wait is marked, and opens that review. */
export const LongWait: Story = {
	args: {
		queue: {
			status: "ready",
			queue: {
				...busyReviewQueue,
				oldestWaitingSince: minutesBefore(45),
				head: [{ ...reviewQueueHead.waitingLong, since: minutesBefore(45) }],
			},
		},
	},
};

/** Both halves load. */
export const Loading: Story = {
	args: {
		queue: { status: "loading" },
		timing: { status: "loading" },
		finished: { state: { status: "loading" }, list: FINISHED_LIST },
	},
};

/** On a phone the range goes under the queue, and nothing runs off the page. */
export const Narrow: Story = {
	args: TestWorkspace.args,
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		const [now, range] = partsOf(canvas);
		await expect(range.top).toBeGreaterThanOrEqual(now.bottom);
		await expectNoPageOverflow();
	},
};

export const Dark: Story = {
	args: TestWorkspace.args,
	globals: { theme: "dark" },
};
