import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor, within } from "storybook/test";

import { HEPH_LABEL } from "@/components/brand/HephIcon";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	OVERVIEW_FIXTURE,
	groups,
	practiceStandings,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { composeOverview, EMPTY_OVERVIEW } from "./compose-overview";
import { DEFAULT_FEEDBACK_TAB } from "./practice-profile-search";
import { PracticeProfilePage } from "./PracticeProfilePage";

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
		state: { status: "ready" },
	},
	argTypes: {
		// One composed record: nothing in it is a control a reader could set by hand.
		overview: { control: false },
	},
} satisfies Meta<typeof PracticeProfilePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Practice profile" })).toBeVisible();
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
		// A real link: the level it opens is an address, so it opens in a new tab and reloads.
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toHaveAttribute(
			"href",
			expect.stringContaining("practice-groups%3Aall"),
		);
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
		// The card's group name opens the group the page knows by that slug.
		await userEvent.click(within(card).getByRole("button", { name: "Packaging work for review" }));
		await expect(args.onOpenGroup).toHaveBeenLastCalledWith(groups[0]);
	},
};

/**
 * Each tab filters the cards in place: resolved ones in their resolved state, "Newest" the two
 * newest open cards.
 */
export const FeedbackTabs: Story = {
	render: (args) => (
		<Stateful initial={args.feedbackTab}>
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
		<Stateful initial={args.feedbackTab}>
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
		await expect(canvas.getByText("No open feedback yet.")).toBeVisible();
		await expect(
			canvas.getByText("Feedback appears once the same shortcoming keeps showing up on your work."),
		).toBeVisible();
		await expect(canvas.queryByRole("article")).toBeNull();
		await expect(canvas.queryByText("Latest run")).toBeNull();
		await expect(canvas.queryByRole("img", { name: HEPH_LABEL })).toBeNull();
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
		state: { status: "loading" },
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
		state: {
			status: "error",
			error: new Error("Practice standings are unavailable right now"),
			onRetry: fn(),
		},
	},
	play: async ({ args: { state }, canvas }) => {
		await expect(canvas.getByText("Could not load your practices")).toBeVisible();
		// A failed load makes no claim about the work: no count, no empty tab, no cold-start copy.
		await expect(canvas.queryByText("No practices set up yet")).toBeNull();
		await expect(canvas.queryByRole("tab")).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(state.status === "error" && state.onRetry).toHaveBeenCalledOnce();
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
