import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import { Card, CardContent } from "@/components/ui/card";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
import { minutesBefore } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";

import { busyReviewQueue, idleReviewQueue, reviewQueueHead } from "./fixtures";
import { ReviewQueueNow } from "./ReviewQueueNow";

/**
 * The queue as it stands now, whatever the range: the first part of the Queue card, above the
 * review time. Only a long wait and a hold for budget are marked.
 */
/** The card's figures as "label: number", in their order; each reads number first. */
function figuresOf(canvas: StoryContext["canvas"]) {
	const list = canvas.getByLabelText("Queue now");
	return [...list.querySelectorAll("li")].map((figure) => {
		const value = figure.querySelector("[data-figure-value]")?.textContent ?? "";
		return `${figure.textContent.slice(value.length).trim()}: ${value}`;
	});
}

/** The reviews listed in line, apart from the figures above them. */
function rowsOf(canvas: StoryContext["canvas"]) {
	const table = within(canvas.getByRole("table", { name: "First in the queue" }));
	return table.getAllByRole("row").slice(1);
}

/** One figure's number, by its key. */
function figureValue(canvas: StoryContext["canvas"], key: string) {
	return canvas
		.getByLabelText("Queue now")
		.querySelector(`[data-figure="${key}"] [data-figure-value]`);
}

const meta = {
	component: ReviewQueueNow,
	decorators: [
		(Story) => (
			<Card size="sm" className="max-w-xl">
				<CardContent className="@container">
					<Story />
				</CardContent>
			</Card>
		),
		withStandardPage,
	],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		state: { status: "ready", queue: busyReviewQueue },
	},
	argTypes: { state: { control: false } },
} satisfies Meta<typeof ReviewQueueNow>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing waits or runs: quiet zeroes, no oldest wait, one calm line. No row and no link. */
export const Idle: Story = {
	args: { state: { status: "ready", queue: idleReviewQueue } },
	play: async ({ canvas }) => {
		const now = canvas.getByRole("region", { name: "The queue" });
		await expect(now).toHaveTextContent("Nothing waits or runs.");
		await expect(figuresOf(canvas)).toEqual(["waiting: 0", "running: 0", "on hold for budget: 0"]);
		await expect(canvas.queryByRole("list", { name: "First in the queue" })).toBeNull();
		await expect(canvas.queryByRole("link", { name: /^See all/u })).toBeNull();
		await expect(now.querySelector(".lucide-triangle-alert")).toBeNull();
	},
};

/**
 * Two running and five waiting, the oldest for four minutes: busy, not stuck, so nothing is
 * marked. The first three in line each open their review, and the rest are one link away.
 */
