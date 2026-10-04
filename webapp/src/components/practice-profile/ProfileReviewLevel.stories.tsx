import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { PracticeTraceEntry, ProfileReviewRun } from "@/api/types.gen";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { artifactTrace } from "@/components/practice-trace/fixtures";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { groups } from "@/stories/practice-profile-story-mock-data";
import {
	openProfileReviewRun,
	openProfileRunObservations,
} from "@/stories/profile-review-runs-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { daysBefore } from "@/stories/story-clock";

import { reviewLevel, REVIEWS_LEVEL, REVIEWS_OF_YOUR_WORK } from "./practice-profile-search";
import { type ProfileReviewDetailState, ProfileReviewLevel } from "./ProfileReviewLevel";

/**
 * The trace as the endpoint answers it for this review. The shared fixture spans every review of the
 * work, and its failed row from another review rests on this review's occurrence, which the named
 * endpoint never offers.
 */
const namedTrace = {
	...artifactTrace,
	practices: artifactTrace.practices.filter((entry) => entry.practiceSlug !== "dependency-risk"),
};

const loaded = {
	status: "ready",
	run: openProfileReviewRun,
	observations: openProfileRunObservations,
	activity: { status: "ready", trace: namedTrace },
} satisfies ProfileReviewDetailState;

const practiceTable = () => screen.getByRole("table", { name: "Every practice on this work" });

