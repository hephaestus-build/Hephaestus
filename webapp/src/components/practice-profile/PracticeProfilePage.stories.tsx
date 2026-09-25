import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { PracticeGroup } from "@/api/types.gen";
import type { DetailStackEntry } from "@/components/layout/detail-drawer/detail-stack";
import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { DEFAULT_PRACTICE_GROUP_SORT } from "@/components/practice-vocabulary/practice-group-list-order";
import { DrawerBody, DrawerTitle } from "@/components/ui/drawer";
import { useFeedbackRatings } from "@/stories/feedback-ratings";
import { expectSettledVisible } from "@/stories/overlay";
import {
	ALL_FEEDBACK_CARDS,
	OPEN_FEEDBACK_CARDS,
} from "@/stories/practice-feedback-cards-story-mock-data";
import {
	groups,
	groupStandings,
	OVERVIEW_FIXTURE,
	practicesByGroup,
	practiceStandings,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { AllPracticesLevel } from "./AllPracticesLevel";
import { composeOverview, EMPTY_OVERVIEW } from "./compose-overview";
import {
	allPracticesLevel,
	DEFAULT_FEEDBACK_TAB,
	practiceGroupLevel,
	practiceLevel,
	type PracticeProfileDetailLevelKind,
} from "./practice-profile-search";
import { PracticeProfilePage, type PracticeProfilePageProps } from "./PracticeProfilePage";

const composed = composeOverview(OVERVIEW_FIXTURE);
const empty = composeOverview(EMPTY_OVERVIEW);

const meta = {
	component: PracticeProfilePage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		overview: composed,
		practices: practiceStandings,
		groups,
		feedbackCards: ALL_FEEDBACK_CARDS,
		onOpenGroup: fn(),
		onOpenPractice: fn(),
		feedbackTab: DEFAULT_FEEDBACK_TAB,
		onFeedbackTabChange: fn(),
		onShowAllPractices: fn(),
		isLoading: false,
	},
	argTypes: {
		// One composed record: nothing in it is a control a reader could set by hand.
		overview: { control: false },
	},
} satisfies Meta<typeof PracticeProfilePage>;

export default meta;
type Story = StoryObj<typeof meta>;

const popups = () => [...document.querySelectorAll<HTMLElement>('[data-slot="drawer-popup"]')];

/** The level at `depth`, so a play that has fewer open than it expects says which. */
const popupAt = (depth: number): HTMLElement => {
	const popup = popups()[depth];
	if (!popup) {
		throw new Error(`Expected at least ${depth + 1} open drawer level(s).`);
	}
	return popup;
};

/** The page with the ratings held in story state, where the route keeps them on the server. */
function RatedPage(args: PracticeProfilePageProps) {
	const { ratingProps } = useFeedbackRatings();
	return <PracticeProfilePage {...args} ratingProps={ratingProps} />;
}

/**
 * The page with the route's drawer over it, held in story state: the two lists the page opens and,
 * over them, a stand-in for the group level the profile owns, so a row press stacks a level and a
 * dismissal pops exactly one.
 */