export const Busy: Story = {
	args: { state: { status: "ready", queue: { ...busyReviewQueue, waiting: 5, heldForBudget: 0 } } },
	play: async ({ canvas }) => {
		const now = canvas.getByRole("region", { name: "The queue" });
		await expect(figuresOf(canvas)).toEqual([
			"waiting: 5",
			"running: 2",
			"on hold for budget: 0",
			"oldest wait: 4 min",
		]);
		// Waiting and running open the Reviews table at their queue state, whatever the range.
		const opens = (name: string) =>
			new URL(canvas.getByRole<HTMLAnchorElement>("link", { name }).href).searchParams;
		await expect(opens("5 reviews waiting").get("queueState")).toBe("WAITING");
		await expect(opens("2 reviews running").get("queueState")).toBe("RUNNING");
		await expect(opens("2 reviews running").getAll("status")).toStrictEqual([]);
		await expect(opens("2 reviews running").get("from")).toBeNull();
		// Number and word are one link, as the outcome counts are; the number is black, the word muted.
		const running = canvas.getByRole("link", { name: "2 reviews running" });
		await expect(running.textContent).toBe("2 running");
		const number = figureValue(canvas, "running") ?? now;
		await expect(getComputedStyle(number).color).toBe(getComputedStyle(now).color);
		await expect(getComputedStyle(number).fontWeight).toBe("600");
		await expect(getComputedStyle(running).color).not.toBe(getComputedStyle(number).color);
		// Nothing on hold: a quiet zero opens nothing.
		await expect(canvas.queryByRole("link", { name: /on hold/u })).toBeNull();
		// The oldest wait opens the review that waits, number and word as one link too.
		const oldest = canvas.getByRole("link", { name: "Oldest wait 4 min: open that review" });
		await expect(oldest.textContent).toBe("4 min oldest wait");
		await expect(levelsOpenedBy(oldest)).toEqual(["review:0c0c0c0c-0000-4000-8000-000000000003"]);
		const rows = rowsOf(canvas);
		await expect(rows).toHaveLength(3);
		// A small table: work, developer, state and how long, each column lined up down the rows.
		await expect(
			within(canvas.getByRole("table", { name: "First in the queue" }))
				.getAllByRole("columnheader")
				.map((head) => head.textContent),
		).toEqual(["Work", "Developer", "State", "Since / Waited"]);
		await expect(rows[0]).toHaveTextContent("#1423Ada LovelaceRunning3 min");
		await expect(rows[2]).toHaveTextContent("#1431Alan TuringWaiting4 min");
		const lefts = (column: number) =>
			new Set(
				rows.map((row) => Math.round(row.children[column]?.getBoundingClientRect().left ?? 0)),
			);
		await expect(lefts(2).size).toBe(1);
		// A queued review opens its sheet over the page.
		await expect(levelsOpenedBy(canvas.getByRole("link", { name: "#1423" }))).toEqual([
			"review:0c0c0c0c-0000-4000-8000-000000000001",
		]);
		// The link names no count: it opens reviews by status, which need not add up to the figures.
		const all = canvas.getByRole("link", { name: "See all" });
		const search = new URL(all.getAttribute("href") ?? "", "http://x").searchParams;
		await expect(search.getAll("status")).toStrictEqual(["RUNNING", "QUEUED"]);
		await expect(now.querySelector(".lucide-triangle-alert")).toBeNull();
	},
};

/**
 * Each counted figure opens the Reviews table at its own queue state and names its count, so the
 * list it opens holds as many reviews as it says (`-overview-queue-route.test.tsx` follows the links).
 * The oldest wait counts no rows and is not one of them.
 */
export const FiguresOpenTheirQueueState: Story = {
	args: { state: { status: "ready", queue: { ...busyReviewQueue, waiting: 4, heldForBudget: 1 } } },
	play: async ({ canvas }) => {
		const figures = canvas.getByLabelText("Queue now");
		const counted = ["waiting", "running", "held"].map((key) =>
			within(
				figures.querySelector<HTMLElement>(`[data-figure="${key}"]`) ?? figures,
			).getByRole<HTMLAnchorElement>("link"),
		);
		await expect(
			counted.map(
				(link) =>
					`${link.getAttribute("aria-label") ?? ""} -> ${new URL(link.href).searchParams.toString()}`,
			),
		).toStrictEqual([
			"4 reviews waiting -> queueState=WAITING",
			"2 reviews running -> queueState=RUNNING",
			"1 review on hold for budget, needs a look -> queueState=HELD",
		]);
	},
};

/**
 * The oldest has waited three quarters of an hour, past the overview's mark at half an hour: the
 * wait is the one thing marked, in words and an icon, without colour, since slow is not broken.
 */
export const LongWait: Story = {
	args: {
		state: {
			status: "ready",
			queue: {
				...busyReviewQueue,
				running: 1,
				activeWorkers: 1,
				waiting: 2,
				heldForBudget: 0,
				oldestWaitingSince: minutesBefore(45),
				head: [
					reviewQueueHead.runningLong,
					{ ...reviewQueueHead.waitingLong, since: minutesBefore(45) },
					reviewQueueHead.waitingShort,
				],
			},
		},
	},
	play: async ({ canvas }) => {
		const wait = figureValue(canvas, "oldest");
		await expect(wait).toHaveTextContent("Needs a look: 45 min");
		await expect(wait?.querySelector(".lucide-triangle-alert")).not.toBeNull();
		await expect(wait?.querySelector(".text-warning, .text-destructive")).toBeNull();
		// The link's name keeps the mark its own name would hide.
		await expect(
			canvas.getByRole("link", { name: "Oldest wait 45 min, needs a look: open that review" }),
		).toHaveTextContent("Needs a look: 45 min oldest wait");
		await expect(canvas.queryByRole("link", { name: /^See all/u })).toBeNull();
	},
};