/** Always stacked on the reviews list, so every story mounts it as a drawer's second level. */
const meta = {
	component: ProfileReviewLevel,
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
		state: loaded,
		positionOnWork: 2,
		groups,
		onOpenPractice: fn(),
		tab: "practices",
		onTabChange: fn(),
		filters: {},
		onFiltersChange: fn(),
		onReviewNow: fn(),
		workspaceSlug: "acme",
	},
	argTypes: { path: { control: false }, refusal: { control: false } },
	render: (args) => (
		<DetailDrawerStack
			stack={[REVIEWS_LEVEL, reviewLevel(openProfileReviewRun.reviewId)]}
			size="detailWide"
			onClose={fn()}
		>
			{(entry, level) =>
				entry.kind === "review" ? (
					<ProfileReviewLevel {...args} nested={level.nested} />
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
} satisfies Meta<typeof ProfileReviewLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * The table lists this review's answers only: every observation's own words where a practice said
 * something about the reader, and the recorded reason where it did not.
 */
export const Default: Story = {
	play: async ({ args }) => {
		const panel = await settledDrawerPanel();
		await expect(screen.getByRole("link", { name: /^Open the original/u })).toBeVisible();
		await userEvent.click(screen.getByRole("button", { name: "Request review" }));
		await expect(args.onReviewNow).toHaveBeenCalledWith(openProfileReviewRun.reviewedWork);
		await expect(screen.getByText("Requested")).toBeVisible();
		await expect(screen.getByText("2nd review")).toBeVisible();
		await expect(screen.getByRole("tab", { name: "Every practice 4" })).toBeVisible();
		await expect(screen.getByRole("tab", { name: "What we noticed 5" })).toBeVisible();

		const table = practiceTable();
		await expect(within(table).queryByText("Meaningful commit history")).toBeNull();
		await expect(
			within(table).getByText("The refactor and the fix arrived together"),
		).toBeVisible();
		await expect(
			within(table).getByText("A dependency bump rode along with the behaviour change"),
		).toBeVisible();
		await expect(
			within(table).getByText("The review ended before it reached this practice."),
		).toBeVisible();
		// The unfiltered table states no count: the tab already carries it.
		await expect(screen.queryByText(/^\d+ practices?\.$/u)).toBeNull();
		await expect(
			screen.getByText("three practices assessed in this review · four practices listed"),
		).toBeVisible();
		// The operating facts are the admin's.
		await expect(screen.queryByText(/^Rests on/u)).toBeNull();

		await userEvent.click(screen.getByRole("button", { name: "Thin controllers" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("thin-controllers");
		await expectNoPanelOverflow(panel);
	},
};

const REUSED =
	"An earlier review checked this practice on the same code, so this review did not assess it again.";

const PUSHED = { signal: "scm.pull_request.synchronized", displayName: "New commits pushed" };

/** The review before the open one, on the same merge request. */
const EARLIER_REVIEW = "00000000-0000-0000-0000-0000000002a2";

/** The open review started by a push rather than asked for. */
const pushedRun = { ...openProfileReviewRun, triggerMode: "AUTO" } satisfies ProfileReviewRun;

const pushedSignals = namedTrace.signals.map((signal) =>
	signal.id === "sig-ready" ? { ...signal, ...PUSHED } : signal,
);

/** A practice this review left to the earlier review's answer, reached through the push it started. */
function answeredEarlier(practiceSlug: string, practiceName: string): PracticeTraceEntry {
	return {
		practiceSlug,
		practiceName,
		autonomy: "AUTOMATIC",
		outcome: "REVIEWED",
		explanation: REUSED,
		watches: [PUSHED],
		occasionedBy: PUSHED,
		occasionedById: "sig-ready",
		reviewId: EARLIER_REVIEW,
		observationCount: 0,
		deliveredCount: 0,
		withheldReasons: [],
	};
}

const commitSubjects = answeredEarlier("commit-subjects", "Commit subjects explain each change");
const scopedChange = answeredEarlier("scope-one-reviewable-change", "Scope one reviewable change");

/**
 * Three practices assessed here, two answered by the earlier review, one never reached. The head
 * counts each apart, a reused row links to the review that answered it beside the push this review
 * rests on, and its delivery sentence speaks only of this review.
 */
export const AnsweredByAnEarlierReview: Story = {
	args: {
		canAdminister: true,
		state: {
			...loaded,
			run: pushedRun,
			activity: {
				status: "ready",
				trace: {
					...namedTrace,
					signals: pushedSignals,
					practices: [
						...namedTrace.practices.map((entry) =>
							entry.occasionedById === "sig-ready"
								? { ...entry, watches: [PUSHED], occasionedBy: PUSHED }
								: entry,
						),
						commitSubjects,
						scopedChange,
					],
				},
			},
		},
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(
			screen.getByText(
				"three practices assessed in this review · two practices answered by an earlier review · six practices listed",
			),
		).toBeVisible();
		await expect(screen.getByRole("tab", { name: "Every practice 6" })).toBeVisible();
		const table = within(practiceTable());
		await expect(table.getAllByText(REUSED)).toHaveLength(2);
		await expect(table.getAllByRole("link", { name: "Open the earlier review" })).toHaveLength(2);
		await expect(
			table.getAllByText("No new observations in this review, so nothing was sent"),
		).toHaveLength(2);
		await expect(table.getAllByText(/^Rests on New commits pushed/u)[0]).toBeVisible();
		await expect(table.getByText("The refactor and the fix arrived together")).toBeVisible();
	},
};

/** The moment filter narrows the table in place, and resets to everything. */
export const FilteredPractices: Story = {
	args: { filters: { watches: "scm.pull_request.synchronized" } },
	play: async ({ args }) => {
		await settledDrawerPanel();
		await expect(screen.getByLabelText("Reviews when")).toHaveTextContent("New commits pushed");
		await expect(within(practiceTable()).queryByText("Clear ownership")).toBeNull();
		await expect(screen.getByText("1 practice matches your filters.")).toBeVisible();

		await userEvent.click(screen.getByRole("button", { name: /^Reset/u }));
		await expect(args.onFiltersChange).toHaveBeenCalledWith({});
	},
};

/**
 * A link naming a moment no practice here watches filters nothing, rather than showing the moment
 * by its wire name in the select.
 */
export const FilterOnAMomentNobodyWatches: Story = {
	args: { filters: { watches: "scm.pull_request.labeled" } },
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expect(screen.getByLabelText("Reviews when")).toHaveTextContent("Any moment");
		await expect(within(practiceTable()).getByText("Clear ownership")).toBeVisible();
		await expect(panel).not.toHaveTextContent(/scm\./u);
	},
};

/** An observation with no words of its own falls back to the practice's recorded reason. */
export const ObservationWithoutASummary: Story = {
	args: {
		state: {
			...loaded,
			observations: openProfileRunObservations.map((observation) => ({
				...observation,
				summary: "",
			})),
		},
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(
			within(practiceTable()).getByText(/^Reviewed on the commits pushed/u),
		).toBeVisible();
	},
};

export const MarkedIncorrect: Story = {
	args: {
		state: {
			...loaded,
			observations: openProfileRunObservations.map((observation, index) =>
				index === 0
					? {
							...observation,
							outcome: "MET",
							severity: undefined,
							summary: "The changes each have a clear purpose",
							invalidatedAt: daysBefore(1),
							invalidationReason:
								"The review imported a reason that is not in the merge request description.",
						}
					: observation,
			),
		},
	},
	play: async () => {
		const panel = await settledDrawerPanel();
		const table = within(practiceTable());
		await expect(table.getByText("The changes each have a clear purpose")).toBeVisible();
		await expect(table.getAllByText("Marked incorrect")).toHaveLength(1);
		await expect(table.getByText(/^Reason: The review imported a reason/u)).toBeVisible();
		await expect(
			table.getByText("A dependency bump rode along with the behaviour change"),
		).toBeVisible();
		await expectNoPanelOverflow(panel);
	},
};

export const WhatWeNoticed: Story = {
	args: { tab: "noticed" },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("heading", { name: "What we noticed" })).toBeVisible();
		await expect(
			screen.getByText(/already had a review within this workspace’s cooldown period/u),
		).toBeVisible();
	},
};

/** An admin also reads, under each practice, its operating facts. */
export const AsAnAdmin: Story = {
	args: { canAdminister: true },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("1 piece of feedback reached the developer")).toBeVisible();
		await expect(screen.getAllByText(/^Rests on Marked ready for review/u)[0]).toBeVisible();
	},
};

