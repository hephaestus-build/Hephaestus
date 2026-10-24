import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import type { ReviewSummary } from "@/api/types.gen";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { ReviewRequestedNotice } from "@/components/practice-trace/ReviewRequestedNotice";
import { formatWeekdayDay } from "@/lib/dates";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";
import { withStandardPage, withWidePage } from "@/stories/decorators";
import { expectPlaceholderRows } from "@/stories/loading-more";
import { settledPopup } from "@/stories/overlay";
import { loadedList, narrowedList } from "@/stories/paged-list";
import { expectNoPageOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";
import { STORY_NOW } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";

import { reviews } from "./fixtures";
import { REVIEWABLE_ON_DEMAND } from "./job-utils";
import { reviewLevel } from "./review-levels";
import { REVIEW_PAGE_SIZE, type ReviewsSearch, reviewsQuery } from "./review-search";
import { type ReviewRequestActions, ReviewsPage } from "./ReviewsPage";
import { STORY_GROUPS, STORY_PEOPLE, STORY_PRACTICES } from "./story-scope-sources";

const loadMoreReviews = fn();
const retryReviews = fn();
const REQUESTED_REVIEW_ID = "0f2b7c1e-9a3d-4c5b-8e1f-2d6a7b8c9d01";

function storyRequestActions(): ReviewRequestActions {
	return {
		canRequest: (item) => REVIEWABLE_ON_DEMAND.has(item.kind),
		onRequest: fn(),
		isRequesting: () => false,
	};
}

const LIST = { name: "Practice reviews, newest first" } as const;

const dayOf = (review: ReviewSummary) => formatWeekdayDay(review.createdAt, new Date(STORY_NOW));

function reviewsFor(
	list: PagedListState<ReviewSummary>,
	search: ReviewsSearch,
): PagedListState<ReviewSummary> {
	const query = reviewsQuery(search, REVIEW_PAGE_SIZE);
	return narrowedList(
		list,
		(review) =>
			(query.status === undefined || query.status.includes(review.status)) &&
			(!query.from || review.createdAt >= query.from) &&
			(!query.to || review.createdAt < query.to),
		REVIEW_PAGE_SIZE,
	);
}

const meta = {
	component: ReviewsPage,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [320, 768, 1440] },
	},
	decorators: [withWidePage, withStandardPage],
	tags: ["autodocs"],
	args: {
		search: { status: undefined },
		onSearchChange: fn(),
		reviews: loadedList(reviews),
		groups: STORY_GROUPS,
		practices: STORY_PRACTICES,
		people: STORY_PEOPLE,
	},
	render: (args) => (
		<StatefulPatch initial={args.search}>
			{(search, patch) => (
				<ReviewsPage
					{...args}
					search={search}
					onSearchChange={(next) => {
						patch(next);
						args.onSearchChange(next);
					}}
					reviews={reviewsFor(args.reviews, search)}
				/>
			)}
		</StatefulPatch>
	),
} satisfies Meta<typeof ReviewsPage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	parameters: { viewport: { defaultViewport: "desktop" } },
	play: async ({ canvas }) => {
		const table = await canvas.findByRole("table", LIST);
		const days = [...new Set(reviews.map(dayOf))];
		await expect(days.length).toBeGreaterThan(1);
		for (const day of days) {
			within(table).getByText(day);
		}
		await expect(within(table).getAllByText("Complete").length).toBeGreaterThan(0);
		await expect(within(table).queryByRole("button", { name: "Complete" })).not.toBeInTheDocument();
		await expect(
			within(table).queryByRole("link", { name: /opens in a new tab/u }),
		).not.toBeInTheDocument();
		await expect(
			levelsOpenedBy(within(table).getByRole("link", { name: /^Open review of #1423,/u })),
		).toEqual(["review:11111111-1111-1111-1111-111111111111"]);
	},
};

export const StatusFilter: Story = {
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("combobox", { name: /^Status/u }));
		await settledPopup();
		const listbox = screen.getByRole("listbox", { name: "Status options" });
		await userEvent.click(within(listbox).getByRole("option", { name: /Failed/u }));
		await userEvent.keyboard("{Escape}");
		await waitFor(() => {
			void expect(screen.queryByRole("listbox", { name: "Status options" })).toBeNull();
		});
		await expect(args.onSearchChange).toHaveBeenLastCalledWith({ status: ["FAILED"] });
		const table = await canvas.findByRole("table", LIST);
		await expect(within(table).queryByText("Complete")).not.toBeInTheDocument();
	},
};

export const RequestReviewFromARow: Story = {
	args: { requestReview: storyRequestActions() },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const table = await canvas.findByRole("table", LIST);
		await expect(
			within(table).getAllByRole("button", { name: /^Request review of /u }).length,
		).toBeGreaterThan(0);
	},
};

