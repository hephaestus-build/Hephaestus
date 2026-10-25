import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { ProfileReview } from "@/api/types.gen";
import { detailStackKey } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
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
		onOpenReview: fn(),
		onKindChange: fn(),
		onSinceChange: fn(),
		onStatusChange: fn(),
		onOrderChange: fn(),
		onTrack: fn(),
		total: profileReviews.length,
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
		await expect(screen.getAllByText("Latest review")).toHaveLength(1);
		await expect(screen.getAllByText("Requested")).toHaveLength(1);
		// The timeframe is the segmented range of Practices across the workspace.
		await expect(
			within(screen.getByRole("toolbar", { name: "Timeframe" })).getByRole("button", {
				name: "All time",
			}),
		).toHaveAttribute("aria-pressed", "true");
		await expect(screen.getByText("Review 2 of 2")).toBeVisible();
		// The latest review is the framed row, and says so in its top left corner, over its status.
		const latestRow = screen.getByText("Latest review").closest("tr");
		if (latestRow === null) {
			throw new Error("Expected the latest review's row.");
		}
		await expect(latestRow).toHaveAttribute("data-latest", "true");
		await expect(getComputedStyle(latestRow).outlineStyle).toBe("solid");
		const corner = screen.getByText("Latest review").getBoundingClientRect();
		const rowBox = latestRow.getBoundingClientRect();
		await expect(corner.top - rowBox.top).toBeLessThan(12);
		await expect(corner.left - rowBox.left).toBeLessThan(12);
		const row = within(latestRow);
		// The status stands over the review's time, in the same cell: no column of its own.
		const status = row.getByRole("button", { name: "Completed" }).getBoundingClientRect();
		const time = latestRow.querySelector("time")?.getBoundingClientRect();
		await expect(time).toBeDefined();
		await expect(status.bottom).toBeLessThanOrEqual((time?.top ?? 0) + 1);
		await expect(corner.bottom).toBeLessThanOrEqual(status.top);
		// "What it saw" counts the practices and their outcomes, and says nothing else.
		const table = within(screen.getByRole("table", { name: REVIEWS_OF_YOUR_WORK }));
		await expect(table.queryByRole("columnheader", { name: "Status" })).toBeNull();
		// The count of practices on its line, the outcomes on the line under it.
		const practices = row.getByText("3 practices").getBoundingClientRect();
		const met = row.getByText("1 met").getBoundingClientRect();
		await expect(met.top).toBeGreaterThanOrEqual(practices.bottom - 1);
		await expect(table.getByText("3 practices")).toBeVisible();
		await expect(table.getByText("1 could not determine")).toBeVisible();
		await expect(table.getByText("2 practices")).toBeVisible();
		await expect(table.getByText("2 not met")).toBeVisible();
		await expect(table.getByText("1 does not apply")).toBeVisible();
		await expect(table.getByText("No practices")).toBeVisible();
		await expect(table.queryByText("Thin controllers")).toBeNull();
		await expect(screen.queryByText("Nothing to improve")).toBeNull();

		// The feedback is read from its observation, not from the list.
		await expect(screen.queryByRole("link", { name: /^Read the feedback on the/u })).toBeNull();
		await expect(screen.queryByText("one piece of feedback")).toBeNull();

		// Another review is asked for from a work's latest review only: one button per work.
		const asks = screen.getAllByRole("button", { name: /^Request another review: /u });
		await expect(asks).toHaveLength(
			new Set(profileReviews.map((entry) => entry.review.item.id)).size,
		);
		const olderRun = screen
			.getAllByRole("row")
			.find((candidate) => within(candidate).queryByText("Review 1 of 2"));
		if (olderRun === undefined) {
			throw new Error("Expected the earlier review of the work reviewed twice.");
		}
		await expect(
			within(olderRun).queryByRole("button", { name: /^Request another review: /u }),
		).toBeNull();
		const [firstAsk] = asks;
		if (!firstAsk) {
			throw new Error("Expected a row the reader may ask about.");
		}
		await userEvent.click(firstAsk);
		await expect(args.onReviewNow).toHaveBeenCalledWith(openItem.id);

		const [newest] = screen.getAllByRole("button", {
			name: new RegExp(`^See the reviewed practices for ${openItem.label}, `, "u"),
		});
		if (!newest) {
			throw new Error("Expected a row link per review.");
		}
		// The row link stays on one line at 1280.
		const lineHeight = Number.parseFloat(getComputedStyle(newest).lineHeight);
		await expect(newest.getBoundingClientRect().height).toBeLessThan(lineHeight * 1.5);
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
	args: { kind: ["scm.issue"], since: "30d", total: 1 },
	play: async ({ args }) => {
		await settledDrawerPanel();
		// The admin Reviews tab's chips: the facet shows what it holds, the count what it kept.
		await expect(
			within(screen.getByRole("group", { name: "Filters" })).getAllByText(/Issues/u)[0],
		).toBeVisible();
		await expect(screen.getByText("1 review matches your filters.")).toBeVisible();
		await expect(
			within(screen.getByRole("toolbar", { name: "Timeframe" })).getByRole("button", {
				name: "Last 30 days",
			}),
		).toHaveAttribute("aria-pressed", "true");
		await expect(screen.queryByText("Latest review")).toBeNull();
		await userEvent.click(screen.getByRole("button", { name: /^Reset/u }));
		await expect(args.onKindChange).toHaveBeenCalledWith(undefined);
		await expect(args.onSinceChange).toHaveBeenCalledWith(undefined);
	},
};

