import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ListPracticeReviewsResponse, ReviewRunSummary } from "@/api/types.gen";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { settledPopup } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";

import { reviewRuns } from "./fixtures";
import { REVIEW_PAGE_SIZE, type RunsSearch, runsQuery } from "./review-search";
import { ReviewRunsPage } from "./ReviewRunsPage";

/** The fixture's reviews, one of which completed and then failed to process what it produced. */
const RUNS: ReviewRunSummary[] = reviewRuns.map((run) =>
	run.id === "11111111-1111-1111-1111-111111111111" ? { ...run, resultProcessing: "FAILED" } : run,
);

/**
 * The page of reviews the endpoint would return for a search, computed from the fixture instead of
 * mocked over HTTP. The screen takes its rows as a prop, so a story that wants to prove a facet
 * narrows the list has to answer it — and answering it in a function keeps the whole file
 * network-free, which is what stops one story's failure from becoming every story's failure on the
 * shared Docs page.
 *
 * It filters through `runsQuery`, the very transformation the route sends, so a story cannot
 * "prove" a window the screen never asks for.
 */
function reviewsFor(search: RunsSearch): ListPracticeReviewsResponse {
	const query = runsQuery(search, REVIEW_PAGE_SIZE);
	const rows = RUNS.filter(
		(run) =>
			(query.status === undefined || query.status.includes(run.status)) &&
			(query.resultProcessing === undefined ||
				(run.resultProcessing !== undefined &&
					query.resultProcessing.includes(run.resultProcessing))) &&
			(!query.from || run.createdAt >= query.from) &&
			(!query.to || run.createdAt < query.to),
	);
	return {
		content: rows.slice(query.page * query.size, query.page * query.size + query.size),
		page: {
			number: query.page,
			size: query.size,
			totalElements: rows.length,
			totalPages: Math.max(1, Math.ceil(rows.length / query.size)),
		},
	};
}

/**
 * Toggles one status in the facet and closes it again, waiting out both transitions: a press on the
 * trigger while the popup is still leaving lands on its inert layer.
 */
async function pickStatus(
	canvas: StoryContext["canvas"],
	userEvent: {
		click: (element: Element) => Promise<void>;
		keyboard: (text: string) => Promise<void>;
	},
	option: RegExp,
) {
	await userEvent.click(canvas.getByRole("combobox", { name: /^Status/u }));
	await settledPopup();
	const listbox = screen.getByRole("listbox", { name: "Status options" });
	await userEvent.click(within(listbox).getByRole("option", { name: option }));
	await userEvent.keyboard("{Escape}");
	await waitFor(() => {
		void expect(screen.queryByRole("listbox", { name: "Status options" })).toBeNull();
	});
}

const meta = {
	component: ReviewRunsPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		search: { status: undefined, resultProcessing: undefined },
		onSearchChange: fn(),
		reviews: reviewsFor({ status: undefined, resultProcessing: undefined }),
		isLoading: false,
		error: null,
		onRetry: fn(),
	},
	// The screen is controlled: with a frozen `search` prop every facet reads as dead. The rows
	// follow the search the same way the route's query would.
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, patch) => (
				<ReviewRunsPage
					{...args}
					search={search}
					onSearchChange={(next) => {
						patch(next);
						args.onSearchChange(next);
					}}
					reviews={reviewsFor(search)}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ReviewRunsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Each row is named after the work rather than after the review, because a review has no name an
 * operator knows — it has a UUID.
 */
export const Default: Story = {
	parameters: { viewport: { defaultViewport: "desktop" } },
	play: async ({ canvas }) => {
		const list = await canvas.findByRole("list", { name: /Practice reviews/u });
		within(list).getByRole("link", {
			name: "Cache the workspace member lookup on the review path",
		});
		within(list).getByRole("link", { name: "How should we roll back the pricing migration?" });
		within(list).getByRole("link", { name: "Runbook: restoring a workspace from backup" });
		canvas.getByText("Results appear as it finishes.");
		canvas.getByText("It produced nothing before it stopped.");
	},
};

/**
 * Observations are a strip that draws every slot including the zeroes. Dropping them would start
 * the next number at a different x on each row and reflow the line under the reader whenever the
 * poll refreshes an active review. Feedback, spread over ten delivery states, is a sentence of only
 * the states that happened.
 */
export const WhatEachReviewProduced: Story = {
	parameters: { viewport: { defaultViewport: "desktop" }, chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("list", { name: /Practice reviews/u });
		// A still-running review has no tally, so index 0 is the first review with output rather than
		// the first row.
		const observations = canvas.getAllByRole("list", { name: "Observations" })[0];
		// A count and its word are two elements, so each pair is asserted on the strip, not per cell.
		for (const pair of [
			"1 positive outcome",
			"2 negative outcomes",
			"0 not applicable",
			"0 undetermined",
		]) {
			await expect(observations).toHaveTextContent(pair);
		}
		for (const strip of canvas.getAllByRole("list", { name: "Observations" })) {
			await expect(within(strip).getAllByRole("listitem")).toHaveLength(4);
		}
		canvas.getByText("Feedback: 1 delivered · 2 withheld");
	},
};

/** Statuses add up: each one chosen widens the list by the reviews in it. */
export const StatusFilter: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByRole("list", { name: /Practice reviews/u });
		await pickStatus(canvas, userEvent, /Failed/u);
		await canvas.findByText("1 review matches your filters.");
		await pickStatus(canvas, userEvent, /Running/u);
		await canvas.findByText("2 reviews match your filters.");
		await userEvent.click(canvas.getByRole("button", { name: "Reset" }));
		await canvas.findByText("7 reviews.");
	},
};