function PageWithDrawer(args: PracticeProfilePageProps) {
	return (
		<Stateful initial={[] as DetailStackEntry<PracticeProfileDetailLevelKind>[]}>
			{(stack, setStack) => {
				const open = (entry: DetailStackEntry<PracticeProfileDetailLevelKind>) =>
					setStack([...stack, entry]);
				const openGroup = (opened: PracticeGroup) => {
					args.onOpenGroup?.(opened);
					open(practiceGroupLevel(opened.slug));
				};
				// The route pushes these as two history entries; here they are two stack entries at once.
				const openPractice = (groupSlug: string, practiceSlug: string) => {
					args.onOpenPractice?.(practiceSlug);
					setStack([...stack, practiceGroupLevel(groupSlug), practiceLevel(practiceSlug)]);
				};
				return (
					<>
						<PracticeProfilePage
							{...args}
							onOpenGroup={openGroup}
							onShowAllPractices={() => {
								args.onShowAllPractices?.();
								open(allPracticesLevel());
							}}
							onOpenPractice={(slug) => {
								const card = OPEN_FEEDBACK_CARDS.find(
									(candidate) => candidate.practiceSlug === slug,
								);
								if (card?.group) {
									openPractice(card.group.slug, slug);
								}
							}}
						/>
						<DetailDrawerStack
							stack={stack}
							size="detailWide"
							onClose={(depth) => setStack(stack.slice(0, depth))}
						>
							{(entry, level) =>
								entry.kind === "practices" ? (
									<AllPracticesLevel
										nested={level.nested}
										groups={groups}
										standings={groupStandings}
										practicesByGroup={practicesByGroup}
										sentences={composed.groupSentences}
										sort={DEFAULT_PRACTICE_GROUP_SORT}
										onSortChange={fn()}
										onOpenGroup={openGroup}
										openGroupSlug={
											stack.find((candidate) => candidate.kind === "practice-group")?.id
										}
										onOpenPractice={args.onOpenPractice}
										isLoading={false}
									/>
								) : (
									<>
										<DetailDrawerHeader nested={level.nested}>
											<DrawerTitle>
												{entry.kind === "practice"
													? `Practice ${entry.id}`
													: (groups.find((candidate) => candidate.slug === entry.id)?.name ??
														entry.id)}
											</DrawerTitle>
										</DetailDrawerHeader>
										<DrawerBody>
											<p className="text-sm text-muted-foreground">
												{entry.kind === "practice"
													? "The practice level the profile renders here."
													: "The group level the profile renders here."}
											</p>
										</DrawerBody>
									</>
								)
							}
						</DetailDrawerStack>
					</>
				);
			}}
		</Stateful>
	);
}

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Practice profile" })).toBeVisible();
		await expect(
			canvas.queryByRole("heading", { name: "Practice development feedback" }),
		).toBeNull();
		// The table of every practice is a level over the page, not a section of it.
		await expect(canvas.queryByRole("table", { name: "All practice groups" })).toBeNull();
		// The cards sit under their own sub-heading and a tab row that opens on the newest ones.
		await expect(canvas.getByRole("heading", { level: 2, name: "Your feedback" })).toBeVisible();
		const tabs = within(canvas.getByRole("tablist", { name: "Feedback" })).getAllByRole("tab");
		await expect(tabs.map((tab) => tab.textContent)).toStrictEqual([
			"Newest 2",
			"Open 6",
			"Resolved 3",
			"All 9",
		]);
		await expect(canvas.getByRole("tab", { name: /^Newest/u })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		await expect(canvas.getAllByRole("article")).toHaveLength(2);
		await expect(canvas.getByText("Newest first")).toBeVisible();
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toBeVisible();
		// The paragraph names two things and counts the rest, which unfolds in place.
		await expect(canvas.getByRole("button", { name: /^Show the/u })).toBeVisible();
		// A card's practice pill opens the level on its observations; "Learn more" on its About tab.
		const [card] = canvas.getAllByRole("article");
		if (!card) {
			throw new Error("The Newest tab shows a card");
		}
		await userEvent.click(
			within(card).getByRole("button", { name: "Scope the change to one concern" }),
		);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith("scope-one-reviewable-change");
		await userEvent.click(
			within(card).getByRole("button", { name: "Learn more about this practice" }),
		);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith(
			"scope-one-reviewable-change",
			"about",
		);
	},
};

/**
 * Each tab filters the cards in place: resolved ones in their resolved state, "Newest" the two
 * newest open cards.
 */
export const FeedbackTabs: Story = {
	render: (args) => (
		<Stateful initial={args.feedbackTab ?? DEFAULT_FEEDBACK_TAB}>
			{(tab, setTab) => (
				<PracticeProfilePage
					{...args}
					feedbackTab={tab}
					onFeedbackTabChange={(next) => {
						args.onFeedbackTabChange?.(next);
						setTab(next);
					}}
				/>
			)}
		</Stateful>
	),
	play: async ({ args, canvas }) => {
		const articles = () => canvas.getAllByRole("article");
		const first = () => {
			const [card] = articles();
			if (!card) {
				throw new Error("The tab lists at least one card");
			}
			return card;
		};
		await userEvent.click(canvas.getByRole("tab", { name: /^Resolved/u }));
		await expect(args.onFeedbackTabChange).toHaveBeenLastCalledWith("resolved");
		await expect(articles()).toHaveLength(3);
		await expect(within(first()).getByText("Resolved")).toBeVisible();
		await expect(articles()[0]).toHaveTextContent("Descriptions named the what, rarely the why");

		await userEvent.click(canvas.getByRole("tab", { name: /^All/u }));
		await expect(articles()).toHaveLength(9);
		// Newest first across both: the open and the resolved card from the latest run lead.
		await expect(articles()[0]).toHaveTextContent("Created 9 September, 2:10 pm");

		await userEvent.click(canvas.getByRole("tab", { name: /^Open/u }));
		await expect(articles()).toHaveLength(6);

		await userEvent.click(canvas.getByRole("tab", { name: /^Newest/u }));
		await expect(articles()).toHaveLength(2);
		await expect(articles()[0]).toHaveTextContent("Pull requests bundle a fix with a refactor");
		await expect(within(first()).getByText("New")).toBeVisible();
	},
};

