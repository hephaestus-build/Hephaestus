import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { ProfileReview } from "@/api/types.gen";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { reviewPositionsOnItem } from "@/components/practice-trace/review-groups";
import { ReviewRequestedNotice } from "@/components/practice-trace/ReviewRequestedNotice";
import type { ReviewFeedState } from "@/components/profile/reviews";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import {
	openProfileReview,
	profileReviews,
	runningProfileReview,
} from "@/stories/profile-reviews-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";
import { levelsOpenedBy } from "@/test/detail-stack";

import {
	REVIEW_SELECTION_CLEARED,
	reviewLevel,
	REVIEWS_LEVEL,
	REVIEWS_OF_YOUR_WORK,
} from "./practice-profile-search";
import { ProfileReviewsLevel } from "./ProfileReviewsLevel";

const feedOf = (
	reviews: ProfileReview[],
	more: { hasMore?: boolean; isLoadingMore?: boolean; loadMoreError?: unknown } = {},
): ReviewFeedState => ({
	status: "ready",
	reviews,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
	...more,
});

/** The paging press, a spy of each story's own since `feedOf` makes one per feed. */
function onLoadMoreOf(feed: ReviewFeedState) {
	if (feed.status !== "ready") {
		throw new Error("Expected a loaded feed.");
	}
	return feed.onLoadMore;
}

const openItem = openProfileReview.review.item;
const REQUESTED_REVIEW_ID = "0f2b7c1e-9a3d-4c5b-8e1f-2d6a7b8c9d01";

/** Opened from the header's chip, so every story mounts it as a drawer's first level. */
const meta = {
	component: ProfileReviewsLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Practice profile", depth: 0 }], onClose: fn() },
		feed: feedOf(profileReviews),
		positions: reviewPositionsOnItem(profileReviews.map((entry) => entry.review)),
		onOpenReview: fn(),
		onKindChange: fn(),
		onSinceChange: fn(),
		onReviewNow: fn(),
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<DetailDrawerStack stack={[REVIEWS_LEVEL]} onClose={fn()}>
			{(_entry, level) => <ProfileReviewsLevel {...args} nested={level.nested} />}
		</DetailDrawerStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ProfileReviewsLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledDrawerPanel();
		await expect(screen.getAllByText("Latest")).toHaveLength(1);
		await expect(screen.getAllByText("Requested")).toHaveLength(1);
		await expect(screen.getByLabelText("Timeframe")).toHaveTextContent("All time");
		await expect(screen.getByText("Review 2")).toBeVisible();
		await expect(screen.getByText("Thin controllers")).toBeVisible();
		await expect(screen.getByText("one practice met, one could not be determined")).toBeVisible();
		await expect(screen.getByText("Explain each change, Keep the diff reviewable")).toBeVisible();
		await expect(screen.getByText("one practice did not apply")).toBeVisible();
		await expect(screen.getByText("Stopped before it finished")).toBeVisible();
		await expect(screen.getAllByText("Nothing to improve")).toHaveLength(2);

		const feedbackLinks = screen.getAllByRole("link", { name: /^Read the feedback on the/u });
		await expect(feedbackLinks).toHaveLength(1);
		await expect(feedbackLinks[0]).toHaveAttribute(
			"href",
			openProfileReview.feedback[0]?.copies[0]?.externalUrl,
		);
		await expect(screen.getByText("one piece of feedback")).toBeVisible();

		const asks = screen.getAllByRole("button", { name: /^Request review: /u });
		await expect(asks).toHaveLength(profileReviews.length);
		const [firstAsk] = asks;
		if (!firstAsk) {
			throw new Error("Expected a row the reader may ask about.");
		}
		await userEvent.click(firstAsk);
		await expect(args.onReviewNow).toHaveBeenCalledWith(openItem.id);

		const [newest] = screen.getAllByRole("button", {
			name: new RegExp(`^Open review of ${openItem.label}, `, "u"),
		});
		if (!newest) {
			throw new Error("Expected a row link per review.");
		}
		await userEvent.click(newest);
		await expect(args.onOpenReview).toHaveBeenCalledWith(openProfileReview.review.id);
		await expectNoPanelOverflow(panel);
	},
};

