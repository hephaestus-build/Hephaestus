import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { detailObservation } from "@/stories/practice-detail-story-mock-data";
import { groups } from "@/stories/practice-profile-story-mock-data";
import {
	openProfileReviewRun,
	openProfileRunObservations,
	profileOlderRunTrace,
	profileReviewRuns,
	profileRunTrace,
	runningProfileReviewRun,
} from "@/stories/profile-review-runs-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";

import { REVIEW_RUNS_LEVEL, REVIEWS_OF_YOUR_WORK, reviewRunLevel } from "./practice-profile-search";
import { ReviewRunLevel } from "./ReviewRunLevel";

/**
 * The level is always stacked on the list of runs, so every story mounts it as the second level of
 * a real drawer over a real page.
 */
const meta = {
	component: ReviewRunLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		path: {
			behind: [
				{ label: "Practice profile", depth: 0 },
				{ label: REVIEWS_OF_YOUR_WORK, depth: 1 },
			],
			onClose: fn(),
		},
		run: openProfileReviewRun,
		observations: openProfileRunObservations,
		trace: profileRunTrace,
		// The newer of the two runs on this pull request, as the complete list counts them.
		positionOnWork: { position: 2, total: 2 },
		groups,
		onOpenPractice: fn(),
		tab: "practices",
		onTabChange: fn(),
		filters: {},
		onFiltersChange: fn(),
		onReviewNow: fn(),
		workspaceSlug: "acme",
		isLoading: false,
		onRetry: fn(),
	},
	argTypes: { path: { control: false } },
	render: (args) => (
		<DetailDrawerStack
			stack={[REVIEW_RUNS_LEVEL, reviewRunLevel(args.run?.reviewId ?? "run")]}
			size="detailWide"
			onClose={fn()}
		>
			{(entry, level) =>
				entry.kind === "review-run" ? (
					<ReviewRunLevel {...args} nested={level.nested} />
				) : (
					// A covered level is not inert, so it is a dialog with a name like any other.
					<>
						<DetailDrawerHeader nested={level.nested}>
							<DrawerTitle>{REVIEWS_OF_YOUR_WORK}</DrawerTitle>
						</DetailDrawerHeader>
						<DrawerBody />
					</>
				)
			}
		</DetailDrawerStack>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ReviewRunLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledDrawerPanel();
		// The head names the work and carries the two things a reader can do about it.
		await expect(screen.getByText(/Pull or merge request/u)).toBeVisible();
		await expect(screen.getByRole("link", { name: /^Open the original/u })).toBeVisible();
		await expect(screen.getByRole("button", { name: "Review this now" })).toBeVisible();
		await expect(screen.queryByRole("button", { name: "Copy link" })).toBeNull();
		await expect(screen.getByText("Requested by hand")).toBeVisible();
		// Which review of this work it is, in the same words a row uses; no "Run 2 of 2" sentence.
		await expect(screen.getByText("2nd review")).toBeVisible();
		await expect(screen.queryByText(/Run \d+ of \d+/u)).toBeNull();

		// No lead over the tabs: the table under them says it per practice, in the review's own words.
		await expect(screen.queryByText(/one linked page did not follow it/u)).toBeNull();
		await expect(screen.getByRole("tab", { name: "Every practice 5" })).toBeVisible();
		await expect(screen.getByRole("tab", { name: "What we noticed 2" })).toBeVisible();

		// The practice table lists what this run answered and nothing another run did.
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(within(table).getByText("Scope the change to one concern")).toBeVisible();
		await expect(within(table).getByText("Small, reviewable changes")).toBeVisible();
		await expect(within(table).queryByText("Meaningful commit history")).toBeNull();
		// A reached practice shows the observation's own words; a quiet one its recorded reason.
		await expect(
			within(table).getByText("The refactor and the fix arrived together"),
		).toBeVisible();
		await expect(within(table).getByText(/reviewed 40 minutes ago/u)).toBeVisible();
		await expect(screen.getByText("5 practices.")).toBeVisible();

		// The operating facts are the admin's: a member has nothing to do about them.
		await expect(screen.queryByText(/41 s/u)).toBeNull();
		await expect(screen.queryByText(/Rests on/u)).toBeNull();

		await userEvent.click(screen.getByRole("button", { name: "Scope the change to one concern" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-one-reviewable-change");

		await expectNoPanelOverflow(panel);
	},
};

/** The filters narrow the table in place, and the count says what survived them. */
export const FilteredPractices: Story = {
	args: { filters: { group: "Owning the change" } },
	play: async ({ args }) => {
		await settledDrawerPanel();
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(within(table).getByText("Clear ownership")).toBeVisible();
		await expect(within(table).queryByText("Scope the change to one concern")).toBeNull();
		await expect(screen.getByText("2 practices match your filters.")).toBeVisible();

		await userEvent.click(screen.getByRole("button", { name: /^Reset/u }));
		await expect(args.onFiltersChange).toHaveBeenCalledWith({});
	},
};

/**
 * Every group with a practice in the table is on offer, including one the reader has no standing in
 * and one the workspace hides from its dashboards: the trace names each practice's group, so the
 * choices cannot fall short of the rows they narrow.
 */
export const EveryGroupIsOnOffer: Story = {
	play: async () => {
		await settledDrawerPanel();
		await userEvent.click(screen.getByLabelText("Group"));
		const list = await screen.findByRole("listbox");
		await expect(
			within(list).getByRole("option", { name: "Packaging work for review" }),
		).toBeVisible();
		// "Owning the change" is not among the groups this page holds, and is listed all the same.
		await expect(within(list).getByRole("option", { name: "Owning the change" })).toBeVisible();
		await userEvent.keyboard("{Escape}");
	},
};

/** What starts a review is a filter rather than a line per row, named the way the timeline names it. */
export const FilteredByReviewEvent: Story = {
	args: { filters: { watches: "scm.pull_request.synchronized" } },
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByLabelText("Review event")).toHaveTextContent("New commits pushed");
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(within(table).getByText("Small, reviewable changes")).toBeVisible();
		await expect(within(table).queryByText("Clear ownership")).toBeNull();

		await userEvent.click(screen.getByRole("button", { name: /^Reset/u }));
		await expect(args.onFiltersChange).toHaveBeenCalledWith({});
	},
};