/**
 * "Read the feedback ↓" in Heph's card lands the reader on the card it is about. The card the
 * fall back names sits behind the "Newest" tab, so the page moves to a tab that lists it and puts
 * the focus on the card, and the reader's next Tab continues from there rather than from the top.
 */
export const ReadTheFeedbackFromTheCard: Story = {
	render: (args) => (
		<Stateful initial={args.feedbackTab ?? DEFAULT_FEEDBACK_TAB}>
			{(tab, setTab) => (
				<PracticeProfilePage
					{...args}
					feedbackTab={tab}
					onFeedbackTabChange={(next) => {
						args.onFeedbackTabChange?.(next);
						setTab(next);
					}}
				/>
			)}
		</Stateful>
	),
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", {
				name: "Read the feedback for Say which acceptance criteria are done",
			}),
		);
		const card = canvas
			.getByText("Pull requests closed their issue without saying what was met")
			.closest("article");
		if (!card) {
			throw new Error("Every piece of feedback is an article");
		}
		await waitFor(async () => {
			await expect(card).toHaveFocus();
		});
	},
};

/**
 * The header's standing card opens the practice-groups table as a level; a row opens its group over it,
 * and every dismissal pops one level — the group, then the table, then the page.
 */
export const AllPracticesOpened: Story = {
	render: (args) => <PageWithDrawer {...args} />,
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("link", { name: "See all practice groups" }));
		await expect(args.onShowAllPractices).toHaveBeenCalledOnce();
		const table = await screen.findByRole("table", { name: "All practice groups" });
		await expectSettledVisible(table);
		await expect(popups()).toHaveLength(1);
		await expect(screen.getByRole("heading", { name: "All practice groups" })).toBeVisible();

		await userEvent.click(
			screen.getByRole("button", { name: "Open group Communicating in the open" }),
		);
		await expect(args.onOpenGroup).toHaveBeenCalledWith(groups[2]);
		await expectSettledVisible(
			await screen.findByText("The group level the profile renders here."),
		);
		await expect(popups()).toHaveLength(2);

		// Back from the group lands on the table, not on the page.
		await userEvent.click(screen.getByRole("button", { name: "Back" }));
		await waitFor(async () => expect(popups()).toHaveLength(1));
		await expect(screen.getByRole("table", { name: "All practice groups" })).toBeVisible();

		await userEvent.keyboard("{Escape}");
		await waitFor(async () => expect(popups()).toHaveLength(0));
	},
};

/**
 * One level in, one level out: a practice opened from a card is its group and then itself, and
 * each back arrow pops exactly one — practice, group, page.
 */
export const PracticeOpenedFromCard: Story = {
	render: (args) => <PageWithDrawer {...args} />,
	parameters: { chromatic: { disableSnapshot: true } },
	play: async ({ args, canvas }) => {
		const [pill] = canvas.getAllByRole("button", { name: "Scope the change to one concern" });
		if (!pill) {
			throw new Error("The first card names its practice");
		}
		await userEvent.click(pill);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("scope-one-reviewable-change");
		await expectSettledVisible(await screen.findByText("Practice scope-one-reviewable-change"));
		await expect(popups()).toHaveLength(2);

		await userEvent.click(within(popupAt(1)).getByRole("button", { name: "Back" }));
		await waitFor(async () => expect(popups()).toHaveLength(1));
		await expect(
			within(popupAt(0)).getByRole("heading", { name: "Packaging work for review" }),
		).toBeVisible();

		await userEvent.click(within(popupAt(0)).getByRole("button", { name: "Close" }));
		await waitFor(async () => expect(popups()).toHaveLength(0));
	},
};

/**
 * The rating stays pressed on the card it was given to; the other card and the other button are
 * untouched.
 */
