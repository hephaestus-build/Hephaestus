import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import type { MemberReviewReadiness } from "@/api/types.gen";
import { DEFAULT_FEEDBACK_TAB } from "@/components/practice-profile/practice-profile-search";
import { PracticeProfilePage } from "@/components/practice-profile/PracticeProfilePage";
import { withProvider } from "@/stories/decorators";
import { ALL_FEEDBACK_CARDS } from "@/stories/practice-feedback-cards-story-mock-data";
import {
	groups,
	LATEST_REVIEW,
	practiceStandings,
	practicesByGroup,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import { exampleProfile } from "./example-profile";
import { PracticeProfileTour, type PracticeProfileTourProps } from "./PracticeProfileTour";
import { type TourChapterId, tourChapters } from "./tour-chapters";

const STORAGE_KEY = "hephaestus:guided-tour:practice-profile";

/** Long enough for a level to slide in and the page to scroll to the part. */
const SETTLE = { timeout: 5000 } as const;

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
	// As on the reader's page: a card draws its answers, which the last steps of Your profile light.
	ratingProps: () => ({ onRate: fn(), onDisagree: fn(), onResolve: fn() }),
};

const meta = {
	component: PracticeProfileTour,
	decorators: [withProvider("GITHUB")],
	parameters: { layout: "fullscreen" },
	beforeEach: () => {
		window.localStorage.removeItem(STORAGE_KEY);
	},
	// The reader's own page under the tour, as the route renders it: the steps about it light its parts.
	render: (args) => (
		<>
			<main className="p-4">
				<PracticeProfilePage {...args.page} />
			</main>
			<PracticeProfileTour {...args} />
		</>
	),
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

const guide = () => screen.getByRole("dialog", { name: "Guided tour" });

/** The example's caption is on top: nothing, not even an open level, covers its middle. */
async function expectExampleCaptionOnTop() {
	const caption = document.querySelector<HTMLElement>("[data-tour-example-caption]");
	await expect(caption).toHaveTextContent("Example, not your work");
	const box = caption?.getBoundingClientRect();
	const hit = box && document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
	await expect(hit).toBe(caption);
}

/**
 * Presses Next through a chapter. Each step names itself, and a step that points at a part must find
 * it: a renamed or lost `data-tour-slot` fails here, not in front of a reader.
 */
async function walkChapter(page: PracticeProfileTourProps["page"], chapterId: TourChapterId) {
	const chapter = tourChapters(
		{ workspaceSlug: "acme", workspaceName: "Acme", provider: "GITHUB", page, practicesByGroup },
		exampleProfile(new Date(), "GITHUB"),
	).find((each) => each.id === chapterId);
	const steps = chapter?.steps ?? [];
	for (const [index, step] of steps.entries()) {
		await screen.findByRole("heading", { level: 3, name: step.title });
		await expect(
			screen.getByText(`Step ${index + 1} of ${steps.length}`, { selector: "span:not(.sr-only)" }),
		).toBeVisible();
		if (step.spot === undefined) {
			await expect(guide()).toHaveAttribute("data-spot", "none");
		} else {
			await waitFor(
				async () =>
					expect(guide(), `step "${step.id}" finds its part`).toHaveAttribute("data-spot", "found"),
				SETTLE,
			);
		}
		if (index < steps.length - 1) {
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
		await expect(within(dialog).getByRole("button", { name: "Close" })).toBeVisible();
		await expect(within(dialog).queryByRole("button", { name: "Start over" })).toBeNull();
	},
};

/** The page's first-visit offer: what the tour is, where it comes back from, and a plain way out. */
export const FirstVisit: Story = {
	args: { firstVisit: true },
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await expect(dialog).toHaveAccessibleDescription(/“Take the tour” brings it back/u);
		await userEvent.click(within(dialog).getByRole("button", { name: "Not now" }));
		await expect(args.onClose).toHaveBeenCalled();
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
		// The finished chapter shows its check in the chapter strip too.
		await expect(
			screen.getByRole("button", { name: /^Chapter 1 How it works ?, done$/u }),
		).toBeVisible();
	},
};

export const StartOverClearsProgress: Story = {
	beforeEach: MenuContinues.beforeEach,
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Start over" }));
		await expect(screen.queryByText(/Continue at step/u)).toBeNull();
		await expect(within(guide()).queryByText("Done")).toBeNull();
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

/** A workspace with no practices yet: the scenes show the example, and say so. */
export const HowItWorksWithoutPractices: Story = {
	args: {
		startChapter: "how-it-works",
		page: { ...NEW_READER, practices: [], groups: [] },
		practicesByGroup: {},
	},
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Next" }));
		await userEvent.click(screen.getByRole("button", { name: "Next" }));
		await screen.findByRole("heading", {
			level: 3,
			name: "A review looks at one practice at a time",
		});
		await expect(guide()).toHaveTextContent(/Acme has not chosen any practices yet/u);
		await expect(guide()).toHaveTextContent(/Example, not your work/u);
	},
};

export const HowItWorksSaysWhatStartsAReview: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Next" }));
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		await expect(guide()).toHaveTextContent(/In Acme, a review of your work can start when:/u);
		await expect(guide()).toHaveTextContent(/Example, not your work/u);
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
		await walkChapter(blockedReader("NO_AI"), "how-it-works");
		await expect(guide().querySelector("[aria-live='polite']")).toHaveTextContent(
			/You chose No AI/u,
		);
	},
};

