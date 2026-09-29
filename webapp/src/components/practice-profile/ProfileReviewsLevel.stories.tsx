import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { ProfileReviewRun } from "@/api/types.gen";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { ReviewRunFeedState } from "@/components/profile/review-runs";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import {
	openProfileReviewRun,
	profileReviewRuns,
	runningProfileReviewRun,
} from "@/stories/profile-review-runs-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { REVIEWS_LEVEL } from "./practice-profile-search";
import { ProfileReviewsLevel } from "./ProfileReviewsLevel";
import { runPositionsOnWork } from "./review-run-groups";

const feedOf = (
	runs: ProfileReviewRun[],
	more: { hasMore?: boolean; isLoadingMore?: boolean; loadMoreError?: unknown } = {},
): ReviewRunFeedState<ProfileReviewRun> => ({
	status: "ready",
	runs,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
	...more,
});

/** The paging press, a spy of each story's own since `feedOf` makes one per feed. */
function onLoadMoreOf(feed: ReviewRunFeedState<ProfileReviewRun>) {
	if (feed.status !== "ready") {
		throw new Error("Expected a loaded feed.");
	}
	return feed.onLoadMore;
}

/** Opened from the header's chip, so every story mounts it as a drawer's first level. */
const meta = {
	component: ProfileReviewsLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Practice profile", depth: 0 }], onClose: fn() },
		feed: feedOf(profileReviewRuns),
		positions: runPositionsOnWork(profileReviewRuns),
		onOpenReview: fn(),
		onKindChange: fn(),
		onSinceChange: fn(),
		onReviewNow: fn(),
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<DetailDrawerStack stack={[REVIEWS_LEVEL]} size="detailWide" onClose={fn()}>
			{(_entry, level) => <ProfileReviewsLevel {...args} nested={level.nested} />}
		</DetailDrawerStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ProfileReviewsLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Newest first under a heading per day. A row leads with the practices that slipped, by name, and
 * under it counts the practices that held, did not apply or stayed undecided, and how many practices
 * the review reached. "Review this now" is offered only on the rows whose work
 * the reader may ask about, and feedback is linked where the provider's comment can be addressed
 * and counted elsewhere.
 */
export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledDrawerPanel();
		await expect(screen.getAllByText("Latest")).toHaveLength(1);
		await expect(screen.getAllByText("Requested")).toHaveLength(1);
		await expect(screen.getByLabelText("Timeframe")).toHaveTextContent("All time");
		await expect(screen.getByText("2nd review")).toBeVisible();
		await expect(
			screen.getByText("one practice held, one undecided; three practices reached"),
		).toBeVisible();
		await expect(
			screen.getByText("one practice held, five did not apply; nine practices reached"),
		).toBeVisible();
		// A stopped review wrote no reach, so the line says only what it decided.
		await expect(screen.getByText("one practice held")).toBeVisible();
		await expect(
			screen.getByText("Explain each change, Keep the diff reviewable +1 more"),
		).toBeVisible();
		await expect(screen.getByText("Stopped before it finished")).toBeVisible();
		await expect(screen.getAllByText("Nothing to improve")).toHaveLength(2);

		const feedbackLinks = screen.getAllByRole("link", { name: /^Read the feedback on the/u });
		await expect(feedbackLinks).toHaveLength(1);
		await expect(feedbackLinks[0]).toHaveAttribute("href", openProfileReviewRun.feedbackUrl);
		await expect(screen.getByText("two pieces of feedback")).toBeVisible();
		await expect(screen.getByText("one piece of feedback")).toBeVisible();

		const asks = screen.getAllByRole("button", { name: /^Review this now: /u });
		await expect(asks).toHaveLength(3);
		const [firstAsk] = asks;
		if (!firstAsk) {
			throw new Error("Expected a row the reader may ask about.");
		}
		await userEvent.click(firstAsk);
		await expect(args.onReviewNow).toHaveBeenCalledWith(openProfileReviewRun.reviewedWork);

		// The row's link names the work as well as the time, so two rows of one day read apart.
		const [newest] = screen.getAllByRole("button", {
			name: new RegExp(`^Open review of ${openProfileReviewRun.reviewedWork.label}, `, "u"),
		});
		if (!newest) {
			throw new Error("Expected a row link per review.");
		}
		await userEvent.click(newest);
		await expect(args.onOpenReview).toHaveBeenCalledWith(openProfileReviewRun.reviewId);
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
			Array.from({ length: 30 }, (_, index) => ({
				...(profileReviewRuns[index % profileReviewRuns.length] ?? openProfileReviewRun),
				reviewId: `00000000-0000-0000-0000-${String(index).padStart(12, "0")}`,
				reviewedAt: daysBefore(index),
			})),
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
		await expect(onLoadMoreOf(args.feed)).toHaveBeenCalledOnce();
	},
};

export const LoadingEarlierReviews: Story = {
	args: { feed: feedOf(profileReviewRuns, { hasMore: true, isLoadingMore: true }) },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("button", { name: "Loading…" })).toBeDisabled();
	},
};

/** A page that did not arrive keeps the rows already read and offers the press back. */
export const EarlierReviewsFailed: Story = {
	args: {
		feed: feedOf(profileReviewRuns, { hasMore: true, loadMoreError: new Error("offline") }),
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByText("Could not load earlier reviews.")).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: "View earlier reviews" }));
		await expect(onLoadMoreOf(args.feed)).toHaveBeenCalledOnce();
	},
};

/** An ask in flight shows on every row of that work, and only there. */
export const AskingForAReview: Story = {
	args: { requesting: openProfileReviewRun.reviewedWork },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getAllByText("Asking…")).toHaveLength(2);
		await expect(screen.getAllByText("Review this now")).toHaveLength(1);
	},
};

/** A review still going says so in words and counts nothing yet. */
export const WhileAReviewIsRunning: Story = {
	args: { feed: feedOf([runningProfileReviewRun, ...profileReviewRuns]) },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("Still running")).toBeVisible();
		await expect(screen.getByText("Results appear as it finishes")).toBeVisible();
	},
};

/** Nothing has recorded anything about the reader's work yet: the list says what will land here. */
export const Empty: Story = {
	args: { feed: feedOf([]) },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("No review of your work yet.")).toBeVisible();
	},
};

/** A filter that keeps nothing says so, rather than claim no review of the work exists. */
export const FilteredEmpty: Story = {
	args: { feed: feedOf([]), kind: "docs.document" },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("No review here matches your filters.")).toBeVisible();
		await expect(screen.queryByText("No review of your work yet.")).toBeNull();
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
		await expect(screen.getByText("Could not load reviews of your work")).toBeVisible();
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