/**
 * A work reviewed more than once offers Track on its rows, as the admin lists do: it narrows the
 * list to every review of that work. A work reviewed once offers none.
 */
export const TrackAWorksReviews: Story = {
	play: async ({ args }) => {
		await settledDrawerPanel();
		// Both row controls share one width and one height.
		const [firstAsk] = screen.getAllByRole("button", { name: /^Request another review: /u });
		const [firstTrack] = screen.getAllByRole("button", { name: /^Track this work: /u });
		if (firstAsk === undefined || firstTrack === undefined) {
			throw new Error("Expected both row controls.");
		}
		await expect(firstAsk.getBoundingClientRect().width).toBeCloseTo(
			firstTrack.getBoundingClientRect().width,
			0,
		);
		await expect(firstAsk.getBoundingClientRect().height).toBeCloseTo(
			firstTrack.getBoundingClientRect().height,
			0,
		);
		// Icons only: each button names itself on hover and focus, and the line over the table says
		// what both do.
		await expect(firstAsk).not.toHaveTextContent(/\S/u);
		await expect(firstTrack).not.toHaveTextContent(/\S/u);
		await userEvent.hover(firstTrack);
		const tip = await screen.findByText("Track this work", {
			selector: "[data-slot=tooltip-content]",
		});
		await expect(tip).toHaveAttribute("data-open");
		await userEvent.unhover(firstTrack);
		const table = screen.getByRole("table", { name: REVIEWS_OF_YOUR_WORK });
		const legend = screen.getByText(/show only its reviews/u).closest("p");
		await expect(legend).toHaveTextContent("Request another review");
		await expect(legend?.getBoundingClientRect().bottom ?? Infinity).toBeLessThanOrEqual(
			table.getBoundingClientRect().top,
		);
		const tracks = screen.getAllByRole("button", {
			name: new RegExp(`^Track this work: ${openItem.label}, `, "u"),
		});
		// Each run's Track has a name of its own: the review's time follows the work.
		await expect(new Set(tracks.map((track) => track.getAttribute("aria-label"))).size).toBe(
			tracks.length,
		);
		await expect(tracks).toHaveLength(2);
		const onceReviewed = profileReviews.find(
			(entry) =>
				profileReviews.filter((other) => other.review.item.id === entry.review.item.id).length ===
				1,
		);
		if (onceReviewed === undefined) {
			throw new Error("The fixture needs a work reviewed once.");
		}
		await expect(
			screen.queryByRole("button", {
				name: new RegExp(`^Track this work: ${onceReviewed.review.item.label}, `, "u"),
			}),
		).toBeNull();
		const [first] = tracks;
		if (first === undefined) {
			throw new Error("Expected a Track toggle.");
		}
		await userEvent.click(first);
		await expect(args.onTrack).toHaveBeenCalledWith(openItem);
	},
};