/**
 * A completed review whose results could not be processed is found by that failure, which its status
 * alone does not tell apart from a review that finished cleanly.
 */
export const ResultProcessingFilter: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("7 reviews.");
		const trigger = canvas.getByRole("combobox", { name: /^Result processing/u });
		await userEvent.click(trigger);
		await settledPopup();
		const listbox = screen.getByRole("listbox", { name: "Result processing options" });
		await userEvent.click(
			within(listbox).getByRole("option", { name: /Result processing failed/u }),
		);
		await userEvent.keyboard("{Escape}");
		await canvas.findByText("1 review matches your filters.");
		const list = canvas.getByRole("list", { name: /Practice reviews/u });
		const [row] = within(list).getAllByRole("listitem");
		if (!row) {
			throw new Error("The filtered list has no row");
		}
		within(row).getByRole("link", { name: "Cache the workspace member lookup on the review path" });
		within(row).getByText("Result processing failed");
		await waitFor(() => {
			void expect(screen.queryByRole("listbox")).toBeNull();
		});
	},
};

/**
 * The range arrives through `args` rather than through the calendar: the state worth pinning is a
 * populated filtered list that then intersects with the status filter, which clicking two days on an
 * empty toolbar does not reach.
 */
export const FilterByRequestedDate: Story = {
	args: {
		search: {
			status: undefined,
			resultProcessing: undefined,
			from: "2026-07-28",
			to: "2026-07-29",
		},
	},
	parameters: { viewport: { defaultViewport: "desktop" }, chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("3 reviews match your filters.");
		const list = canvas.getByRole("list", { name: /Practice reviews/u });
		within(list).getByRole("link", { name: /Retry webhook deliveries with backoff/u });
		within(list).getByRole("link", {
			name: "Cache the workspace member lookup on the review path",
		});
		await expect(
			within(list).queryByRole("link", {
				name: /Move invoice numbering behind the billing boundary/u,
			}),
		).not.toBeInTheDocument();

		canvas.getByRole("button", { name: "Requested: Jul 28 – Jul 29, 2026" });

		// A status intersects with the range rather than replacing it.
		await pickStatus(canvas, userEvent, /Completed/u);
		await canvas.findByText("2 reviews match your filters.");

		// The failed review was requested outside the window, so failed alone intersects to nothing.
		await pickStatus(canvas, userEvent, /Failed/u);
		await pickStatus(canvas, userEvent, /Completed/u);
		await canvas.findByText("No reviews found");
		canvas.getByText("No review matches these filters. Other reviews may exist outside them.");

		// One button clears the range and the status together.
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await canvas.findByText("7 reviews.");
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { viewports: [320, 768] },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		const list = await canvas.findByRole("list", { name: /Practice reviews/u });
		await within(list).findByRole("link", { name: /Retry webhook deliveries with backoff/u });
		await expectNoPageOverflow();
	},
};

/**
 * `DateRangeFacet` keeps its badge at every width, unlike `FacetMultiSelect`, which collapses to
 * "N selected" — so the separate applied-pill row the sibling lists carry deliberately leaves the
 * date range out, rather than printing the same range twice.
 */
export const MobileAppliedDateRange: Story = {
	args: {
		search: {
			status: undefined,
			resultProcessing: undefined,
			from: "2026-07-28",
			to: "2026-07-29",
		},
	},
	parameters: { chromatic: { viewports: [320] }, viewport: { defaultViewport: "reflow" } },
	play: async ({ canvas }) => {
		await canvas.findByText("3 reviews match your filters.");
		canvas.getByRole("button", { name: "Requested: Jul 28 – Jul 29, 2026" });
		await expectNoPageOverflow();
	},
};

/** The skeleton draws `REVIEW_PAGE_SIZE` rows, so results replace it without moving the pager. */
export const Loading: Story = {
	args: { reviews: undefined, isLoading: true },
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <ReviewRunsPage {...args} />,
	play: async ({ canvas }) => {
		await canvas.findByText("Loading reviews");
		await expect(canvas.queryByRole("list", { name: /Practice reviews/u })).not.toBeInTheDocument();
	},
};

export const NoReviewsYet: Story = {
	args: {
		reviews: {
			content: [],
			page: { number: 0, size: REVIEW_PAGE_SIZE, totalElements: 0, totalPages: 1 },
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <ReviewRunsPage {...args} />,
	play: async ({ canvas }) => {
		await canvas.findByText("No reviews found");
		// Nothing is filtered, so the empty state must not offer an action that would change nothing.
		await expect(
			canvas.queryByRole("button", { name: "Clear all filters" }),
		).not.toBeInTheDocument();
	},
};

/** The status decides the wording and whether Retry is offered; this screen only forwards it. */
export const LoadFailed: Story = {
	args: {
		reviews: undefined,
		error: { status: 500, detail: "The review index is unavailable." },
	},
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <ReviewRunsPage {...args} />,
	play: async ({ args, canvas, userEvent }) => {
		await canvas.findByText("Couldn't load reviews");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};
