import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { MemberReviewReadiness } from "@/api/types.gen";
import { DEFAULT_FEEDBACK_TAB } from "@/components/practice-profile/practice-profile-search";
import { withProvider } from "@/stories/decorators";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	groups,
	LATEST_REVIEW,
	practiceStandings,
	practicesByGroup,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import { PracticeProfileTour, type PracticeProfileTourProps } from "./PracticeProfileTour";
import { type TourChapterId, tourChapters } from "./tour-chapters";

const STORAGE_KEY = "hephaestus:guided-tour:practice-profile";

const READY: MemberReviewReadiness = {
	blocker: null,
	events: ["scm.pull_request.opened"],
	approvalBeforePosting: false,
};

const NEW_READER: PracticeProfileTourProps["page"] = {
	practices: practiceStandings,
	groups,
	feedbackCards: [],
	feedbackTab: DEFAULT_FEEDBACK_TAB,
	state: { status: "ready" },
	firstRun: { workspaceName: "Acme", readiness: READY },
};

const READER_WITH_CARDS: PracticeProfileTourProps["page"] = {
	...NEW_READER,
	latestReview: LATEST_REVIEW,
	feedbackCards: ALL_FEEDBACK_CARDS,
};

const meta = {
	component: PracticeProfileTour,
	decorators: [withProvider("GITHUB")],
	beforeEach: () => {
		window.localStorage.removeItem(STORAGE_KEY);
	},
	args: {
		workspaceSlug: "acme",
		workspaceName: "Acme",
		provider: "GITHUB",
		page: NEW_READER,
		practicesByGroup,
		onClose: fn(),
	},
} satisfies Meta<typeof PracticeProfileTour>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Presses Next through a chapter and checks that each step lights the part it names. */
async function walkChapter(page: PracticeProfileTourProps["page"], chapterId: TourChapterId) {
	const chapter = tourChapters({
		workspaceSlug: "acme",
		workspaceName: "Acme",
		page,
		practicesByGroup,
		openedAt: new Date(),
	}).find((each) => each.id === chapterId);
	for (const [index, step] of (chapter?.steps ?? []).entries()) {
		await screen.findByRole("heading", { level: 3, name: step.title });
		await expect(
			screen.getByText(`Step ${index + 1} of ${chapter?.steps.length ?? 0}`, {
				selector: "span:not(.sr-only)",
			}),
		).toBeVisible();
		if (step.slot !== undefined) {
			const lit = document.querySelector(`[data-tour-slot="${step.slot}"][data-tour-active]`);
			await expect(lit, `step "${step.id}" lights "${step.slot}"`).not.toBeNull();
		}
		if (index < (chapter?.steps.length ?? 0) - 1) {
			await userEvent.click(screen.getByRole("button", { name: "Next" }));
		}
	}
}

export const Menu: Story = {
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await expect(dialog).toHaveAccessibleDescription(/Choose what you want to know/u);
		for (const name of ["How it works", "Your profile", "Going deeper", "Across the workspace"]) {
			await expect(
				within(dialog).getByRole("button", { name: new RegExp(name, "u") }),
			).toBeVisible();
		}
		// With no card yet, the chain is where a new person starts.
		await waitFor(async () => {
			await expect(
				within(dialog).getByRole("button", { name: /How it works.*Start here/u }),
			).toHaveFocus();
		});
		await expect(within(dialog).queryByRole("button", { name: "Start over" })).toBeNull();
	},
};

export const MenuWithAFirstCard: Story = {
	args: { page: READER_WITH_CARDS },
	play: async () => {
		await expect(
			await screen.findByRole("button", { name: /Your profile.*Start here/u }),
		).toBeVisible();
	},
};

export const MenuContinues: Story = {
	beforeEach: () => {
		window.localStorage.setItem(
			STORAGE_KEY,
			JSON.stringify({ place: { chapter: "your-profile", step: "why" }, done: ["how-it-works"] }),
		);
	},
	play: async () => {
		await userEvent.click(
			await screen.findByRole("button", { name: /Your profile.*Continue at step 5/u }),
		);
		await screen.findByRole("heading", { level: 3, name: "Why it was raised" });
	},
};

export const StartOverClearsProgress: Story = {
	beforeEach: MenuContinues.beforeEach,
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Start over" }));
		await expect(screen.queryByText(/Continue at step/u)).toBeNull();
		await expect(screen.queryByText("Done")).toBeNull();
	},
};

export const SpoiledProgressStartsFresh: Story = {
	beforeEach: () => {
		window.localStorage.setItem(STORAGE_KEY, "{not json");
	},
	play: async () => {
		await expect(
			await screen.findByRole("button", { name: /How it works.*Start here/u }),
		).toBeVisible();
	},
};