export const WhereYouAreNowWithCards: Story = {
	args: { startChapter: "how-it-works", page: READER_WITH_CARDS },
	play: async () => {
		await walkChapter(READER_WITH_CARDS, "how-it-works");
		await expect(guide()).toHaveTextContent(/You have \d+ open feedback cards/u);
	},
};

/** A reviewed reader with no card: the words must not read as a clean result. */
export const WhereYouAreNowReviewedNoCard: Story = {
	args: {
		startChapter: "how-it-works",
		page: {
			...NEW_READER,
			latestReview: LATEST_REVIEW,
			firstRun: { workspaceName: "Acme", readiness: { ...READY, approvalBeforePosting: true } },
		},
	},
	play: async ({ args }) => {
		await walkChapter(args.page, "how-it-works");
		await expect(guide()).toHaveTextContent(/No open feedback right now/u);
		await expect(guide()).toHaveTextContent(/a workspace admin approves feedback/u);
	},
};

export const UnknownReadinessLeavesOutNow: Story = {
	args: { startChapter: "how-it-works", page: { ...NEW_READER, firstRun: undefined } },
	play: async ({ args }) => {
		await walkChapter(args.page, "how-it-works");
		await expect(screen.queryByRole("heading", { name: "Where you are now" })).toBeNull();
	},
};

/** Without a card of their own, the card steps stand on the example, under its caption. */
export const YourProfileWithoutACard: Story = {
	args: { startChapter: "your-profile" },
	play: async () => {
		await walkChapter(NEW_READER, "your-profile");
		await expectExampleCaptionOnTop();
		await expect(guide()).toHaveTextContent(/Example, not your work\./u);
	},
};

/** With a card of their own, every step lights the reader's real page, and no example appears. */
export const YourProfileWithYourCard: Story = {
	args: { startChapter: "your-profile", page: READER_WITH_CARDS },
	play: async () => {
		await walkChapter(READER_WITH_CARDS, "your-profile");
		await expect(guide()).toHaveTextContent(/Your Practice profile, with your real data/u);
		await expect(document.querySelector("[data-tour-example-caption]")).toBeNull();
	},
};

/** On the Resolved tab the reader's open card is not on the page, so the example stands in. */
export const YourProfileOnAnotherTab: Story = {
	args: { startChapter: "your-profile", page: { ...READER_WITH_CARDS, feedbackTab: "resolved" } },
	play: async ({ args }) => {
		await walkChapter(args.page, "your-profile");
		await expectExampleCaptionOnTop();
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
		for (let step = 0; step < 3; step += 1) {
			await userEvent.click(await screen.findByRole("button", { name: "Next" }));
		}
		await screen.findByRole("heading", { level: 3, name: "One card is one pattern" });
		await expect(guide()).toHaveTextContent(/merge requests are made up/u);
		await waitFor(
			async () => expect(screen.getAllByText(/!17/u).length).toBeGreaterThan(0),
			SETTLE,
		);
	},
};

/** The real levels slide in over the example, and the last step hands over to the reader's own data. */
export const GoingDeeper: Story = {
	args: { startChapter: "going-deeper", page: READER_WITH_CARDS },
	play: async () => {
		await walkChapter(READER_WITH_CARDS, "going-deeper");
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

/** The example's bars count nobody as the reader, so they state no standing for them. */
export const ExampleHasNoYou: Story = {
	args: { startChapter: "across-the-workspace" },
	play: async () => {
		await waitFor(async () => expect(guide()).toHaveAttribute("data-spot", "found"), SETTLE);
		await expectExampleCaptionOnTop();
		const splits = document.querySelector<HTMLElement>(
			'[data-tour-example] [data-tour-slot="splits"]',
		);
		await expect(splits).not.toBeNull();
		await expect(splits?.textContent).not.toMatch(/\bYou\b/u);
	},
};

/** The chapter strip names the chapters and starts any of them. */
export const ChaptersJump: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		await screen.findByRole("heading", { level: 3, name: "From your work to your profile" });
		await expect(screen.getByRole("button", { name: "Chapter 1 How it works" })).toHaveAttribute(
			"aria-current",
			"step",
		);
		await userEvent.click(screen.getByRole("button", { name: "Chapter 3 Going deeper" }));
		await screen.findByRole("heading", { level: 3, name: "Each press opens one more panel" });
		await userEvent.click(screen.getByRole("button", { name: "All chapters" }));
		await expect(
			await screen.findByRole("button", { name: /Going deeper.*Continue|Going deeper/u }),
		).toBeVisible();
	},
};

