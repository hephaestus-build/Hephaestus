import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import {
	openProfileReviewRun,
	profileReviewRuns,
	runningProfileReviewRun,
} from "@/stories/profile-review-runs-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { REVIEW_RUNS_LEVEL } from "./practice-profile-search";
import { ReviewRunsLevel } from "./ReviewRunsLevel";

/**
 * The level opens from the header's chip, so every story mounts it as the first level of a real
 * drawer over a real page.
 */
const meta = {
	component: ReviewRunsLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: { behind: [{ label: "Practice profile", depth: 0 }], onClose: fn() },
		runs: profileReviewRuns,
		onOpenRun: fn(),
		onKindChange: fn(),
		onSinceChange: fn(),
		onReviewNow: fn(),
		onLoadMore: fn(),
		skeletonRows: 5,
		isLoading: false,
		onRetry: fn(),
	},
	argTypes: {
		path: { control: false },
	},
	render: (args) => (
		<DetailDrawerStack stack={[REVIEW_RUNS_LEVEL]} size="detailWide" onClose={fn()}>
			{(_entry, level) => <ReviewRunsLevel {...args} nested={level.nested} />}
		</DetailDrawerStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ReviewRunsLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getByText("Reviews of your work")).toBeVisible();
		await expect(canvas.getByText(/Only you can see this/u)).toBeVisible();

		// The newest run is marked, and a run somebody asked for says so.
		await expect(canvas.getAllByText("Latest")).toHaveLength(1);
		await expect(canvas.getAllByText("Requested by hand")).toHaveLength(1);
		// And the two do not read alike: the newest run carries the filled accent, which is what
		// makes it stand out, rather than the row under it being tinted.
		const [latest] = canvas.getAllByText("Latest");
		const [byHand] = canvas.getAllByText("Requested by hand");
		if (!latest || !byHand) {
			throw new Error("Expected both run tags.");
		}
		await expect(getComputedStyle(latest).backgroundColor).not.toBe(
			getComputedStyle(byHand).backgroundColor,
		);
		await expect(latest.closest("tr")).toHaveClass(byHand.closest("tr")?.className ?? "");
		// The list rests on every run there is; narrowing it is the reader's to ask for.
		await expect(canvas.getByLabelText("Timeframe")).toHaveTextContent("All time");

		// The runs fall on their own days, and a row carries the time of day alone.
		await expect(canvas.getAllByRole("columnheader", { name: "Reviewed" })).toHaveLength(1);

		// Every heading names its day outright. A page left open crosses midnight, and "Today" over
		// the rows would then head the wrong day without anything moving on screen.
		const headings = [...panel.querySelectorAll("th[scope='colgroup']")];
		await expect(headings.length).toBeGreaterThan(0);
		for (const heading of headings) {
			await expect(heading.textContent).toMatch(
				/^(?:Mon|Tues|Wednes|Thurs|Fri|Satur|Sun)day, \d{1,2} [A-Z][a-z]+(?: \d{4})?$/u,
			);
		}
		await expect(canvas.queryByText("Today")).toBeNull();
		await expect(canvas.queryByText("Yesterday")).toBeNull();

		// The second review of pull request 902 says so under the work; the first one of a work says
		// nothing, since every piece of work has one.
		await expect(canvas.getByText("2nd review")).toBeVisible();
		await expect(canvas.queryByText("1st review")).toBeNull();

		// A row leads with what slipped, by name, and counts what it got through under it. The
		// counts explain each other: held, did not apply and what slipped add up to what was reached,
		// which is the number the row ends on.
		await expect(canvas.getByText("Keep the diff reviewable")).toBeVisible();
		const { strengths, problems, notApplicable, undetermined } = openProfileReviewRun.observations;
		await expect(strengths + problems + notApplicable + undetermined).toBe(
			openProfileReviewRun.practicesEvaluated,
		);
		await expect(canvas.getByText("2 held, 1 did not apply, 4 reached")).toBeVisible();
		// Two names and the rest counted, where more practices slipped than a row names.
		await expect(
			canvas.getByText(/Explain each change, Keep the diff reviewable \+1 more/u),
		).toBeVisible();
		// A run with nothing against the reader says so, and nothing says "0" anywhere.
		await expect(canvas.getAllByText("Nothing to improve").length).toBeGreaterThan(0);
		await expect(canvas.queryByText(/\b0 /u)).toBeNull();

		// The duration is gone from the list entirely, and no fact on a row is joined by a middle dot.
		await expect(canvas.queryByText(/41 s/u)).toBeNull();
		await expect(canvas.queryByText(/·/u)).toBeNull();

		// The work is a link to itself, beside the row's own opener.
		await expect(canvas.getAllByRole("link", { name: /^#902/u }).length).toBeGreaterThan(0);

		const [newest] = canvas.getAllByRole("button", { name: /^Open run /u });
		if (!newest) {
			throw new Error("Expected a row link per run.");
		}
		await userEvent.click(newest);
		await expect(args.onOpenRun).toHaveBeenCalledWith(openProfileReviewRun.reviewId);
		await expectNoPanelOverflow(panel);
	},
};

/**
 * The kind filter is one of the level's two filters, and a chosen kind can be cleared again: an
 * include with no exclude is a one-way door.
 */
export const FilteredToOneKind: Story = {
	args: { kind: "scm.issue" },
	play: async ({ args }) => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getByLabelText("Show")).toHaveTextContent("Issues");
		await userEvent.click(canvas.getByRole("button", { name: /^Reset/u }));
		await expect(args.onKindChange).toHaveBeenCalledWith(undefined);
	},
};