export const HowItWorks: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		await walkChapter(NEW_READER, "how-it-works");
		await expect(screen.getByRole("button", { name: "Next chapter: Your profile" })).toHaveFocus();
	},
};

export const HowItWorksSaysWhatStartsAReview: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		await expect(screen.getByRole("dialog")).toHaveTextContent(
			/In Acme, a review of your work can start when:/u,
		);
		await expect(screen.getByRole("dialog")).toHaveTextContent(/Example, not your work/u);
	},
};

const blockedReader = (
	blocker: MemberReviewReadiness["blocker"],
): PracticeProfileTourProps["page"] => ({
	...NEW_READER,
	firstRun: { workspaceName: "Acme", readiness: { ...READY, blocker } },
});

export const WhereYouAreNowBlocked: Story = {
	args: { startChapter: "how-it-works", page: blockedReader("NO_AI") },
	play: async () => {
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		await expect(screen.getByRole("dialog")).toHaveTextContent(
			/no review starts by itself on your work/u,
		);
		await walkChapter(blockedReader("NO_AI"), "how-it-works");
		await expect(
			screen.getByRole("dialog").querySelector("[aria-live='polite']"),
		).toHaveTextContent(/You chose No AI/u);
	},
};

export const WhereYouAreNowWithCards: Story = {
	args: { startChapter: "how-it-works", page: READER_WITH_CARDS },
	play: async () => {
		await walkChapter(READER_WITH_CARDS, "how-it-works");
		await expect(screen.getByRole("dialog")).toHaveTextContent(/You have \d+ open feedback cards/u);
	},
};

/** A reviewed reader with no card: the words must not read as a clean result. */
export const WhereYouAreNowReviewedNoCard: Story = {
	args: {
		startChapter: "how-it-works",
		page: {
			...NEW_READER,
			latestReview: LATEST_REVIEW,
			firstRun: {
				workspaceName: "Acme",
				readiness: { ...READY, approvalBeforePosting: true },
			},
		},
	},
	play: async ({ args }) => {
		await walkChapter(args.page, "how-it-works");
		const dialog = screen.getByRole("dialog");
		await expect(dialog).toHaveTextContent(/No open feedback right now/u);
		await expect(dialog).toHaveTextContent(/a workspace admin approves feedback/u);
	},
};

export const UnknownReadinessLeavesOutNow: Story = {
	args: { startChapter: "how-it-works", page: { ...NEW_READER, firstRun: undefined } },
	play: async ({ args }) => {
		await walkChapter(args.page, "how-it-works");
		await expect(screen.queryByRole("heading", { name: "Where you are now" })).toBeNull();
	},
};

export const YourProfileWithoutACard: Story = {
	args: { startChapter: "your-profile" },
	play: async () => {
		await walkChapter(NEW_READER, "your-profile");
		// The example stands alone under its caption, and the words say so too.
		await expect(screen.getByRole("region", { name: "Example, not your work" })).toBeVisible();
	},
};

export const YourProfileWithYourCard: Story = {
	args: { startChapter: "your-profile", page: READER_WITH_CARDS },
	play: async () => {
		await walkChapter(READER_WITH_CARDS, "your-profile");
		await expect(screen.getByRole("region", { name: "One of your open cards" })).toBeVisible();
		await expect(screen.queryByText("Example, not your work")).toBeNull();
	},
};

/** Nothing about the reader is stated before the page knows it. */
export const WhilePageLoads: Story = {
	args: {
		startChapter: "your-profile",
		page: { ...NEW_READER, firstRun: undefined, practices: [], state: { status: "loading" } },
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await expect(dialog).toHaveTextContent(/still loading/u);
		await expect(dialog).not.toHaveTextContent(/You have no open card|no review has recorded/u);
		await expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
	},
};

export const AfterFailedLoad: Story = {
	args: {
		startChapter: "how-it-works",
		page: {
			...NEW_READER,
			firstRun: undefined,
			practices: [],
			state: { status: "error", error: new Error("Server error"), onRetry: fn() },
		},
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await expect(within(dialog).getByRole("alert")).toHaveTextContent(
			/We could not load your practices/u,
		);
		await expect(dialog).not.toHaveTextContent(/has not chosen any practices|your real data/u);
		await expect(screen.queryByRole("button", { name: "Next" })).toBeNull();
	},
};

