import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import { Card, CardContent } from "@/components/ui/card";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";

import { practiceReviewTiming } from "./fixtures";
import { ReviewTimeCard } from "./ReviewTimeCard";

type Canvas = StoryContext["canvas"];

const EMPTY_STAGE = { count: 0, medianSeconds: null, p90Seconds: null };

/** A figure's number, by its key: "finished". */
function figureOf(canvas: Canvas, key: string) {
	return canvas
		.getByLabelText("Review time figures")
		.querySelector(`[data-figure="${key}"] [data-figure-value]`);
}

/** The typical review's stages as "label value", in their order. */
function stagesOf(canvas: Canvas) {
	return [...canvas.getByRole("list", { name: "Where the time went" }).querySelectorAll("li")].map(
		(item) => item.textContent,
	);
}

/** What the card's (i) says, read by opening it and closing it again. */
async function infoOf(canvas: Canvas) {
	await userEvent.click(canvas.getByRole("button", { name: "About review time" }));
	const dialog = await screen.findByRole("dialog", { name: "About review time" });
	const text = dialog.textContent;
	await userEvent.keyboard("{Escape}");
	return text;
}

/** The bold terms of the card's (i), in their order. */
async function termsOf(canvas: Canvas) {
	await userEvent.click(canvas.getByRole("button", { name: "About review time" }));
	const dialog = await screen.findByRole("dialog", { name: "About review time" });
	const terms = [...dialog.querySelectorAll("strong")].map((term) => term.textContent);
	await userEvent.keyboard("{Escape}");
	return terms;
}

/**
 * The review time is the second part of the Queue card, under the range's name. The figure is the
 * median of whole reviews, from the moment that started each, or its request, until the comment was
 * posted on the work; under it, its split into Until asked, Queue, Preparation, Execution and To
 * developer, the words a
 * review's trace uses. The developer's own time before they read the feedback is never part of it.
 */
const meta = {
	component: ReviewTimeCard,
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
		state: { status: "ready", timing: practiceReviewTiming, stale: false },
		label: "Last 30 days",
		during: "in the last 30 days",
		finished: {
			state: { status: "ready", total: 34, stale: false },
			list: {
				list: "reviews",
				search: {
					from: "2026-09-10",
					to: "2026-10-09",
					status: ["COMPLETE", "PARTIAL", "FAILED"],
				},
			},
		},
	},
	argTypes: { state: { control: false } },
} satisfies Meta<typeof ReviewTimeCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The local data: the median, then its split into the four stages, which add up to it. The row under
 * it holds only what opens its reviews, the finished ones. Counts the Reviews list cannot open, such as
 * the reviews finished after the merge, are shown nowhere, not even in the (i).
 */
export const Default: Story = {
	play: async ({ canvas, canvasElement }) => {
		// The title is no link: the Reviews tab opens the same list.
		await expect(canvas.queryByRole("link", { name: /^Review time/u })).not.toBeInTheDocument();
		const figure = canvas.getByText("median, start to comment").previousElementSibling;
		await expect(figure).toHaveTextContent("15 min");
		await expect(stagesOf(canvas)).toStrictEqual([
			"Until asked2 min",
			"Queue26 s",
			"Preparation20 s",
			"Execution12 min",
			"To developer12 s",
		]);
		// The slowest tenth is gone; what each stage runs from and to is in the (i).
		await expect(canvas.queryByText("Slowest 1 in 10")).toBeNull();
		const info = await infoOf(canvas);
		// One short line a term, each term first and bold.
		await expect(info).toContain(
			"Median of whole reviews, from what started each to its first comment posted on the work without waiting for approval.",
		);
		await expect(canvas.queryByText(/^No measured/u)).toBeNull();
		await expect(info).toContain(
			"Until asked from the moment on the work that started the review to its request.",
		);
		await expect(info).toContain("They add up to the median, but are not each stage's own median.");
		const terms = await termsOf(canvas);
		await expect(terms).toEqual([
			"Median",
			"Stages",
			"Until asked",
			"Queue",
			"Preparation",
			"Execution",
			"To developer",
			"Finished",
		]);
		// Nothing about the merge: the Reviews list cannot open those reviews, so they are not shown.
		await expect(info).not.toContain("merge");
		await expect(info).toContain(
			"Finished counts the reviews created in the range that completed, failed or timed out.",
		);
		// The stages are one bar, each part as wide as its share of the median.
		const parts = [...canvasElement.querySelectorAll<HTMLElement>("[data-stage]")];
		await expect(parts.map((part) => part.dataset.stage)).toEqual([
			"asked",
			"queue",
			"preparation",
			"execution",
			"delivery",
		]);
		const widths = parts.map((part) => part.getBoundingClientRect().width);
		await expect(widths[3]).toBeGreaterThan(widths[0] ?? 0);
		await expect(widths[3]).toBeGreaterThan((widths[1] ?? 0) * 10);
		// Every review recorded its execution start, so no unsplit run is drawn or explained.
		await expect(canvas.queryByText(/not split/u)).toBeNull();
		// The finished figure is the only one, the size of every other figure of the page, and opens
		// its reviews; the reviews after the merge are no figure.
		await expect(figureOf(canvas, "finished")).toHaveClass("text-sm");
		await expect(canvas.queryByText(/after merge/u)).toBeNull();
		await expect(canvas.getAllByRole("link")).toHaveLength(1);
	},
};