/**
 * A practice this review reached shows what the review observed about the work, one line per
 * observation, and the way to the observation itself. The next step, the evidence and the feedback
 * are on the observation, so the table does not repeat them, and the catalog's words on the
 * practice never appear here at all.
 */
export const ObservedAboutYou: Story = {
	play: async () => {
		await settledDrawerPanel();
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(
			within(table).getByText("The refactor and the fix arrived together"),
		).toBeVisible();
		await expect(within(table).queryByText("Next step")).toBeNull();
		await expect(within(table).queryByRole("button", { name: /^Read the feedback/u })).toBeNull();
		await expect(
			within(table).queryByRole("button", { name: /^Open the observation/u }),
		).toBeNull();
	},
};

/**
 * The earlier of two runs on the same pull request. Its activity is asked for that run, so the
 * table lists the practices it reached and what it made of them rather than the newer run's.
 */
export const OlderReviewOfThisWork: Story = {
	args: {
		run: profileReviewRuns[1],
		observations: [],
		trace: profileOlderRunTrace,
		positionOnWork: { position: 1, total: 2 },
	},
	play: async () => {
		await settledDrawerPanel();
		// The first review of a piece of work wears no tag: every work has one.
		await expect(screen.queryByText(/^\d+(?:st|nd|rd|th) review$/u)).toBeNull();
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(within(table).getByText("Meaningful commit history")).toBeVisible();
		await expect(within(table).getByText("Migration safety")).toBeVisible();
		await expect(within(table).getByText(/touches no migration/u)).toBeVisible();
		await expect(within(table).queryByText(/reviewed 40 minutes ago/u)).toBeNull();
	},
};