export const FeedbackRated: Story = {
	render: (args) => <RatedPage {...args} />,
	play: async ({ canvas }) => {
		const [first, second] = canvas.getAllByRole("article");
		if (!first || !second) {
			throw new Error("The page opens with two pieces of feedback");
		}
		const helpful = within(first).getByRole("button", { name: "Helpful" });
		const notHelpful = within(first).getByRole("button", { name: "Not helpful" });
		await userEvent.click(helpful);
		await expect(helpful).toHaveAttribute("aria-pressed", "true");
		await expect(notHelpful).toHaveAttribute("aria-pressed", "false");
		// The rating opens the card's comment band; the other card stays shut.
		await expect(
			within(first).getByRole("textbox", { name: "What worked about this feedback?" }),
		).toBeVisible();
		await expect(within(second).queryByRole("textbox")).toBeNull();
		await expect(within(second).getByRole("button", { name: "Helpful" })).toHaveAttribute(
			"aria-pressed",
			"false",
		);
		await userEvent.click(notHelpful);
		await expect(notHelpful).toHaveAttribute("aria-pressed", "true");
		await expect(helpful).toHaveAttribute("aria-pressed", "false");
		await expect(within(first).getByRole("textbox", { name: "What was missed?" })).toBeVisible();
		// Pressing the chosen one again withdraws the rating and closes the band.
		await userEvent.click(notHelpful);
		await expect(notHelpful).toHaveAttribute("aria-pressed", "false");
		await expect(within(first).queryByRole("textbox")).toBeNull();
	},
};

/** A dispute needs a sentence: once it is sent the band closes and the rating stays pressed. */
export const NotHelpfulCommentSent: Story = {
	render: (args) => <RatedPage {...args} />,
	play: async ({ canvas }) => {
		const [first] = canvas.getAllByRole("article");
		if (!first) {
			throw new Error("The page opens with two pieces of feedback");
		}
		const notHelpful = within(first).getByRole("button", { name: "Not helpful" });
		await userEvent.click(notHelpful);
		await userEvent.click(within(first).getByRole("button", { name: "Not accurate" }));
		await expect(within(first).getByRole("button", { name: "Not accurate" })).toHaveAttribute(
			"aria-pressed",
			"true",
		);
		await userEvent.type(
			within(first).getByRole("textbox", { name: "What was missed?" }),
			"!418 was one change; the rename was a separate commit.",
		);
		await userEvent.click(within(first).getByRole("button", { name: "Send" }));
		await expect(within(first).queryByRole("textbox")).toBeNull();
		await expect(notHelpful).toHaveAttribute("aria-pressed", "true");
	},
};

/**
 * No review has reached any practice, so there is no run, no feedback, and the tab says so in the
 * empty block every practice surface uses: the icon, what is missing, and when it will show up.
 */
export const ColdStart: Story = {
	args: {
		overview: empty,
		practices: [],
		feedbackCards: [],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No feedback yet.")).toBeVisible();
		await expect(
			canvas.getByText("Feedback appears once the same shortcoming keeps showing up on your work."),
		).toBeVisible();
		await expect(canvas.queryByRole("article")).toBeNull();
		await expect(canvas.queryByText("Latest run")).toBeNull();
		await expect(canvas.queryByText("Heph")).toBeNull();
	},
};

/** The Resolved tab says what would move a card here, rather than repeating the other tabs. */
export const NothingResolvedYet: Story = {
	args: {
		overview: empty,
		practices: [],
		feedbackCards: [],
		feedbackTab: "resolved",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No resolved feedback yet.")).toBeVisible();
		await expect(
			canvas.getByText("A card moves here once the work resolves it or you mark it as addressed."),
		).toBeVisible();
	},
};

/** The card list is a skeleton of the two newest cards, and the tabs carry no counts yet. */
export const Loading: Story = {
	args: {
		overview: empty,
		practices: [],
		groups: [],
		feedbackCards: [],
		isLoading: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("tab", { name: "Newest" })).toBeVisible();
		await expect(canvas.queryByRole("article")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		overview: empty,
		practices: [],
		groups: [],
		feedbackCards: [],
		error: new Error("Practice standings are unavailable right now"),
		onRetry: fn(),
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Could not load your practices")).toBeVisible();
		// A failed load makes no claim about the work: no count, no empty tab, no cold-start copy.
		await expect(canvas.queryByText("No practices set up yet")).toBeNull();
		await expect(canvas.queryByRole("tab")).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalled();
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
