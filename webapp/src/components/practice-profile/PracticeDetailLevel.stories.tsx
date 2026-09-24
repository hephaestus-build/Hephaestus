import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import type { ReviewRunFeedState } from "@/components/profile/review-runs";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { withPageBehind } from "@/stories/decorators";
import { useFeedbackRatings } from "@/stories/feedback-ratings";
import { expectSettledVisible } from "@/stories/overlay";
import {
	detailObservation,
	detailPractices,
	detailRun,
	detailRuns,
	focusedChanges,
} from "@/stories/practice-detail-story-mock-data";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import { packagingGroup } from "@/stories/practice-profile-story-mock-data";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { StatefulPatch } from "@/stories/stateful";

import {
	DEFAULT_PRACTICE_TAB,
	type PracticeProfileDetailLevelKind,
	type PracticeTab,
} from "./practice-profile-search";
import { PracticeDetailLevel, type PracticeDetailLevelProps } from "./PracticeDetailLevel";

const readyFeed = {
	status: "ready",
	runs: detailRuns,
	hasMore: false,
	isLoadingMore: false,
	onLoadMore: fn(),
} satisfies ReviewRunFeedState;

/** The practice the preview fixtures write feedback about, both open and resolved. */
const describedPractice = detailPractices[1] ?? focusedChanges;

interface LevelState {
	stack: DetailStackEntry<PracticeProfileDetailLevelKind>[];
	tab: PracticeTab;
}

/** The level with the ratings held in story state, where the route keeps them on the server. */
function RatedLevel(props: PracticeDetailLevelProps) {
	const { ratingProps } = useFeedbackRatings();
	return <PracticeDetailLevel {...props} ratingProps={ratingProps} />;
}

/**
 * The level is always stacked on its group's, so every story mounts it as the second level of a
 * real drawer over a real page. The tab and the stack are the route's in the app; here they are
 * held beside the drawer so a press on a tab, a card or the group name moves the level as it does
 * there. Which observations are open is each row's own.
 */
const meta = {
	component: PracticeDetailLevel,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		practice: focusedChanges,
		feed: readyFeed,
		feedbackCards: ALL_FEEDBACK_CARDS,
		observations: { onRespond: fn() },
		onOpenGroup: fn(),
		onTabChange: fn(),
		isLoading: false,
		onRetry: fn(),
	},
	argTypes: {
		// A discriminated union renders as a free-text box, which cannot produce a valid value.
		feed: { control: false },
	},
	render: (args) => (
		<StatefulPatch<LevelState>
			initial={{
				stack: [
					{ kind: "practice-group", id: packagingGroup.slug },
					{ kind: "practice", id: args.practice?.slug ?? focusedChanges.slug },
				],
				tab: args.tab ?? DEFAULT_PRACTICE_TAB,
			}}
		>
			{(state, patch) => (
				<DetailDrawerStack
					stack={state.stack}
					size="detailWide"
					onClose={(depth) => patch({ stack: state.stack.slice(0, depth) })}
				>
					{(entry, level) =>
						entry.kind === "practice" ? (
							<RatedLevel
								{...args}
								nested={level.nested}
								path={{
									behind: [
										{ label: "Practice profile", depth: 0 },
										{ label: packagingGroup.name, depth: 1 },
									],
									onClose: (depth) => patch({ stack: state.stack.slice(0, depth) }),
								}}
								tab={state.tab}
								onTabChange={(tab) => {
									args.onTabChange?.(tab);
									patch({ tab });
								}}
								onOpenGroup={() => {
									args.onOpenGroup?.();
									patch({ stack: state.stack.slice(0, 1) });
								}}
							/>
						) : (
							// A covered level is not inert, so it is a dialog with a name like any other.
							<>
								<DetailDrawerHeader nested={level.nested}>
									<DrawerTitle>{packagingGroup.name}</DrawerTitle>
								</DetailDrawerHeader>
								<DrawerBody />
							</>
						)
					}
				</DetailDrawerStack>
			)}
		</StatefulPatch>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof PracticeDetailLevel>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The second-newest observation in the fixture feed. */
const olderObservation = detailRuns[1]?.observations[0];
if (!olderObservation) {
	throw new Error("Expected a second run in the fixture feed.");
}

/**
 * Arrives on the observations with the newest open to why it was noted, the evidence, the next
 * step and its response controls; every earlier row is a line the reader can open. Nothing was
 * loaded to open it.
 */
export const Default: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		// Below the top, dismissing returns to the group behind, so the control says Back.
		await expect(screen.getByRole("button", { name: "Back" })).toBeVisible();
		await expect(screen.getByRole("tab", { name: "Observations 3" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(screen.getByRole("tab", { name: "Feedback 0" })).toBeVisible();
		// The run also observed another practice; only this practice's observation is shown.
		await expect(
			screen.queryByText("The description names the motivation"),
		).not.toBeInTheDocument();

		// The newest is open without a press, with its reasons; every earlier one waits for one.
		await expect(
			screen.getByRole("button", { name: new RegExp(detailObservation.summary, "u") }),
		).toHaveAttribute("aria-expanded", "true");
		await expect(
			screen.getByRole("button", { name: new RegExp(olderObservation.summary, "u") }),
		).toHaveAttribute("aria-expanded", "false");
		await expect(screen.getAllByText("Why it was noted")).toHaveLength(1);
		await expect(screen.getAllByText("Next step")).toHaveLength(1);

		// A run reviews this practice once, so every card here is one block: the summary leads and
		// the work it was seen on is the line under it, with no head divided off above.
		const row = screen.getByText(detailObservation.summary).closest("li");
		if (!row) {
			throw new Error("Expected the observation's own row.");
		}
		const rows = row.closest("ul");
		await expect(rows?.parentElement?.firstElementChild).toBe(rows);
		await expect(within(row).getByRole("link", { name: /^#902/u })).toBeVisible();
		await expect(within(row).getByText("HephaestusTest/practice-validation")).toBeVisible();

		await expect(screen.getByText("PracticeCatalogLoader.java")).toBeVisible();
		await expect(screen.getAllByRole("button", { name: "Disputed" })).toHaveLength(1);
		// The rows are this practice's own, so none repeats its name under the summary: the
		// level's title is the one place the practice is named.
		await expect(screen.getAllByText(focusedChanges.name)).toHaveLength(1);
		await expect(screen.getByRole("heading", { name: focusedChanges.name })).toBeVisible();
	},
};

/** Each row answers its own press: an earlier one opens, the newest closes, neither disturbs the other. */
export const OpenedAndClosed: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		const older = screen.getByRole("button", { name: new RegExp(olderObservation.summary, "u") });
		await userEvent.click(older);
		await expect(older).toHaveAttribute("aria-expanded", "true");
		await expect(screen.getAllByText("Why it was noted")).toHaveLength(2);

		const newest = screen.getByRole("button", { name: new RegExp(detailObservation.summary, "u") });
		await userEvent.click(newest);
		await expect(newest).toHaveAttribute("aria-expanded", "false");
		await expect(older).toHaveAttribute("aria-expanded", "true");
		await expect(screen.getAllByText("Why it was noted")).toHaveLength(1);
	},
};

/**
 * The path over the title names the group; its crumb returns to the group's level under this one.
 */
export const BackToTheGroup: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { name: "Observations" }));
		await expect(screen.getByRole("list", { name: "Path" })).toHaveTextContent(
			`Practice profile${packagingGroup.name}Practice`,
		);
		await userEvent.click(screen.getByRole("button", { name: packagingGroup.name }));
		await expect(screen.queryByRole("tab")).not.toBeInTheDocument();
	},
};