export const AskingForAReview: Story = {
	args: { requesting: openProfileReviewRun.reviewedWork },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("button", { name: "Requesting review…" })).toBeDisabled();
	},
};

export const NothingReached: Story = {
	args: {
		state: {
			...loaded,
			observations: [],
			activity: { status: "ready", trace: { ...artifactTrace, practices: [] } },
		},
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(
			screen.getByText("This review reached no practice, so there is nothing to list here."),
		).toBeVisible();
	},
};

/**
 * A review still going that has decided no practice yet: the table says the practices are on their
 * way, not that the review reached none.
 */
export const RunningWithNothingReached: Story = {
	args: {
		state: {
			...loaded,
			run: { ...openProfileReviewRun, status: "IN_PROGRESS", practicesEvaluated: undefined },
			observations: [],
			activity: { status: "ready", trace: { ...artifactTrace, practices: [] } },
		},
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("Running")).toBeVisible();
		await expect(
			screen.getByText("Practices appear here as the review reaches them."),
		).toBeVisible();
		await expect(screen.queryByText(/reached no practice/u)).toBeNull();
	},
};

/** Work with an empty title is named by its label, never by a blank heading. */
export const UntitledWork: Story = {
	args: {
		state: {
			...loaded,
			run: {
				...openProfileReviewRun,
				reviewedWork: { ...openProfileReviewRun.reviewedWork, title: "" },
			},
		},
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(
			screen.getByRole("heading", { name: openProfileReviewRun.reviewedWork.label }),
		).toBeVisible();
	},
};

/** The activity is its own read: the head stands while the table waits for it. */
export const ActivityLoading: Story = {
	args: { state: { ...loaded, activity: { status: "loading" } } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("2nd review")).toBeVisible();
		await expect(practiceTable()).toHaveAttribute("aria-busy", "true");
	},
};

export const ActivityFailed: Story = {
	args: {
		state: {
			...loaded,
			activity: { status: "error", error: new Error("offline"), onRetry: fn() },
		},
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("We could not load this work’s review activity")).toBeVisible();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async () => {
		await settledDrawerPanel();
		await expect(practiceTable()).toHaveAttribute("aria-busy", "true");
	},
};

/** A review that is not the reader's, or is gone, answers 404, which offers no retry. */
export const NotFound: Story = {
	args: { state: { status: "error", error: { status: 404 }, onRetry: fn() } },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText("We could not find this review")).toBeVisible();
		await expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
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