/** A practice observed twice in one run says both, rather than whichever was read last. */
export const ObservedTwiceInOneRun: Story = {
	args: {
		observations: [
			...openProfileRunObservations,
			{
				...(openProfileRunObservations[0] ?? detailObservation),
				id: "00000000-0000-0000-0000-0000000001aa",
				summary: "A dependency bump rode along with the behaviour change",
			},
		],
	},
	play: async () => {
		await settledDrawerPanel();
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(
			within(table).getByText("The refactor and the fix arrived together"),
		).toBeVisible();
		await expect(
			within(table).getByText("A dependency bump rode along with the behaviour change"),
		).toBeVisible();
	},
};

/**
 * A practice this run reached that observed nothing about this reader carries the recorded reason
 * and no way on: there is no observation of theirs to open.
 */
export const ReachedAndSaidNothingAboutYou: Story = {
	args: { observations: [] },
	play: async () => {
		await settledDrawerPanel();
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(
			within(table).getByText(
				"Reviewed on the commits that were ready at 10:15, and one point was raised.",
			),
		).toBeVisible();
		await expect(
			within(table).queryByRole("button", { name: /^Open the observation/u }),
		).toBeNull();
	},
};

/** A practice that stayed quiet carries its recorded reason, and nothing else. */
export const QuietPractice: Story = {
	play: async () => {
		await settledDrawerPanel();
		const table = screen.getByRole("table", { name: "Every practice on this work" });
		await expect(within(table).getByText(/reviewed 40 minutes ago/u)).toBeVisible();
		await expect(
			within(table).queryByRole("button", { name: /^Open the observation for Small, reviewable/u }),
		).toBeNull();
	},
};

/** A filter nothing matches says so in the table rather than leaving an empty frame. */
export const FilteredToNothing: Story = {
	args: { filters: { practice: "zzz" } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("No practice here matches your filters.")).toBeVisible();
		await expect(screen.getByText("0 practices match your filters.")).toBeVisible();
	},
};

/** Everything recorded about the work, oldest first, on its own tab. */
export const WhatWeNoticed: Story = {
	args: { tab: "noticed" },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("heading", { name: "What we noticed" })).toBeVisible();
		await expect(screen.getByText("Marked ready for review")).toBeVisible();
		await expect(screen.getByText(/reviewed too recently/u)).toBeVisible();
	},
};

/**
 * A workspace admin also reads how much of the practice set the run reached and, under each
 * practice, the delivery sentence, the autonomy, what the answer rests on and what the practice
 * watches. The head's ratio is counted from the table under it, so the two agree: four of the five
 * practices listed were reached, and the four assessments the row counts add up to those four.
 */
export const AsAnAdmin: Story = {
	args: { canAdminister: true },
	play: async () => {
		await settledDrawerPanel();
		const { strengths, problems, notApplicable, undetermined } = openProfileReviewRun.observations;
		await expect(strengths + problems + notApplicable + undetermined).toBe(
			openProfileReviewRun.practicesEvaluated,
		);
		await expect(screen.getByText("4 of 5 practices reached")).toBeVisible();
		await expect(screen.getByText("5 practices.")).toBeVisible();
		// The duration is gone from the head, and no fact in it is joined by a middle dot.
		await expect(screen.queryByText(/41 s/u)).toBeNull();
		await expect(screen.queryByText(/·/u)).toBeNull();
		await expect(screen.getByText("1 piece of feedback reached the developer")).toBeVisible();
		// Every reached practice rests on the same moment, so the anchor is on screen more than once.
		await expect(screen.getAllByText(/^Rests on Marked ready for review/u)[0]).toBeVisible();
		// What a practice watches is the Review event filter now, not a line under every row.
		await expect(screen.queryByText(/^Starts a review on/u)).toBeNull();
	},
};

/** The reader may not ask for a review of somebody else's work, so no button offers it. */
export const CannotAskForAReview: Story = {
	args: { run: { ...openProfileReviewRun, mayRequest: false } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.queryByRole("button", { name: "Review this now" })).toBeNull();
		await expect(screen.getByRole("link", { name: /^Open the original/u })).toBeVisible();
	},
};