/** Tracking one work: its reviews in sequence, the bar says what is tracked, one press stops it. */
export const TrackingAWork: Story = {
	args: {
		feed: feedOf(profileReviews.filter((entry) => entry.review.item.id === openItem.id)),
		trackedItemId: openItem.id,
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		const bar = screen.getByRole("group", { name: "Selection" });
		await expect(within(bar).getByText("Tracking:")).toBeVisible();
		await expect(screen.getByText("Review 2 of 2")).toBeVisible();
		for (const toggle of screen.getAllByRole("button", {
			name: new RegExp(`^Track this work: ${openItem.label}, `, "u"),
		})) {
			await expect(toggle).toHaveAttribute("aria-pressed", "true");
		}
		await userEvent.click(within(bar).getByRole("button", { name: /^Stop tracking/u }));
		await expect(args.onTrack).toHaveBeenCalledWith(undefined);
	},
};

/**
 * A pressed Track keeps the primary fill in dark mode too, where the outline button's own pressed
 * ground would leave the icon nearly invisible.
 */
export const TrackingAWorkDark: Story = {
	...TrackingAWork,
	globals: { theme: "dark" },
	play: async ({ canvasElement }) => {
		await settledDrawerPanel();
		const [toggle] = screen.getAllByRole("button", {
			name: new RegExp(`^Track this work: ${openItem.label}, `, "u"),
		});
		if (toggle === undefined) {
			throw new Error("Expected a pressed Track toggle.");
		}
		await expect(toggle).toHaveAttribute("aria-pressed", "true");
		const primary = document.createElement("span");
		primary.className = "bg-primary";
		canvasElement.append(primary);
		await expect(getComputedStyle(toggle).backgroundColor).toBe(
			getComputedStyle(primary).backgroundColor,
		);
		primary.remove();
	},
};

/**
 * Tracking a work while another filter leaves none of its reviews: the bar says what it tracks in
 * words instead of a placeholder for a name nothing is loading.
 */
export const TrackingWithNoMatches: Story = {
	args: {
		feed: feedOf([]),
		trackedItemId: openItem.id,
		status: ["FAILED"],
		total: 0,
	},
	play: async () => {
		await settledDrawerPanel();
		const bar = screen.getByRole("group", { name: "Selection" });
		await expect(bar).toHaveTextContent("Tracking:a piece of work");
		await expect(bar.querySelector("[data-slot=skeleton]")).toBeNull();
		within(bar).getByRole("button", { name: "Stop tracking (a piece of work)" });
	},
};

/**
 * A status filter keeps each review's place on its work, which the server counts over every review
 * of the work: the latest review still offers Request another review, and an earlier one does not.
 */
export const StatusFilterKeepsTheLatestReview: Story = {
	args: {
		status: ["COMPLETE"],
		feed: feedOf([
			openProfileReview,
			{
				...openProfileReview,
				review: { ...openProfileReview.review, id: "00000000-0000-0000-0000-0000000003b1" },
				positionOnWork: 1,
			},
		]),
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("Review 2 of 2")).toBeVisible();
		await expect(screen.getByText("Review 1 of 2")).toBeVisible();
		const asks = screen.getAllByRole("button", { name: /^Request another review: /u });
		await expect(asks).toHaveLength(1);
		const latestRow = screen.getByText("Review 2 of 2").closest("tr");
		if (latestRow === null) {
			throw new Error("Expected the latest review's row.");
		}
		await expect(
			within(latestRow).getByRole("button", { name: /^Request another review: /u }),
		).toBe(asks[0]);
	},
};

