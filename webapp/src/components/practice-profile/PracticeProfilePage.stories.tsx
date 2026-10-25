import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import { HEPH_LABEL } from "@/components/brand/HephIcon";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	groups,
	LATEST_REVIEW,
	practiceStandings,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";
import { precedes } from "@/test/dom";

import { withProvider } from "@/stories/decorators";
import { DEFAULT_FEEDBACK_TAB } from "./practice-profile-search";
import { PracticeProfilePage } from "./PracticeProfilePage";

const meta = {
	decorators: [withProvider("GITHUB")],
	component: PracticeProfilePage,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		latestReview: LATEST_REVIEW,
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
		latestReview: { control: false },
	},
} satisfies Meta<typeof PracticeProfilePage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("heading", { level: 1, name: "Practice profile" })).toBeVisible();
		// The table of every practice is a level over the page, not a section of it.
		await expect(canvas.queryByRole("table", { name: "All practice groups" })).toBeNull();
		// The cards sit under their own sub-heading and a tab row that opens on the open ones.
		await expect(canvas.getByRole("heading", { level: 2, name: "Your feedback" })).toBeVisible();
		const tabs = within(canvas.getByRole("tablist", { name: "Feedback" })).getAllByRole("tab");
		await expect(tabs.map((tab) => tab.textContent)).toStrictEqual([
			"Open 6",
			"Resolved and closed 3",
			"All 9",
		]);
		await expect(canvas.getByRole("tab", { name: /^Open/u })).toHaveAttribute(
			"aria-selected",
			"true",
		);
		const articles = canvas.getAllByRole("article");
		await expect(articles).toHaveLength(6);
		// Room between the cards, so each card's own edge parts it from the next: they do not touch.
		const [first, second] = articles;
		await expect(
			(second?.getBoundingClientRect().top ?? 0) - (first?.getBoundingClientRect().bottom ?? 0),
		).toBeGreaterThan(0);
		await expect(canvas.getByText("Newest first")).toBeVisible();
		// A real link: the level it opens is an address, so it opens in a new tab and reloads.
		await expect(canvas.getByRole("link", { name: "See all practice groups" })).toHaveAttribute(
			"href",
			expect.stringContaining("practice-groups:all"),
		);
		// Practices going well are counted in the header's standings, with no section of their own:
		// the feedback is the first section under the header.
		await expect(practiceStandings.some((practice) => practice.standing === "STRENGTH")).toBe(true);
		await expect(canvas.queryByRole("heading", { name: "Going well" })).toBeNull();
		const firstSection = canvas.getByRole("heading", { level: 2, name: "Your feedback" });
		await expect(canvas.getAllByRole("heading", { level: 2 })[0]).toBe(firstSection);
		await expect(
			precedes(canvas.getByRole("heading", { level: 1, name: "Practice profile" }), firstSection),
		).toBe(true);
		await expect(canvas.queryByRole("img", { name: HEPH_LABEL })).toBeNull();
		await expect(canvas.queryByText("What changed")).toBeNull();
		// A card's practice pill opens the practice's level on its default tab; its "Learn more" asks
		// for the Guide tab, where the practice is explained.
		const [card] = canvas.getAllByRole("article");
		if (!card) {
			throw new Error("The Open tab shows a card");
		}
		await userEvent.click(
			within(card).getByRole("button", { name: "Scope the change to one concern" }),
		);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith("scope-one-reviewable-change");
		await userEvent.click(
			within(card).getByRole("button", { name: "Learn more about this practice" }),
		);
		await expect(args.onOpenPractice).toHaveBeenCalledTimes(2);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith(
			"scope-one-reviewable-change",
			"guide",
		);
		// The card's group name opens the group the page knows by that slug.
		await userEvent.click(within(card).getByRole("button", { name: "Packaging work for review" }));
		await expect(args.onOpenGroup).toHaveBeenLastCalledWith(groups[0]);
	},
};

/** Each tab filters the cards in place: open ones, resolved ones in their resolved state, or all. */
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
		await expect(articles()[0]).toHaveTextContent("Created 9 September, 2:10 pm");

		await userEvent.click(canvas.getByRole("tab", { name: /^Open/u }));
		await expect(articles()).toHaveLength(6);
		await expect(articles()[0]).toHaveTextContent("Pull requests bundle a fix with a refactor");
		await expect(within(first()).getByText("New")).toBeVisible();
	},
};

export const ColdStart: Story = {
	args: {
		latestReview: undefined,
		practices: [],
		feedbackCards: [],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No open feedback yet")).toBeVisible();
		await expect(
			canvas.getByText(
				"Feedback appears here once a review sees the same pattern in your work more than once. Feedback posted on the work stays there.",
			),
		).toBeVisible();
		await expect(canvas.queryByRole("article")).toBeNull();
		await expect(canvas.queryByText("Latest review")).toBeNull();
		await expect(canvas.queryByRole("img", { name: HEPH_LABEL })).toBeNull();
	},
};

/** "Resolved and closed" says what would move a card here, rather than repeating the other tabs. */
export const NothingResolvedYet: Story = {
	args: {
		latestReview: undefined,
		practices: [],
		feedbackCards: [],
		feedbackTab: "resolved",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No resolved or closed feedback yet")).toBeVisible();
		await expect(
			canvas.getByText(
				"A card moves here once your work resolves it, you mark it yourself, or your workspace changes its practice.",
			),
		).toBeVisible();
	},
};

/** The card list is a skeleton of two cards, and the tabs carry no counts yet. */
export const Loading: Story = {
	args: {
		latestReview: undefined,
		practices: [],
		groups: [],
		feedbackCards: [],
		state: { status: "loading" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("tab", { name: "Open" })).toBeVisible();
		await expect(canvas.queryByRole("article")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		latestReview: undefined,
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
		await expect(canvas.getByText("We could not load your practices")).toBeVisible();
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