/**
 * What finished follows the range on screen, under the range's name, first in the row of figures,
 * and opens the Reviews table filtered to those reviews.
 */
export const FinishedInTheRange: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Review time" })).toHaveTextContent(
			/^Last 30 days/u,
		);
		await expect(
			[...canvas.getByLabelText("Review time figures").querySelectorAll("li")].map(
				(figure) => figure.textContent,
			),
		).toEqual(["34 finished"]);
		// The whole figure is the link, number and word, as the outcome counts are.
		await expect(canvas.getByRole("link", { name: "34 finished" }).textContent).toBe("34 finished");
		const link = new URL(canvas.getByRole<HTMLAnchorElement>("link", { name: "34 finished" }).href);
		await expect(link.pathname).toBe("/w/demo/admin/practices/reviews");
		await expect(link.searchParams.getAll("status")).toStrictEqual([
			"COMPLETE",
			"PARTIAL",
			"FAILED",
		]);
		await expect(link.searchParams.get("from")).toBe("2026-09-10");
		await expect(await infoOf(canvas)).toContain(
			"counts the reviews created in the range that completed, failed or timed out",
		);
	},
};

/** The queue backs up: its part of the median grows, and the row says so. */
export const SlowQueue: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			timing: {
				...practiceReviewTiming,
				total: { count: 14, medianSeconds: 545, p90Seconds: 1500 },
				totalShares: {
					asked: 0,
					queue: 240 / 545,
					preparation: 2 / 545,
					model: 300 / 545,
					runUnsplit: 0,
					posted: 3 / 545,
				},
				finishedAfterMerge: 0,
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("median, start to comment").previousElementSibling,
		).toHaveTextContent("9 min");
		await expect(stagesOf(canvas)).toStrictEqual([
			"Until asked0 s",
			"Queue4 min",
			"Preparation2 s",
			"Execution5 min",
			"To developer3 s",
		]);
	},
};

export const FinishedAfterMerge: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			timing: {
				...practiceReviewTiming,
				finishedAfterMerge: 5,
				finishedAfterMergeStartedByMerge: 2,
			},
		},
	},
	play: async ({ canvas }) => {
		// Neither a figure nor a sentence in the (i): the Reviews list cannot open exactly these.
		await expect(canvas.queryByText(/after merge/u)).toBeNull();
		await expect(await infoOf(canvas)).not.toContain("merge");
	},
};

/**
 * Twenty-one comments were ready only after the work merged. No list opens exactly those, so they are
 * neither a figure nor a sentence in the (i).
 */
export const CommentsLostToMerge: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			timing: { ...practiceReviewTiming, feedbackWithheldAsMerged: 21 },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/lost to merge/u)).toBeNull();
		await expect(
			[...canvas.getByLabelText("Review time figures").querySelectorAll("li")].map(
				(figure) => figure.textContent,
			),
		).toEqual(["34 finished"]);
		await expect(await infoOf(canvas)).not.toContain("merge");
	},
};

const NO_TIME = {
	...practiceReviewTiming,
	asked: EMPTY_STAGE,
	queue: EMPTY_STAGE,
	preparation: EMPTY_STAGE,
	model: EMPTY_STAGE,
	posted: EMPTY_STAGE,
	total: EMPTY_STAGE,
	totalShares: null,
	finished: EMPTY_STAGE,
	finishedShares: null,
	read: EMPTY_STAGE,
	finishedAfterMerge: 0,
};