/**
 * A quarter of an hour is an ordinary wait on a course day, about the median: below the half hour,
 * nothing is marked.
 */
export const WaitBelowMark: Story = {
	args: {
		state: {
			status: "ready",
			queue: {
				...busyReviewQueue,
				waiting: 2,
				heldForBudget: 0,
				oldestWaitingSince: minutesBefore(15),
			},
		},
	},
	play: async ({ canvas }) => {
		const now = canvas.getByRole("region", { name: "The queue" });
		await expect(figuresOf(canvas)).toContain("oldest wait: 15 min");
		// That review is not among the first in line, so the wait opens nothing.
		await expect(canvas.queryByRole("link", { name: /^Oldest wait/u })).toBeNull();
		await expect(now.querySelector(".lucide-triangle-alert")).toBeNull();
	},
};

/** The budget is used up: the hold is marked, and its row waits last, after the work that can run. */
export const OnHold: Story = {
	args: {
		state: {
			status: "ready",
			queue: {
				...idleReviewQueue,
				heldForBudget: 1,
				finishedLastHour: 0,
				finishedLastDay: 3,
				finishedPerHourLastDay: 0.125,
				head: [
					{
						...reviewQueueHead.waitingLong,
						heldForBudget: true,
						since: minutesBefore(42),
					},
				],
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(figureValue(canvas, "held")).toHaveTextContent("Needs a look: 1");
		// Nothing runs until an admin acts: broken, so red.
		await expect(figureValue(canvas, "held")?.querySelector(".text-destructive")).not.toBeNull();
		// The hold opens the reviews it holds; its name keeps the mark's words.
		const held = canvas.getByRole<HTMLAnchorElement>("link", {
			name: "1 review on hold for budget, needs a look",
		});
		await expect(new URL(held.href).searchParams.get("queueState")).toBe("HELD");
		await expect(rowsOf(canvas)).toHaveLength(1);
		await expect(rowsOf(canvas)[0]).toHaveTextContent("#1431Alan TuringOn hold42 min");
	},
};

/** Many in line: still three rows, and "See all" opens the rest. */
export const ManyItems: Story = {
	args: {
		state: {
			status: "ready",
			queue: {
				...busyReviewQueue,
				running: 4,
				activeWorkers: 4,
				waiting: 37,
				oldestWaitingSince: minutesBefore(9),
				heldForBudget: 2,
				finishedLastHour: 48,
				finishedLastDay: 610,
				finishedPerHourLastDay: 610 / 24,
			},
		},
	},
	play: async ({ canvas, userEvent }) => {
		await expect(rowsOf(canvas)).toHaveLength(3);
		canvas.getByRole("link", { name: "See all" });
		// The (i) names no figure that no list opens: neither the pace nor the number of workers.
		await userEvent.click(canvas.getByRole("button", { name: "About the queue" }));
		const about = await screen.findByRole("dialog", { name: "About the queue" });
		await expect(about).toHaveTextContent("counts reviews that run now. The count opens them.");
		await expect(about).not.toHaveTextContent(/Pace|an hour|4 workers/u);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		canvas.getByText("Loading the queue…");
	},
};

export const LoadError: Story = {
	args: { state: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText("We could not load the queue");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

/** At 320 px the rows truncate the developer before the time, and nothing scrolls sideways. */
export const Narrow: Story = {
	args: { state: { status: "ready", queue: busyReviewQueue } },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		await expect(rowsOf(canvas)[0]).toHaveTextContent(/Running3 min$/u);
		await expectNoPageOverflow();
	},
};

/** The status icons and the hold's warning keep their contrast on the dark surface. */
export const Dark: Story = {
	args: {
		state: {
			status: "ready",
			queue: { ...busyReviewQueue, heldForBudget: 1, oldestWaitingSince: minutesBefore(14) },
		},
	},
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "See all" })).toBeVisible();
	},
};