/**
 * Both filters show what they hold, and one reset clears both. The newest row of a narrowed list is
 * not the latest review, so none wears the tag.
 */
export const Filtered: Story = {
	args: { kind: "scm.issue", since: "30d" },
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByLabelText("Show")).toHaveTextContent("Issues");
		await expect(screen.getByLabelText("Timeframe")).toHaveTextContent("Last 30 days");
		await expect(screen.queryByText("Latest")).toBeNull();
		await userEvent.click(screen.getByRole("button", { name: /^Reset/u }));
		await expect(args.onKindChange).toHaveBeenCalledWith(undefined);
		await expect(args.onSinceChange).toHaveBeenCalledWith(undefined);
	},
};

/** Earlier reviews are a press away; the frame scrolls in the body rather than clipping. */
export const ManyReviews: Story = {
	args: {
		feed: feedOf(
			Array.from({ length: 30 }, (_, index) => {
				const entry = profileReviews[index % profileReviews.length] ?? openProfileReview;
				return {
					...entry,
					review: {
						...entry.review,
						id: `00000000-0000-0000-0000-${String(index).padStart(12, "0")}`,
						createdAt: daysBefore(index),
						completedAt: daysBefore(index),
					},
				};
			}),
			{ hasMore: true },
		),
		positions: undefined,
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		const openers = screen.getAllByRole("button", { name: /^Open review /u });
		await expect(openers).toHaveLength(30);
		const frame = openers.at(-1)?.closest("[data-slot='table-container']")?.parentElement;
		if (!frame) {
			throw new Error("Expected the table to stand in its own frame.");
		}
		await expect(frame.scrollHeight).toBeLessThanOrEqual(frame.clientHeight + 1);
		await userEvent.click(screen.getByRole("button", { name: "View earlier reviews" }));
		await expect(onLoadMoreOf(args.feed)).toHaveBeenCalled();
	},
};

/** The next page's placeholder rows continue the table in its own columns while the table is busy. */
export const LoadingEarlierReviews: Story = {
	args: { feed: feedOf(profileReviews, { hasMore: true, isLoadingMore: true }) },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("button", { name: "Loading…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		const table = screen.getByRole("table", { name: REVIEWS_OF_YOUR_WORK });
		await expect(table).toHaveAttribute("aria-busy", "true");
		const placeholders = table.querySelectorAll("tbody [data-loading-row]");
		await expect(placeholders).toHaveLength(3);
		// Every placeholder row has a cell per column, so the rows that replace it do not move the table.
		const columns = table.querySelectorAll("thead th").length;
		for (const row of placeholders) {
			await expect(row.querySelectorAll("td")).toHaveLength(columns);
		}
	},
};

/** A page that did not arrive keeps the rows already read and offers the press back. */
export const EarlierReviewsFailed: Story = {
	args: {
		feed: feedOf(profileReviews, { hasMore: true, loadMoreError: new Error("offline") }),
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByText("We could not load earlier reviews.")).toBeVisible();
		await expect(onLoadMoreOf(args.feed)).not.toHaveBeenCalled();
		await userEvent.click(screen.getByRole("button", { name: "Retry" }));
		await expect(onLoadMoreOf(args.feed)).toHaveBeenCalledOnce();
	},
};

export const AskingForAReview: Story = {
	args: { requesting: openItem.id },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getAllByText("Requesting review…")).toHaveLength(2);
		await expect(screen.getAllByText("Request review")).toHaveLength(profileReviews.length - 2);
	},
};