export const KeyboardSteps: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		await waitFor(async () => {
			await expect(screen.getByRole("button", { name: "Next" })).toHaveFocus();
		});
		await userEvent.keyboard("{ArrowRight}");
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		await userEvent.keyboard("{ArrowLeft}");
		await screen.findByRole("heading", { level: 3, name: "From your work to your profile" });
		// Enter acts once, on the button that has focus.
		await userEvent.keyboard("{Enter}");
		await screen.findByRole("heading", { level: 3, name: "It starts with your work" });
		// Back leaves with the first step, and focus goes to the way on.
		await userEvent.click(screen.getByRole("button", { name: "Back" }));
		await screen.findByRole("heading", { level: 3, name: "From your work to your profile" });
		await waitFor(async () => {
			await expect(screen.getByRole("button", { name: "Next" })).toHaveFocus();
		});
	},
};

/** Tab stays inside the guide, also over a lit part of the page and over the example's levels. */
export const FocusStaysInTheGuide: Story = {
	args: { startChapter: "going-deeper" },
	play: async () => {
		for (let step = 0; step < 2; step += 1) {
			await userEvent.click(await screen.findByRole("button", { name: "Next" }));
		}
		await screen.findByRole("heading", { level: 3, name: "All practice groups" });
		await waitFor(async () => expect(guide()).toHaveAttribute("data-spot", "found"), SETTLE);
		// A level covers most of the example, and the caption stays in sight over it.
		await expectExampleCaptionOnTop();
		for (let press = 0; press < 12; press += 1) {
			await userEvent.tab();
			// Base UI's focus guards sit beside the guide and hand focus back at once.
			await waitFor(async () => expect(guide().contains(document.activeElement)).toBe(true));
		}
	},
};

export const WordsAreAnnounced: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await expect(dialog).toHaveAccessibleDescription(/From your work to your profile/u);
		const words = dialog.querySelector("[aria-live='polite']");
		await userEvent.click(screen.getByRole("button", { name: "Next" }));
		await expect(words?.isConnected).toBe(true);
		await expect(words).toHaveTextContent(/How it works\. Step 2 of \d+\./u);
		await expect(words).toHaveTextContent(/It starts with your work/u);
	},
};

/** The main path: the region exists before the chapter does, and the announcement names the chapter. */
export const ChapterFromTheMenuIsAnnounced: Story = {
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		const words = dialog.querySelector("[aria-live='polite']");
		await expect(words).toHaveTextContent(/Choose what you want to know/u);
		await userEvent.click(within(dialog).getByRole("button", { name: /Going deeper/u }));
		await screen.findByRole("heading", { level: 3, name: "Each press opens one more panel" });
		await expect(words?.isConnected).toBe(true);
		await expect(words).toHaveTextContent(/Going deeper\. Step 1 of \d+\./u);
	},
};

export const HeldAndModifiedKeysDoNotStep: Story = {
	args: { startChapter: "how-it-works" },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Guided tour" });
		await screen.findByRole("heading", { level: 3, name: "From your work to your profile" });
		for (const init of [{ repeat: true }, { altKey: true }, { metaKey: true }]) {
			dialog.dispatchEvent(
				new KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true, ...init }),
			);
		}
		await expect(screen.getByRole("heading", { level: 3 })).toHaveTextContent(
			"From your work to your profile",
		);
	},
};

export const EscapeCloses: Story = {
	args: { startChapter: "how-it-works" },
	play: async ({ args }) => {
		await screen.findByRole("heading", { level: 3, name: "From your work to your profile" });
		await userEvent.keyboard("{Escape}");
		await expect(args.onClose).toHaveBeenCalled();
	},
};

/** On a phone the guide is a sheet over or under the lit part, and nothing scrolls sideways. */
export const SmallScreen: Story = {
	args: { startChapter: "your-profile", page: READER_WITH_CARDS },
	globals: { viewport: { value: "reflow" } },
	play: async () => {
		await screen.findByRole("heading", { level: 3, name: "Your newest review" });
		await waitFor(async () => expect(guide()).toHaveAttribute("data-spot", "found"), SETTLE);
		await expectNoPageOverflow();
		await expect(screen.getByRole("button", { name: "Next" })).toBeVisible();
	},
};

export const LevelsOnASmallScreen: Story = {
	args: { startChapter: "going-deeper" },
	globals: { viewport: { value: "reflow" } },
	play: async () => {
		for (let step = 0; step < 2; step += 1) {
			await userEvent.click(await screen.findByRole("button", { name: "Next" }));
		}
		await waitFor(async () => expect(guide()).toHaveAttribute("data-spot", "found"), SETTLE);
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
	args: { startChapter: "going-deeper" },
	globals: { theme: "dark" },
};