/**
 * The list is paged, so it rests on every run there is; a timeframe narrows it and can be cleared
 * again, because an include with no exclude is a one-way door.
 */
export const NarrowedToATimeframe: Story = {
	args: { since: "30d" },
	play: async ({ args }) => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getByLabelText("Timeframe")).toHaveTextContent("Last 30 days");
		await userEvent.click(canvas.getByRole("button", { name: /^Reset/u }));
		await expect(args.onSinceChange).toHaveBeenCalledWith(undefined);
	},
};

/**
 * A long list scrolls to its end inside the level's body. The frame around the table is
 * `overflow-hidden`, which zeroes its automatic minimum size as a flex item, so without a `shrink-0`
 * it is squashed to fit and clips its last rows with nothing left to scroll.
 */
export const ManyRuns: Story = {
	args: {
		// Strictly one run per day, newest first, so every heading over the rows is its own.
		runs: Array.from({ length: 30 }, (_, index) => ({
			...(profileReviewRuns[index % profileReviewRuns.length] ?? openProfileReviewRun),
			reviewId: `00000000-0000-0000-0000-${String(index).padStart(12, "0")}`,
			reviewedAt: daysBefore(index),
		})),
		hasMore: true,
	},
	play: async ({ args }) => {
		await settledDrawerPanel();
		const canvas = screen;
		const openers = canvas.getAllByRole("button", { name: /^Open run /u });
		await expect(openers).toHaveLength(30);
		const last = openers.at(-1);
		if (!last) {
			throw new Error("Expected a row link per run.");
		}
		const frame = last.closest("[data-slot='table-container']")?.parentElement;
		if (!frame) {
			throw new Error("Expected the table to stand in its own frame.");
		}
		// The frame hides nothing of its own: it is `overflow-hidden`, which zeroes its automatic
		// minimum size as a flex item, so without a `shrink-0` the level's column squashes it to fit
		// and its last rows are clipped with nothing left to scroll them back into view.
		await expect(frame.scrollHeight).toBeLessThanOrEqual(frame.clientHeight + 1);
		last.scrollIntoView();
		await expect(last).toBeVisible();
		// Reading to the end is the ask: no press loads the runs before these.
		await expect(canvas.queryByRole("button", { name: "Show earlier runs" })).toBeNull();
		const end = last.closest("tbody")?.lastElementChild;
		if (!end) {
			throw new Error("Expected the list to end in a row of its own.");
		}
		end.scrollIntoView();
		await waitFor(() => {
			void expect(args.onLoadMore).toHaveBeenCalled();
		});
	},
};

/** While the page before these is on its way, the end of the list says so and asks for nothing. */
export const LoadingEarlierRuns: Story = {
	args: { hasMore: true, isLoadingMore: true },
	play: async () => {
		await settledDrawerPanel();
		const canvas = screen;
		const status = canvas.getByText("Loading earlier reviews…");
		await expect(status).toBeVisible();
		await expect(status).toHaveAttribute("aria-live", "polite");
		await expect(canvas.queryByRole("button", { name: "Show earlier runs" })).toBeNull();
	},
};

/**
 * A page that did not arrive takes nothing away: the runs already read stand, the end of the list
 * says what happened, and the press comes back rather than the list retrying under the reader.
 */
export const EarlierRunsFailed: Story = {
	args: { hasMore: true, loadMoreError: new Error("offline") },
	play: async ({ args }) => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getAllByRole("button", { name: /^Open run /u }).length).toBeGreaterThan(0);
		await expect(canvas.getByText("Could not load earlier reviews.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Show earlier runs" }));
		await expect(args.onLoadMore).toHaveBeenCalled();
	},
};

