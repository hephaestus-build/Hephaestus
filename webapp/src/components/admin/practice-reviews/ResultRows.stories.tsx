import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { ReviewResult } from "@/api/types.gen";
import { expectNoPageOverflow } from "@/stories/reflow";
import { daysBefore, hoursBefore } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";

import { feedbackCounts, reviewResults } from "./fixtures";
import { resultSummary } from "./result-words";
import { ResultRows } from "./ResultRows";

const [firstResult] = reviewResults;
if (!firstResult) {
	throw new Error("The review fixtures must contain a result");
}

const longContent = {
	...firstResult,
	id: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
	summary:
		"The review keeps every boundary visible even when the result needs enough words to wrap across a narrow screen twice over",
	developer: {
		accountId: "10",
		avatarUrl: null,
		providerLogin: "alexandria-weekend-contributor",
		displayName: "Alexandria Catherine Montgomery-Worthington",
	},
} satisfies ReviewResult;

const overAWeek: ReviewResult[] = reviewResults.map((result, index) => ({
	...result,
	recordedAt: index < 3 ? hoursBefore(index + 1) : daysBefore(Math.floor(index / 3)),
}));

// Storybook resets args spies between runs; one shared instance is sufficient.
const clearFilters = fn();
const retry = fn();

const TABLE = { name: "Observations" } as const;

const meta = {
	component: ResultRows,
	parameters: {
		layout: "padded",
		chromatic: { viewports: [320, 768, 1440] },
	},
	tags: ["autodocs"],
	args: {
		state: { status: "ready", results: reviewResults },
	},
} satisfies Meta<typeof ResultRows>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", TABLE);
		await expect(
			within(table)
				.getAllByRole("columnheader")
				.map((head) => head.textContent),
		).toEqual(["Recorded", "Observation", "Work", "Developer", "Actions", "Open"]);
		await expect(canvas.getAllByText("Not met · 1 problem")).toHaveLength(4);
		await expect(canvas.getAllByText("Not met · 2 problems")).toHaveLength(3);
		await expect(canvas.getAllByText("Not met · 3 problems")).toHaveLength(1);
		await expect(canvas.queryByRole("button", { name: /^Not met/u })).not.toBeInTheDocument();
		await expect(canvas.getAllByText("2 problems")).toHaveLength(3);
		canvas.getByText("Does not apply");
		canvas.getByText("Critical");
		await expect(canvas.queryByText("Minor")).not.toBeInTheDocument();
		await expect(canvas.queryByText("Trivial")).not.toBeInTheDocument();
		await expect(canvas.getAllByText("Backfilled")).toHaveLength(2);
		const open = canvas
			.getAllByRole("link", { name: /^Open observation /u })
			.find(
				(link) =>
					link
						.getAttribute("aria-label")
						?.includes(resultSummary({ summary: firstResult.summary ?? undefined })) === true,
			);
		await expect(open === undefined ? [] : levelsOpenedBy(open)).toEqual([
			`result:${firstResult.id}`,
		]);
	},
};

export const ManyDays: Story = {
	args: { state: { status: "ready", results: overAWeek } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const days = within(canvas.getByRole("table", TABLE)).getAllByRole("rowheader");
		await expect(days.length).toBeGreaterThan(2);
	},
};

export const CouldNotDetermine: Story = {
	args: {
		state: {
			status: "ready",
			results: [
				{
					...firstResult,
					outcome: "COULD_NOT_DETERMINE",
					severity: null,
					problemCount: 0,
					summary: "The captured discussion leaves the chosen approach ambiguous",
				},
			],
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Could not determine");
		await expect(canvas.getAllByRole("button").map((button) => button.title)).toEqual([
			"",
			"Track",
		]);
		await expect(canvas.queryByText("Critical")).not.toBeInTheDocument();
	},
};

export const NoFeedbackCounts: Story = {
	args: {
		state: {
			status: "ready",
			results: [{ ...firstResult, feedback: feedbackCounts(["UNCONFIRMED"]) }],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("1 unconfirmed")).toBeNull();
		await expect(canvas.queryByText("No feedback")).toBeNull();
	},
};

export const OneLinkPerRow: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ canvas }) => {
		const rows = within(canvas.getByRole("table", TABLE))
			.getAllByRole("row")
			.filter((row) => within(row).queryAllByRole("cell").length > 0);
		await expect(rows).toHaveLength(reviewResults.length);
		for (const row of rows) {
			await expect(
				within(row)
					.getAllByRole("link")
					.map((link) => link.title),
			).toEqual(["Trace", ""]);
			await expect(
				within(row)
					.getAllByRole("button")
					.map((button) => button.title),
			).toEqual(["Track"]);
		}
		await expect(
			canvas.queryByRole("link", { name: /^Thin controllers/u }),
		).not.toBeInTheDocument();
		await expect(canvas.getAllByText("Thin controllers").length).toBeGreaterThan(0);
	},
};

export const Narrow: Story = {
	parameters: {
		chromatic: { viewports: [320] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		canvas.getByRole("table", TABLE);
		await expectNoPageOverflow();
	},
};

export const LongContent: Story = {
	args: { state: { status: "ready", results: [longContent] } },
	parameters: {
		chromatic: { viewports: [320] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async () => {
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("table", TABLE)).toHaveAttribute("aria-busy", "true");
	},
};

export const Empty: Story = {
	args: { state: { status: "empty", filtered: false } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await expect(
			within(canvas.getByRole("table", TABLE)).getByText("No observations yet"),
		).toBeVisible();
	},
};

export const FilteredToNothing: Story = {
	args: { state: { status: "empty", filtered: true, onClearFilters: clearFilters } },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await expect(clearFilters).toHaveBeenCalled();
	},
};

export const LoadFailed: Story = {
	args: {
		state: {
			status: "error",
			error: { status: 500, detail: "The result index is unavailable." },
			onRetry: retry,
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("We could not load observations");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retry).toHaveBeenCalled();
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		canvas.getByRole("table", TABLE);
	},
};

export const Tracked: Story = {
	parameters: {
		router: {
			initialUrl: `/?resultId=${firstResult.id}&reviewId=${firstResult.reviewId}&selected=true`,
		},
	},
	play: async ({ canvas }) => {
		const [tracked] = canvas.getAllByRole("button", { name: /^Track observation /u });
		await expect(tracked).toHaveAttribute("aria-pressed", "true");
		await expect(tracked?.closest("tr")).toHaveAttribute("data-variant", "highlighted");
	},
};

export const TextUnavailable: Story = {
	args: { state: { status: "ready", results: [{ ...firstResult, summary: null }] } },
	play: async ({ canvas }) => {
		canvas.getByText("Observation text unavailable");
		const link = canvas.getByRole("link", {
			name: /Open observation Observation text unavailable/u,
		});
		await expect(levelsOpenedBy(link)).toEqual([`result:${firstResult.id}`]);
		await expect(canvas.queryByText("undefined")).not.toBeInTheDocument();
	},
};