/** A dispute asks for its sentence before it is recorded. */
export const Disputing: Story = {
	// One run, so there is one Disputed button to press.
	args: { feed: { ...readyFeed, runs: [detailRun] } },
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByRole("button", { name: "Disputed" }));
		await userEvent.click(screen.getByRole("button", { name: "Disputed" }));
		await expect(args.observations?.onRespond).not.toHaveBeenCalled();
		await userEvent.type(
			screen.getByRole("textbox", { name: "What was missed?" }),
			"The rename was requested by the reviewer in the same thread.",
		);
		await userEvent.click(screen.getByRole("button", { name: "Send" }));
		await expect(args.observations?.onRespond).toHaveBeenCalledWith(detailObservation, {
			usefulness: undefined,
			resolution: "DISPUTED",
			comment: "The rename was requested by the reviewer in the same thread.",
		});
	},
};

/**
 * The practice's one open card and the cards the work resolved, under a heading of the
 * Observations tab's rank; a rating opens the comment band.
 */
export const FeedbackTab: Story = {
	args: { tab: "feedback", practice: describedPractice },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("heading", { level: 2, name: "Feedback" }));
		await expect(screen.getByRole("heading", { level: 3, name: "Current feedback" })).toBeVisible();
		await expect(screen.getByRole("tab", { name: "Feedback 2" })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		// Both cards carry the same headline: the open one as it stands, the resolved one as it was.
		const [open, resolved] = screen.getAllByRole("article", {
			name: "Descriptions named the what, rarely the why",
		});
		if (!open || !resolved) {
			throw new Error("Expected the open and the resolved card.");
		}
		await expect(within(open).getByText("Open")).toBeVisible();
		// Each card heads with the practice as the grey pill, on the practice's own level too.
		for (const card of [open, resolved]) {
			await expect(
				within(card).getByText(describedPractice.name).closest('[data-slot="badge"]'),
			).not.toBeNull();
		}
		await expect(
			screen.getByRole("heading", { level: 3, name: "Resolved feedback" }),
		).toBeVisible();
		await expect(within(resolved).getByText("Resolved 9 September")).toBeVisible();
		await userEvent.click(within(open).getByRole("button", { name: "Helpful" }));
		await expect(
			screen.getByRole("textbox", { name: "What worked about this feedback?" }),
		).toBeVisible();
	},
};

