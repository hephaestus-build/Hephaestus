import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

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

import { reviewLevel, REVIEWS_LEVEL, REVIEWS_OF_YOUR_WORK } from "./practice-profile-search";
import { type ProfileReviewDetailState, ProfileReviewLevel } from "./ProfileReviewLevel";

const loaded = {
	status: "ready",
	run: openProfileReviewRun,
	observations: openProfileRunObservations,
	activity: { status: "ready", trace: artifactTrace },
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
		await userEvent.click(screen.getByRole("button", { name: "Review this now" }));
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
			within(table).getByText("The review ended before reaching this practice."),
		).toBeVisible();
		// The unfiltered table states no count: the tab already carries it.
		await expect(screen.queryByText(/^\d+ practices?\.$/u)).toBeNull();
		await expect(screen.getByText("3 of 4 practices reached")).toBeVisible();
		// The operating facts are the admin's.
		await expect(screen.queryByText(/^Rests on/u)).toBeNull();

		await userEvent.click(screen.getByRole("button", { name: "Thin controllers" }));
		await expect(args.onOpenPractice).toHaveBeenCalledWith("thin-controllers");
		await expectNoPanelOverflow(panel);
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

export const WhatWeNoticed: Story = {
	args: { tab: "noticed" },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("heading", { name: "What we noticed" })).toBeVisible();
		await expect(screen.getByText(/reviewed too recently/u)).toBeVisible();
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
		await expect(screen.getByRole("button", { name: "Asking…" })).toBeDisabled();
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
		await expect(screen.getByText("Could not load this work's review activity")).toBeVisible();
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
		await expect(screen.getByText("Could not load this review")).toBeVisible();
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