/**
 * The (i) over the actions column leads each term with its button's icon, so the reader sees which
 * button does what; the icon is decoration, the term and its words stay what a screen reader hears.
 */
export const RowActionsLegend: Story = {
	args: { requestReview: storyRequestActions() },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, userEvent }) => {
		const table = await canvas.findByRole("table", LIST);
		const buttons = {
			Track: within(table).getAllByRole("button", { name: /^Track /u })[0],
			Trace: within(table).getAllByRole("link", { name: /^Trace /u })[0],
			"Request review": within(table).getAllByRole("button", { name: /^Request review of /u })[0],
		};
		await userEvent.click(within(table).getByRole("button", { name: "About the row actions" }));
		const legend = await screen.findByRole("dialog", { name: "About the row actions" });
		for (const [term, button] of Object.entries(buttons)) {
			const line = within(legend).getByText(term, { selector: "strong" }).closest("p");
			const icon = line?.querySelector("svg");
			await expect(icon).toHaveAttribute("aria-hidden", "true");
			await expect(icon?.getAttribute("class")).toContain(
				button?.querySelector("svg")?.getAttribute("class"),
			);
		}
	},
};

/**
 * A row's ask was accepted before the list reads the new review, or while a filter keeps it out:
 * the way to it stands above the table all the same.
 */
export const WithRequestedReview: Story = {
	args: {
		requestReview: storyRequestActions(),
		requestOutcome: (
			<ReviewRequestedNotice
				review={<DetailStackLink entry={reviewLevel(REQUESTED_REVIEW_ID)} single />}
			/>
		),
	},
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const table = await canvas.findByRole("table", LIST);
		const note = within(canvas.getByRole("note"));
		await expect(
			levelsOpenedBy(note.getByRole("link", { name: "View requested review" })),
		).toStrictEqual([`review:${REQUESTED_REVIEW_ID}`]);
		// No row opens it: the note is the way there.
		await expect(
			within(table)
				.queryAllByRole("link")
				.flatMap((link) => levelsOpenedBy(link)),
		).not.toContain(`review:${REQUESTED_REVIEW_ID}`);
	},
};

export const Mobile: Story = {
	parameters: {
		chromatic: { disableSnapshot: true },
		viewport: { defaultViewport: "reflow" },
	},
	play: async ({ canvas }) => {
		await canvas.findByRole("table", LIST);
		await expectNoPageOverflow();
	},
};

export const Loading: Story = {
	args: { reviews: { status: "loading" } },
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <ReviewsPage {...args} />,
	play: async ({ canvas }) => {
		const table = await canvas.findByRole("table", LIST);
		await expect(table).toHaveAttribute("aria-busy", "true");
		await expect(within(table).queryByRole("link")).not.toBeInTheDocument();
	},
};

export const NoReviewsYet: Story = {
	args: { reviews: loadedList([]) },
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <ReviewsPage {...args} />,
	play: async ({ canvas }) => {
		await canvas.findByText("No reviews yet");
		await expect(
			canvas.queryByRole("button", { name: "Clear all filters" }),
		).not.toBeInTheDocument();
	},
};

export const NoReviewsMatch: Story = {
	args: { reviews: loadedList([]), search: { status: ["CANCELLED"] } },
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <ReviewsPage {...args} />,
	play: async ({ canvas }) => {
		await canvas.findByText("No reviews match these filters");
		canvas.getByRole("button", { name: "Clear all filters" });
	},
};

export const LoadFailed: Story = {
	args: {
		reviews: {
			status: "error",
			error: { status: 500, detail: "The review index is unavailable." },
			onRetry: retryReviews,
		},
	},
	parameters: { chromatic: { viewports: [1440] } },
	render: (args) => <ReviewsPage {...args} />,
	play: async ({ canvas, userEvent }) => {
		await canvas.findByText("We could not load reviews");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(retryReviews).toHaveBeenCalled();
	},
};

export const LoadsMoreAtTheEnd: Story = {
	args: { reviews: loadedList(reviews, { hasMore: true, onLoadMore: loadMoreReviews }) },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas }) => {
		const more = await canvas.findByRole("button", { name: "Show more reviews" });
		more.scrollIntoView();
		await waitFor(async () => expect(loadMoreReviews).toHaveBeenCalled());
	},
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadingMore: Story = {
	args: { reviews: loadedList(reviews, { hasMore: true, isLoadingMore: true }) },
	parameters: { chromatic: { viewports: [1440] } },
	play: async ({ canvas, canvasElement }) => {
		await expectPlaceholderRows(canvasElement, canvas.getByRole("table", LIST));
	},
};