export const CouldNotDetermineResults: Story = {
	args: {
		feed: feedOf([
			{
				...openProfileReview,
				review: { ...openProfileReview.review, id: "00000000-0000-0000-0000-0000000002b1" },
				results: openProfileReview.results.filter((result) => result.outcome !== "NOT_MET"),
			},
			{
				...openProfileReview,
				review: {
					...openProfileReview.review,
					id: "00000000-0000-0000-0000-0000000002b2",
					createdAt: daysBefore(3),
					completedAt: daysBefore(3),
				},
				results: openProfileReview.results.filter(
					(result) => result.outcome === "COULD_NOT_DETERMINE",
				),
			},
		]),
		positions: undefined,
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getAllByText("Some practices could not be determined")).toHaveLength(2);
		await expect(screen.queryByText("Nothing to improve")).toBeNull();
	},
};

/** A review still going says so in words and counts nothing yet. */
export const WhileAReviewIsRunning: Story = {
	args: { feed: feedOf([runningProfileReview, ...profileReviews]) },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("Still running")).toBeVisible();
		await expect(screen.getByText("Observations appear as it finishes")).toBeVisible();
	},
};

/**
 * A partial review with nothing to improve in what it reached. It may have run to the end with a
 * practice failed or withheld, so its row scopes the lead instead of saying it stopped.
 */
export const PartialReview: Story = {
	args: {
		feed: feedOf([
			{
				...openProfileReview,
				review: { ...openProfileReview.review, status: "PARTIAL" },
				results: openProfileReview.results.filter(
					(result) => result.outcome === "MET" || result.outcome === "DOES_NOT_APPLY",
				),
			},
		]),
		positions: undefined,
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByText("Nothing to improve in what it reached")).toBeVisible();
		// The status is the label the workspace admin's list of reviews shows, with its sentence.
		const badge = screen.getByRole("button", { name: "Partial" });
		await expect(badge).toHaveAccessibleDescription(
			"The review’s observations cover only the work it reached.",
		);
		// A tap on it opens the sentence, not the review.
		await userEvent.pointer({ keys: "[TouchA]", target: badge });
		await waitFor(async () => {
			await expect(badge).toHaveAttribute("aria-expanded", "true");
		});
		await expect(args.onOpenReview).not.toHaveBeenCalled();
		await expect(screen.queryByText("Stopped before it finished")).toBeNull();
	},
};

/**
 * A row's ask was accepted, and the list does not show the new review yet: the way to it opens the
 * review over this list.
 */
export const WithRequestedReview: Story = {
	parameters: { router: { initialUrl: `/?detail=${detailStackKey(REVIEWS_LEVEL)}` } },
	args: {
		requestOutcome: (
			<ReviewRequestedNotice
				review={
					<DetailStackLink
						entry={reviewLevel(REQUESTED_REVIEW_ID)}
						levelSearch={REVIEW_SELECTION_CLEARED}
					/>
				}
			/>
		),
	},
	play: async () => {
		await settledDrawerPanel();
		const link = within(screen.getByRole("note")).getByRole("link", {
			name: "View requested review",
		});
		await expect(levelsOpenedBy(link)).toStrictEqual([
			detailStackKey(REVIEWS_LEVEL),
			`review:${REQUESTED_REVIEW_ID}`,
		]);
		await expect(link.getAttribute("href")).not.toMatch(/admin/u);
	},
};

/** Nothing has recorded anything about the reader's work yet: the list says what will land here. */
export const Empty: Story = {
	args: { feed: feedOf([]) },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("No reviews of your work yet")).toBeVisible();
	},
};

/** A filter that keeps nothing says so, rather than claim no review of the work exists. */
export const FilteredEmpty: Story = {
	args: { feed: feedOf([]), kind: "docs.document" },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("No reviews match your filters.")).toBeVisible();
		await expect(screen.queryByText("No reviews of your work yet")).toBeNull();
	},
};

export const Loading: Story = {
	args: { feed: { status: "loading" } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("table", { name: "Reviews of your work" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
	},
};

export const Failed: Story = {
	args: { feed: { status: "error", error: new Error("offline"), onRetry: fn() } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("We could not load reviews of your work")).toBeVisible();
		await expect(screen.queryByRole("table")).toBeNull();
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};