export const GitLabWords: Story = {
	decorators: [withProvider("GITLAB")],
	args: { startChapter: "your-profile", provider: "GITLAB" },
	play: async () => {
		const next = await screen.findByRole("button", { name: "Next" });
		for (let step = 0; step < 3; step += 1) {
			await userEvent.click(next);
		}
		await screen.findByRole("heading", { level: 3, name: "One card is one pattern" });
		await expect(screen.getByRole("dialog")).toHaveTextContent(/merge requests are made up/u);
		await expect(screen.getByRole("dialog")).toHaveTextContent(/!17/u);
	},
};

export const GoingDeeper: Story = {
	args: { startChapter: "going-deeper", page: READER_WITH_CARDS },
	play: async () => {
		await walkChapter(READER_WITH_CARDS, "going-deeper");
		// The last step hands over to the reader's own data.
		await expect(screen.getByRole("link", { name: "See all practice groups" })).toBeVisible();
	},
};

export const AcrossTheWorkspace: Story = {
	args: { startChapter: "across-the-workspace" },
	play: async () => {
		await walkChapter(NEW_READER, "across-the-workspace");
		await expect(screen.getByRole("button", { name: "Done" })).toHaveFocus();
	},
};

/** The example's bar counts nobody as the reader, so it states no standing for them. */
export const ExampleBarHasNoYou: Story = {
	args: { startChapter: "across-the-workspace" },
	play: async () => {
		await screen.findByRole("heading", {
			level: 3,
			name: "How the workspace splits on each group",
		});
		await expect(screen.queryByText("You")).toBeNull();
	},
};

export const KeyboardSteps: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		await waitFor(async () => {
			await expect(screen.getByRole("button", { name: "Next" })).toHaveFocus();
		});
		await userEvent.keyboard("{ArrowRight}");
		await screen.findByRole("heading", {
			level: 3,
			name: "A review looks at one practice at a time",
		});
		await userEvent.keyboard("{ArrowLeft}");
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		// Enter acts once, on the button that has focus.
		await userEvent.keyboard("{Enter}");
		await screen.findByRole("heading", {
			level: 3,
			name: "A review looks at one practice at a time",
		});
		// Back leaves with the first step, and focus goes to the way on.
		await userEvent.click(screen.getByRole("button", { name: "Back" }));
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		await waitFor(async () => {
			await expect(screen.getByRole("button", { name: "Next" })).toHaveFocus();
		});
	},
};

export const WordsAreAnnounced: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await expect(dialog).toHaveAccessibleDescription(/It starts with your work/u);
		const words = dialog.querySelector("[aria-live='polite']");
		await expect(words).toHaveTextContent(/It starts with your work/u);
		await userEvent.click(screen.getByRole("button", { name: "Next" }));
		await expect(words?.isConnected).toBe(true);
		await expect(words).toHaveTextContent(/A review looks at one practice/u);
	},
};

/** The main path: the region exists before the chapter does, and the announcement names the chapter. */
export const ChapterFromTheMenuIsAnnounced: Story = {
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		const words = dialog.querySelector("[aria-live='polite']");
		await expect(words).toHaveTextContent(/Choose what you want to know/u);
		await userEvent.click(within(dialog).getByRole("button", { name: /Going deeper/u }));
		await screen.findByRole("heading", { level: 3, name: "Each click opens one more panel" });
		await expect(words?.isConnected).toBe(true);
		await expect(words).toHaveTextContent(/Going deeper\. Step 1 of 4\./u);
		await expect(words).toHaveTextContent(/Each click opens one more panel/u);
	},
};

export const HeldAndModifiedKeysDoNotStep: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		for (const init of [{ repeat: true }, { altKey: true }, { metaKey: true }]) {
			dialog.dispatchEvent(
				new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true, ...init }),
			);
		}
		await expect(screen.getByRole("heading", { level: 3 })).toHaveTextContent(
			"It starts with your work",
		);
	},
};

export const EscapeCloses: Story = {
	args: { startChapter: "how-it-works" },
	play: async ({ args }) => {
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		await userEvent.keyboard("{Escape}");
		await expect(args.onClose).toHaveBeenCalled();
	},
};

export const SmallScreen: Story = {
	args: { startChapter: "your-profile", page: READER_WITH_CARDS },
	globals: { viewport: { value: "reflow" } },
	play: async () => {
		await screen.findByRole("heading", { level: 3, name: "Your newest review" });
		await expectNoPageOverflow();
		await expect(screen.getByRole("button", { name: "Next" })).toBeVisible();
	},
};

export const MenuOnASmallScreen: Story = {
	globals: { viewport: { value: "reflow" } },
	play: async () => {
		await screen.findByRole("dialog", { name: "Guided tour" });
		await expectNoPageOverflow();
	},
};

export const Dark: Story = {
	args: { startChapter: "how-it-works" },
	globals: { theme: "dark" },
};