/** An ask in flight says so on the button rather than inviting a second one. */
export const AskingForAReview: Story = {
	args: { isRequesting: true },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("button", { name: "Asking…" })).toBeDisabled();
	},
};

/**
 * A refused ask is answered on the level, not in a toast: it is the one answer the reader asked
 * for, and a toast is gone before they have read it.
 */
export const RefusedAsk: Story = {
	args: {
		reviewRefusal: {
			status: "REFUSED",
			reason: "COOLDOWN_ACTIVE",
			reasonDescription: "This work was reviewed a moment ago; a later change gets its own review.",
		},
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("No review was started")).toBeVisible();
		await expect(screen.getByText(/reviewed a moment ago/u)).toBeVisible();
	},
};

/** The only run on this work wears no tag: a tag on every run would single out none. */
export const OnlyRunOnThisWork: Story = {
	args: { positionOnWork: { position: 1, total: 1 } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.queryByText(/^\d+(?:st|nd|rd|th) review$/u)).toBeNull();
	},
};

/**
 * The list under the level is narrowed by a filter or loaded only in part, so which run of its work
 * this is cannot be known: the head says nothing rather than a count it cannot stand behind.
 */
export const PositionNotKnown: Story = {
	args: { positionOnWork: undefined },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.queryByText(/^\d+(?:st|nd|rd|th) review$/u)).toBeNull();
	},
};

/**
 * The work's review activity is a second read that lands after the run: the head stands, and the
 * tabs wait for their rows rather than claiming the run reached no practice.
 */
export const ActivityLoading: Story = {
	args: { trace: undefined, traceState: { status: "loading" } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("2nd review")).toBeVisible();
		// The head's ratio waits for the activity it counts against rather than naming half of it.
		await expect(screen.queryByText(/practices reached/u)).toBeNull();
		screen.getByText("Loading the review activity of this work");
		await expect(screen.queryByText(/reached no practice/u)).toBeNull();
	},
};

/** The activity failed to load: the tabs say so and offer the retry, and claim nothing about the run. */
export const ActivityFailed: Story = {
	args: {
		trace: undefined,
		traceState: { status: "error", error: new Error("offline"), onRetry: fn() },
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("Could not load this work's review activity")).toBeVisible();
		await expect(screen.queryByText(/reached no practice/u)).toBeNull();
	},
};

/** A run that reached no practice says so in the table rather than showing an empty frame. */
export const NothingNeededSaying: Story = {
	args: {
		run: { ...openProfileReviewRun, lead: undefined },
		observations: [],
		trace: { ...profileRunTrace, practices: [] },
		positionOnWork: { position: 1, total: 1 },
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(
			screen.getByText("This run reached no practice, so there is nothing to list here."),
		).toBeVisible();
	},
};

/** A review still going: the state is a word in the head, never a spinner. */
export const WhileARunIsRunning: Story = {
	args: {
		run: runningProfileReviewRun,
		observations: [],
		trace: { ...profileRunTrace, practices: [] },
		positionOnWork: { position: 1, total: 1 },
	},
	play: async () => {
		await settledDrawerPanel();
		const badge = screen.getByText("Running");
		await expect(badge).toBeVisible();
		await expect(badge.querySelector(".animate-spin")).not.toBeNull();
	},
};

/** The head waits for the run rather than inventing a title over an empty body. */
export const Loading: Story = {
	args: { isLoading: true },
	play: async () => {
		await settledDrawerPanel();
		screen.getByText("Loading this review run");
		await expect(screen.queryByRole("tab", { name: /^Every practice/u })).toBeNull();
	},
};

/** A failed load says so and offers the retry. */
export const Failed: Story = {
	args: { error: new Error("offline") },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("Could not load this review run")).toBeVisible();
	},
};

/** A run that is not one of the reader's, or is gone: the level says so rather than showing a shell. */
export const NotFound: Story = {
	args: { run: undefined, observations: [], trace: undefined },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText(/not one of yours/u)).toBeVisible();
	},
};

/** At 320 px the panel is the viewport, and nothing in the level widens it. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: async () => {
		await expectNoPanelOverflow(await settledDrawerPanel());
	},
};