/** No review got past the queue, so there is no time to show and nothing to warn about. */
export const NoData: Story = {
	args: {
		state: { status: "ready", stale: false, timing: NO_TIME },
		finished: {
			state: { status: "ready", total: 0, stale: false },
			list: { list: "reviews", search: { status: ["COMPLETE", "PARTIAL", "FAILED"] } },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("No measured review time in the last 30 days.");
		await expect(figureOf(canvas, "finished")).toHaveTextContent("0");
		await expect(canvas.queryByText("median, start to comment")).toBeNull();
		await expect(canvas.queryByRole("list", { name: "Where the time went" })).toBeNull();
		await expect(canvas.queryByRole("link")).not.toBeInTheDocument();
	},
};

/**
 * Reviews finished, but none has a measured time, such as one started by an event whose time was not
 * recorded. The card says the time is missing and still counts and opens the reviews that finished.
 */
export const FinishedWithoutMeasuredTime: Story = {
	args: {
		state: { status: "ready", stale: false, timing: NO_TIME },
		finished: {
			state: { status: "ready", total: 3, stale: false },
			list: { list: "reviews", search: { status: ["COMPLETE", "PARTIAL", "FAILED"] } },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("No measured review time in the last 30 days.");
		await expect(canvas.queryByText(/No review finished/u)).toBeNull();
		await expect(canvas.getByRole("link", { name: "3 finished" })).toHaveTextContent("3");
		await expect(canvas.queryByRole("list", { name: "Where the time went" })).toBeNull();
	},
};

/**
 * No comment on the work has a measured time: the course's feedback goes to the practice pages, or
 * every comment on the work waited for an admin's approval, which the time leaves out. Comments may
 * still have been delivered, so the card says only that their time is missing, and measures the
 * reviews to their end, never "to comment".
 */
export const NoComment: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			timing: {
				...practiceReviewTiming,
				posted: EMPTY_STAGE,
				total: EMPTY_STAGE,
				totalShares: null,
				finished: { count: 40, medianSeconds: 600, p90Seconds: 1200 },
				finishedShares: {
					asked: 0.1,
					queue: 0.01,
					preparation: 0.01,
					model: 0.88,
					runUnsplit: 0,
					posted: null,
				},
				finishedAfterMerge: 0,
			},
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("No measured time to a comment on the work in the last 30 days.");
		await expect(canvas.queryByText(/No review posted/u)).toBeNull();
		canvas.getByText("median, start to end of review");
		await expect(canvas.queryByText("median, start to comment")).toBeNull();
		await expect(await infoOf(canvas)).toContain(
			"Median of whole reviews, from what started each to the end of the review, as no time to a comment posted without waiting for approval was measured.",
		);
		// No time to a comment was measured, so there is no To developer to split off.
		await expect(stagesOf(canvas)).toStrictEqual([
			"Until asked1 min",
			"Queue6 s",
			"Preparation6 s",
			"Execution9 min",
		]);
	},
};

/**
 * Part of the range ran before reviews recorded when their execution started: their whole run is
 * its own part of the bar, "Run (not split)", and the (i) says why. Split runs stay split.
 */
export const OlderReviewsNotSplit: Story = {
	args: {
		state: {
			status: "ready",
			stale: false,
			timing: {
				...practiceReviewTiming,
				total: { count: 20, medianSeconds: 600, p90Seconds: 1200 },
				totalShares: {
					asked: 0,
					queue: 0.1,
					preparation: 0.05,
					model: 0.4,
					runUnsplit: 0.4,
					posted: 0.05,
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(stagesOf(canvas)).toStrictEqual([
			"Until asked0 s",
			"Queue1 min",
			"Preparation30 s",
			"Execution4 min",
			"Run (not split)4 min",
			"To developer30 s",
		]);
		await expect(await infoOf(canvas)).toContain(
			"Run (not split) the whole run of older reviews that did not record when their execution started.",
		);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		canvas.getByText("Loading the review time…");
	},
};

export const LoadError: Story = {
	args: { state: { status: "error", error: { status: 500 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText("We could not load the review time");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
	},
};

/** The previous range's figures, drained of colour, while the range just chosen loads. */
export const Stale: Story = {
	args: { state: { status: "ready", timing: practiceReviewTiming, stale: true } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("median, start to comment").closest("[aria-busy]"),
		).toHaveAttribute("aria-busy", "true");
	},
};

export const Narrow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByText("median, start to comment");
		await expectNoPageOverflow();
	},
};

/** The link and the line about the stages keep their contrast on the dark surface. */
export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(figureOf(canvas, "finished")).toHaveTextContent("34");
	},
};