/** A card's "Learn more about this practice" moves to the neighbouring tab. */
export const FeedbackToAbout: Story = {
	args: { tab: "feedback", practice: describedPractice },
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("Current feedback"));
		const [open] = screen.getAllByRole("article");
		if (!open) {
			throw new Error("Expected the open card.");
		}
		await userEvent.click(
			within(open).getByRole("button", { name: "Learn more about this practice" }),
		);
		await expect(args.onTabChange).toHaveBeenCalledWith("about");
		await expect(screen.getByText("Where you stand")).toBeVisible();
	},
};

/**
 * Nothing open and nothing resolved: the same empty block the Observations tab draws, and no
 * "Current feedback" or "Resolved feedback" label claims a list of nothing.
 */
export const FeedbackEmpty: Story = {
	args: { tab: "feedback" },
	play: async () => {
		await expectSettledVisible(await screen.findByText("No feedback yet."));
		await expect(
			screen.getByText("Feedback appears once the same shortcoming keeps showing up on your work."),
		).toBeVisible();
		await expect(screen.getByRole("heading", { level: 2, name: "Feedback" })).toBeVisible();
		await expect(screen.queryByText("Current feedback")).not.toBeInTheDocument();
		await expect(screen.queryByText("Resolved feedback")).not.toBeInTheDocument();
	},
};

/**
 * Where the reader stands, in the registry's words and what each rests on, boxed under its label;
 * then the catalog's words.
 */
export const AboutTab: Story = {
	args: { tab: "about" },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Where you stand"));
		const standingLine = screen.getByText(
			"Recent reviews found both strengths and problems here. Based on your latest six pieces of reviewed work.",
		);
		const trendLine = screen.getByText(
			"Recent reviewed work carried more strengths than the stretch before it. Compared your latest six pieces of reviewed work with the five before them. Evidence spans 12 days.",
		);
		await expect(standingLine).toBeVisible();
		await expect(trendLine).toBeVisible();
		// The two lines share one bordered box on the 60% ground, under the label.
		await expect(standingLine.parentElement).toBe(trendLine.parentElement);
		await expect(standingLine.parentElement).toHaveClass("border", "bg-sidebar");
		await expect(screen.getByText("Why it matters")).toBeVisible();
		await expect(screen.getByText("What good looks like")).toBeVisible();
	},
};

export const MoreToLoad: Story = {
	args: { feed: { ...readyFeed, hasMore: true } },
	play: async () => {
		await expectSettledVisible(await screen.findByRole("button", { name: "View earlier reviews" }));
	},
};

export const FeedLoading: Story = {
	args: { feed: { status: "loading" }, skeletonRows: 4 },
};

export const FeedFailed: Story = {
	args: { feed: { status: "error", error: new Error("Gateway timeout"), onRetry: fn() } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("Could not load review runs"));
	},
};

export const EmptyFeed: Story = {
	args: { feed: { ...readyFeed, runs: [] } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("No review has reached this practice yet."));
	},
};

/**
 * A practice no review has settled: the badge and the trend both say so in words about this one
 * practice, and the About tab claims no basis and no direction.
 */
export const NotObserved: Story = {
	args: {
		tab: "about",
		practice: {
			...focusedChanges,
			standing: "NOT_OBSERVED",
			direction: undefined,
			trendSupport: undefined,
		},
		feed: { ...readyFeed, runs: [] },
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("Where you stand"));
		await expect(screen.getAllByText("Not observed yet")).toHaveLength(2);
		await expect(
			screen.getByText("This practice has no current verdict for you yet."),
		).toBeVisible();
		await expect(screen.queryByText(/No practice in this group/u)).not.toBeInTheDocument();
		await expect(screen.queryByText(/Based on your latest/u)).not.toBeInTheDocument();
		// The header's chip is the only one: no direction is claimed over no verdict.
		await expect(screen.getAllByText("Not enough to compare yet")).toHaveLength(1);
	},
};

/** The rail and the feed each draw their own shape, so nothing jumps when the level lands. */
export const Loading: Story = {
	args: { isLoading: true, practice: undefined },
	play: async () => {
		await screen.findByText("Loading review runs");
		await expect(screen.queryByRole("tab")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: { error: new Error("Unavailable") },
	play: async () => {
		await expectSettledVisible(
			await screen.findByText("Could not load your standing for Keep changes focused"),
		);
	},
};

export const Missing: Story = {
	args: { practice: undefined },
};

export const MobileReflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("PracticeCatalogLoader.java"));
		// The frontmost panel: the group level behind it is a placeholder with no body to measure.
		const panels = document.querySelectorAll<HTMLElement>('[data-slot="drawer-popup"]');
		const frontmost = [...panels].at(-1);
		if (!frontmost) {
			throw new Error("Expected the practice level to be open.");
		}
		await expectNoPanelOverflow(frontmost);
	},
};
