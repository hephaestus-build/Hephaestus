import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, within } from "storybook/test";

import type { ListPracticeReviewsResponse } from "@/api/types.gen";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { StatefulPatch } from "@/stories/stateful";
import { expectNoPageOverflow } from "@/test/reflow";

import { REVIEW_PAGE_SIZE, type RunsSearch, runsQuery } from "./review-search";
import { ReviewRunsPage } from "./ReviewRunsPage";
import { reviewRuns } from "./story-mock-data";

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
	const rows = reviewRuns.filter(
		(run) =>
			(!query.status || run.status === query.status) &&
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

const meta = {
	title: "Workspace admin/Practice reviews/Reviews",
	component: ReviewRunsPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		search: {},
		onSearchChange: fn(),
		reviews: reviewsFor({}),
		isLoading: false,
		error: null,
		onRetry: fn(),
	},
	// The screen is controlled: with a frozen `search` prop every facet reads as dead. The rows
	// follow the search the same way the route's query would.
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, onSearchChange) => (
				<ReviewRunsPage
					{...args}
					search={search}
					onSearchChange={onSearchChange}
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
		const list = await canvas.findByRole("list", { name: /Practice reviews/ });
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
 * A strip draws every slot including the zeroes. Dropping them would start the next number at a
 * different x on each row and reflow the line under the reader whenever the poll refreshes an active
 * review, and it would make "no shortfalls" read the same as "shortfalls are not shown here".
 */
export const WhatEachReviewProduced: Story = {
	parameters: { viewport: { defaultViewport: "desktop" }, chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		await canvas.findByRole("list", { name: /Practice reviews/ });
		// A still-running review has no tally, so index 0 is the first review with output rather than
		// the first row.
		const observations = canvas.getAllByRole("list", { name: "Observations" })[0];
		const feedback = canvas.getAllByRole("list", { name: "Feedback" })[0];

		// A count and its word are two elements, so each pair is asserted on the strip, not per cell.
		for (const pair of ["1 strength", "2 improvements", "0 not applicable", "0 undetermined"]) {
			await expect(observations).toHaveTextContent(pair);
		}
		for (const pair of [
			"1 delivered",
			"2 withheld",
			"0 failed to deliver",
			// The bare stored state: this strip counts a run's units across every channel, so it cannot
			// name the moment one lane's prepared unit is waiting for.
			"0 prepared",
		]) {
			await expect(feedback).toHaveTextContent(pair);
		}

		for (const strip of canvas.getAllByRole("list", { name: "Observations" })) {
			await expect(within(strip).getAllByRole("listitem")).toHaveLength(4);
		}
		for (const strip of canvas.getAllByRole("list", { name: "Feedback" })) {
			await expect(within(strip).getAllByRole("listitem")).toHaveLength(5);
		}
	},
};

export const StatusFilter: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByRole("list", { name: /Practice reviews/ });
		await userEvent.click(canvas.getByRole("combobox"));
		const listbox = await screen.findByRole("listbox");
		await userEvent.click(within(listbox).getByRole("option", { name: /Failed/ }));
		await expect(canvas.getByRole("combobox")).toHaveTextContent("Failed");
		await canvas.findByText("1 review matches your filters.");

		await userEvent.click(canvas.getByRole("combobox"));
		await userEvent.click(
			within(await screen.findByRole("listbox")).getByRole("option", { name: /Cancelled/ }),
		);
		await canvas.findByText("No reviews found");
		await userEvent.click(canvas.getByRole("button", { name: "Clear all filters" }));
		await canvas.findByText("7 reviews.");
	},
};

/**
 * The range arrives through `args` rather than through the calendar: the state worth pinning is a
 * populated filtered list that then intersects with the status filter, which clicking two days on an
 * empty toolbar does not reach.
 */
export const FilterByRequestedDate: Story = {
	args: { search: { from: "2026-07-28", to: "2026-07-29" } },
	parameters: { viewport: { defaultViewport: "desktop" }, chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("3 reviews match your filters.");
		const list = canvas.getByRole("list", { name: /Practice reviews/ });
		within(list).getByRole("link", { name: /Retry webhook deliveries with backoff/ });
		within(list).getByRole("link", {
			name: "Cache the workspace member lookup on the review path",
		});
		await expect(
			within(list).queryByRole("link", {
				name: /Move invoice numbering behind the billing boundary/,
			}),
		).not.toBeInTheDocument();

		canvas.getByRole("button", { name: "Requested: Jul 28 – Jul 29, 2026" });

		// Adding a status intersects with the range rather than replacing it.
		await userEvent.click(canvas.getByRole("combobox"));
		await userEvent.click(
			within(await screen.findByRole("listbox")).getByRole("option", { name: /Completed/ }),
		);
		await canvas.findByText("2 reviews match your filters.");

		// The failed review was requested outside the window, so this intersection is empty.
		await userEvent.click(canvas.getByRole("combobox"));
		await userEvent.click(
			within(await screen.findByRole("listbox")).getByRole("option", { name: /Failed/ }),
		);
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
		const list = await canvas.findByRole("list", { name: /Practice reviews/ });
		await within(list).findByRole("link", { name: /Retry webhook deliveries with backoff/ });
		await expectNoPageOverflow();
	},
};

/**
 * `DateRangeFacet` keeps its badge at every width, unlike `FacetMultiSelect`, which collapses to
 * "N selected" — so the separate applied-pill row the sibling lists carry deliberately leaves the
 * date range out, rather than printing the same range twice.
 */
export const MobileAppliedDateRange: Story = {
	args: { search: { from: "2026-07-28", to: "2026-07-29" } },
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
		await expect(canvas.queryByRole("list", { name: /Practice reviews/ })).not.toBeInTheDocument();
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