/**
 * A row has one control of its own: "Review this now", drawn only where the ask would be accepted,
 * so the row about somebody else's issue has none. Nothing repeats the work's own link, which the
 * cell beside it already is.
 */
export const RowActions: Story = {
	play: async ({ args }) => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.queryByRole("link", { name: /at its provider/u })).toBeNull();
		await expect(canvas.queryByText("Open the original")).toBeNull();
		const asks = canvas.getAllByRole("button", { name: /^Review .* now/u });
		await expect(asks).toHaveLength(profileReviewRuns.filter((run) => run.mayRequest).length);
		const [first] = asks;
		if (!first) {
			throw new Error("Expected a row the reader may ask about.");
		}
		await userEvent.click(first);
		await expect(args.onReviewNow).toHaveBeenCalledWith(openProfileReviewRun);
	},
};

/**
 * While an ask is in flight, the rows on the work it is about say so rather than inviting a second
 * one; a row on other work keeps its own control, since the ask was not about it.
 */
export const AskingForAReview: Story = {
	args: { requesting: openProfileReviewRun.reviewedWork },
	play: async () => {
		await settledDrawerPanel();
		const onThisWork = profileReviewRuns.filter(
			(run) => run.reviewedWork.id === openProfileReviewRun.reviewedWork.id,
		).length;
		await expect(screen.getAllByText("Asking…")).toHaveLength(onThisWork);
		await expect(screen.getAllByText("Review this now").length).toBeGreaterThan(0);
	},
};

/**
 * The feedback a run left on the work is a link to it where it was left. Where the provider's
 * comment cannot be addressed the row keeps the plain count, which is what it can stand behind.
 */
export const FeedbackOnTheWork: Story = {
	play: async () => {
		await settledDrawerPanel();
		const canvas = screen;
		const links = canvas.getAllByRole("link", { name: /^Read the feedback on the pull request/u });
		await expect(links).toHaveLength(3);
		await expect(links[0]).toHaveAttribute(
			"href",
			"https://github.com/acme/api/pull/902#issuecomment-2481902",
		);
		// The run that stopped early delivered its feedback into a comment with no address, so the
		// link takes the reader to the work itself, where the comment sits.
		await expect(links[2]).toHaveAttribute(
			"href",
			"https://github.com/HephaestusTest/practice-validation/pull/890",
		);
	},
};

/**
 * A run that stopped before it finished still reports what it had recorded by then: every row in
 * this list comes from an observation about the reader, so "nothing" beside a count of them would
 * contradict the row.
 */
export const StoppedEarlyWithSomethingRecorded: Story = {
	play: async () => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getByText("Stopped before it finished")).toBeVisible();
		// What it had recorded by then stands beside the stop; "nothing" would contradict it.
		await expect(canvas.getByText("1 held")).toBeVisible();
	},
};

/** A review being run now says so in words; the row counts nothing yet. */
export const WhileARunIsRunning: Story = {
	args: { runs: [runningProfileReviewRun, ...profileReviewRuns] },
	play: async () => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getByText("Still running")).toBeVisible();
		await expect(canvas.getByText("Results appear as it finishes")).toBeVisible();
		// The glyph turns while the run does; a still one beside "Running" reads as a run that stopped.
		const [spinner] = canvas.getAllByLabelText("Running");
		await expect(spinner).toHaveClass("animate-spin");
	},
};

/**
 * With earlier runs still to come, no row says which review of its work it is: a page of the list
 * cannot count the reviews a piece of work has had.
 */
export const MoreToLoad: Story = {
	args: { hasMore: true },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.queryByText(/^\d+(?:st|nd|rd|th) review$/u)).toBeNull();
	},
};

/** No review has run on this reader's work yet; the list says when one will show up here. */
export const Empty: Story = {
	args: { runs: [] },
	play: async () => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getByText("No review has run on your work yet.")).toBeVisible();
	},
};

/** The rows are drawn in the shape they will land in, as many as the first page holds. */
export const Loading: Story = {
	args: { isLoading: true },
	play: async () => {
		await settledDrawerPanel();
		const canvas = screen;
		canvas.getByText("Loading reviews of your work");
		await expect(canvas.queryByText("Latest")).toBeNull();
	},
};

/** A failed load says so and offers the retry; no list stands beside the alert. */
export const Failed: Story = {
	args: { error: new Error("offline") },
	play: async () => {
		await settledDrawerPanel();
		const canvas = screen;
		await expect(canvas.getByText("Could not load reviews of your work")).toBeVisible();
		await expect(canvas.queryByText("Latest")).toBeNull();
	},
};

/** At 320 px the panel is the viewport, and the table scrolls inside it rather than widening it. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};