/** A filter that hides a work's latest review leaves none of its rows to ask from. */
export const LatestReviewFilteredOut: Story = {
	args: {
		status: ["FAILED"],
		feed: feedOf([
			{
				...openProfileReview,
				review: { ...openProfileReview.review, status: "FAILED" },
				positionOnWork: 1,
			},
		]),
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("Review 1 of 2")).toBeVisible();
		await expect(screen.queryByRole("button", { name: /^Request another review: /u })).toBeNull();
		await expect(screen.queryByText(/of the work, from its latest review/u)).toBeNull();
	},
};

/**
 * Two runs of one work, and works with the same number in another repository and at another
 * provider: every button on the page still has a name of its own.
 */
export const ControlNamesStayUnique: Story = {
	args: {
		feed: feedOf([
			openProfileReview,
			{
				...openProfileReview,
				review: { ...openProfileReview.review, id: "00000000-0000-0000-0000-0000000003c1" },
				positionOnWork: 1,
			},
			{
				...openProfileReview,
				review: {
					...openProfileReview.review,
					id: "00000000-0000-0000-0000-0000000003c2",
					item: { ...openItem, id: "990001", container: "acme/other-service" },
				},
				positionOnWork: 1,
				totalOnWork: 1,
			},
			// The same number in the same repository's name at another provider.
			{
				...openProfileReview,
				review: {
					...openProfileReview.review,
					id: "00000000-0000-0000-0000-0000000003c3",
					item: { ...openItem, id: "990002", provider: "GITHUB" },
				},
				positionOnWork: 1,
				totalOnWork: 1,
			},
		]),
	},
	play: async () => {
		await settledDrawerPanel();
		const names = screen
			.getAllByRole("button", { name: /^(?:Track this work|Request another review): /u })
			.map((button) => button.getAttribute("aria-label"));
		await expect(names.length).toBeGreaterThan(2);
		await expect(new Set(names).size).toBe(names.length);
	},
};

/** The sort of the admin Reviews tab: newest first by default, oldest first on request. */
export const SortedOldestFirst: Story = {
	args: { order: "OLDEST" },
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByRole("combobox", { name: "Sort" })).toHaveTextContent("Oldest first");
		// Oldest first, the newest row is not the latest review either.
		await expect(screen.queryByText("Latest review")).toBeNull();
		await userEvent.click(screen.getByRole("combobox", { name: "Sort" }));
		await userEvent.click(await screen.findByRole("option", { name: "Newest first" }));
		await expect(args.onOrderChange).toHaveBeenCalledWith(undefined);
	},
};

/** The status filter names each status as the developer's review surfaces do. */
export const FilteredByStatus: Story = {
	args: {
		status: ["PARTIAL", "FAILED"],
		feed: feedOf([]),
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		const filters = within(screen.getByRole("group", { name: "Filters" }));
		await expect(filters.getAllByText(/Partial/u)[0]).toBeVisible();
		await expect(screen.getByText("No reviews match your filters.")).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: /^Reset/u }));
		await expect(args.onStatusChange).toHaveBeenCalledWith(undefined);
		await expect(args.onTrack).toHaveBeenCalledWith(undefined);
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
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		const openers = screen.getAllByRole("button", { name: /^See the reviewed practices /u });
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
		// The work's latest review asks; its earlier review offers no button to ask from.
		await expect(screen.getAllByRole("button", { name: /^Requesting review…: /u })).toHaveLength(1);
		await expect(
			screen.getAllByRole("button", { name: /^Request another review: /u }),
		).toHaveLength(new Set(profileReviews.map((entry) => entry.review.item.id)).size - 1);
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
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getAllByText("1 could not determine")).toHaveLength(2);
		await expect(screen.queryByText("Nothing to improve")).toBeNull();
	},
};

/** A review still going counts no practices yet; its status badge says it runs. */
export const WhileAReviewIsRunning: Story = {
	args: { feed: feedOf([runningProfileReview, ...profileReviews]) },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("No practices yet")).toBeVisible();
	},
};

/**
 * A partial review with nothing to improve in what it reached: its row counts what it reached, and
 * its status badge says it was partial.
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
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByText(/^\d+ met$/u)).toBeVisible();
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
	args: { feed: feedOf([]), kind: ["docs.document"] },
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
