import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { expectNoOverflowingElement, expectWithinViewport } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import {
	GetStarted,
	type GetStartedLearnRow,
	type GetStartedSetupRow,
	type GetStartedState,
} from "./GetStarted";

const AI_CHOICE_TO_DO: GetStartedSetupRow = {
	id: "ai-choice",
	title: "Choose how AI handles your work",
	status: "to-do",
	summary: "Hephaestus reviews your work and answers you only with the AI that you allow.",
	action: <a href="#ai-choice">Choose your AI</a>,
};

const AI_CHOICE_DONE: GetStartedSetupRow = {
	id: "ai-choice",
	title: "Choose how AI handles your work",
	status: "done",
	summary: "You chose In-house.",
};

const ACCOUNT_TO_DO: GetStartedSetupRow = {
	id: "account:3",
	title: "Connect your GitLab account",
	status: "to-do",
	summary: "Acme uses this account to know which work is yours.",
	action: <a href="#connect">Connect GitLab</a>,
};

const REVIEW_TO_DO: GetStartedSetupRow = {
	id: "first-review",
	title: "Your first practice review",
	status: "to-do",
	summary: (
		<>
			<span className="block font-medium text-foreground">No review of your work yet</span>
			Hephaestus reviews your work against the practices that Acme chose.
		</>
	),
	notes: <p>A review of your work can start when you open a pull request.</p>,
	action: <a href="#practices">See what this workspace reviews</a>,
};

const LEARN: GetStartedLearnRow[] = [
	{
		id: "how-it-works",
		title: "See how your work becomes feedback",
		summary: "A short tour of this page, from your work to a feedback card.",
		render: <a href="#tour-how-it-works" />,
		finished: false,
	},
	{
		id: "your-profile",
		title: "Learn to read a feedback card",
		summary: "A short tour of the parts of a card and what you can do with it.",
		render: <a href="#tour-your-profile" />,
		finished: false,
	},
	{
		id: "conversation",
		title: "Ask Hephaestus a question",
		summary: "Ask about your feedback, the practices, or your recent work.",
		render: <a href="#conversation" />,
		finished: false,
	},
];

const ready = (setup: GetStartedSetupRow[], learn = LEARN): GetStartedState => ({
	status: "ready",
	setup,
	learn,
});

const PARTLY_DONE = ready([AI_CHOICE_DONE, ACCOUNT_TO_DO, REVIEW_TO_DO]);

/** The card as a reader sees it, over the page it opens on. */
const card = async () => within(await screen.findByRole("dialog", { name: "Get started" }));

/**
 * A new member's next steps in the bottom-right corner: a pill with the count, and a non-modal card
 * that it opens. Each setup row shows where it stands and its one way on, from the server's facts;
 * the learn rows lead to tour chapters and to Ask Hephaestus.
 */
const meta = {
	component: GetStarted,
	tags: ["autodocs"],
	parameters: { layout: "fullscreen", chromatic: { viewports: [320, 1440] } },
	args: {
		state: PARTLY_DONE,
		open: false,
		opening: "reader",
		onOpenChange: fn(),
		onHide: fn(),
	},
	// The corner holds the card's open state; here a story's state stands in for it.
	render: (args) => (
		<Stateful initial={args.open}>
			{(open, setOpen) => (
				<div className="min-h-screen bg-background p-8">
					<h1 className="text-2xl font-semibold">Activity</h1>
					<p className="mt-2 text-muted-foreground">The page the reader came to.</p>
					<GetStarted
						{...args}
						open={open}
						onOpenChange={(next, dismissed) => {
							setOpen(next);
							args.onOpenChange(next, dismissed);
						}}
					/>
				</div>
			)}
		</Stateful>
	),
} satisfies Meta<typeof GetStarted>;

export default meta;
type Story = StoryObj<typeof meta>;

/**
 * Partly done: the pill names the count. The reader's press opens the card and takes focus to the
 * first step that is still theirs; Escape closes it and gives focus back to the pill.
 */
export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const pill = canvas.getByRole("button", { name: "Get started 1 of 3 done" });
		await expect(pill).toHaveAttribute("aria-expanded", "false");
		await userEvent.click(pill);
		const opened = await card();
		await expectSettledVisible(opened.getByText("Set up"));
		await expect(pill).toHaveAttribute("aria-expanded", "true");
		await expect(opened.getByText("1 of 3 done")).toBeVisible();
		await expect(opened.getAllByText("To do")).toHaveLength(2);
		await waitFor(async () =>
			expect(opened.getByRole("link", { name: "Connect GitLab" })).toHaveFocus(),
		);
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => expect(pill).toHaveFocus());
		await expect(args.onOpenChange).toHaveBeenLastCalledWith(false, true);
	},
};

/**
 * The first visit's open: the card shows, focus stays where the reader is, and a polite status
 * says that it opened. It is the only thing that opens by itself on a first visit.
 */
export const FirstVisit: Story = {
	args: { open: true, opening: "self" },
	play: async ({ canvas }) => {
		const opened = await card();
		await expectSettledVisible(opened.getByText("Set up"));
		await expect(screen.getByRole("dialog", { name: "Get started" })).not.toContainElement(
			document.activeElement instanceof HTMLElement ? document.activeElement : null,
		);
		await expect(canvas.getByRole("status")).toHaveTextContent(
			"Get started opened in the corner. 1 of 3 steps are done.",
		);
	},
};

/** A choice and an account still to do. The review waits for them, so it has no way on of its own. */
export const SetupToDo: Story = {
	args: {
		open: true,
		state: ready([
			AI_CHOICE_TO_DO,
			ACCOUNT_TO_DO,
			{
				id: "first-review",
				title: "Your first practice review",
				status: "cannot-start",
				summary: (
					<>
						<span className="block font-medium text-foreground">Choose your AI to get reviews</span>
						Acme reviews your work only after you choose which AI may handle it.
					</>
				),
			},
		]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Get started 0 of 3 done" })).toBeVisible();
		const opened = await card();
		await expect(opened.getAllByText("To do")).toHaveLength(2);
		await expect(opened.getByText("Cannot start yet")).toBeVisible();
		await expect(opened.getAllByRole("link", { name: /Choose your AI/u })).toHaveLength(1);
	},
};

/** The first review is running: nothing to do, and no time promised. */
export const ReviewInProgress: Story = {
	args: {
		open: true,
		state: ready([
			AI_CHOICE_DONE,
			{
				id: "first-review",
				title: "Your first practice review",
				status: "in-progress",
				summary: (
					<>
						<span className="block font-medium text-foreground">Your first review is running</span>
						It is on #12. Its result appears on your Practice profile when it finishes.
					</>
				),
			},
		]),
	},
	play: async () => {
		const opened = await card();
		await expect(opened.getByText("1 of 2 done")).toBeVisible();
		await expect(opened.getByText("In progress")).toBeVisible();
		await expect(opened.getByText("Done")).toBeVisible();
	},
};

/** A chapter this account finished in this browser carries its mark; the others do not. */
export const FinishedChapter: Story = {
	args: {
		open: true,
		state: ready(
			[REVIEW_TO_DO],
			LEARN.map((row) => ({ ...row, finished: row.id === "how-it-works" })),
		),
	},
	play: async () => {
		const opened = await card();
		const finished = opened.getByText("Finished");
		await expect(finished.closest("li")).toHaveTextContent("See how your work becomes feedback");
		await expect(opened.getAllByText("Finished")).toHaveLength(1);
	},
};

/** Hide takes the list out of the corner; the corner keeps the choice, and the account menu reverses it. */
export const Hidden: Story = {
	args: { open: true },
	play: async ({ args, userEvent }) => {
		const opened = await card();
		await userEvent.click(opened.getByRole("button", { name: "Hide this list" }));
		await expect(args.onHide).toHaveBeenCalledOnce();
	},
};

/**
 * Every setup step is done: the card says so and offers Close, not Hide. Closing it is what takes
 * the pill away for good.
 */
export const AllDone: Story = {
	args: {
		open: true,
		state: ready([AI_CHOICE_DONE, { ...REVIEW_TO_DO, status: "done", action: undefined }]),
	},
	play: async ({ args, canvas, userEvent }) => {
		await expect(canvas.getByRole("button", { name: "Get started 2 of 2 done" })).toBeVisible();
		const opened = await card();
		await expect(opened.getByText(/^Every step is done\./u)).toBeVisible();
		await expect(opened.queryByRole("button", { name: "Hide this list" })).toBeNull();
		await userEvent.click(opened.getByRole("button", { name: "Close" }));
		await expect(args.onOpenChange).toHaveBeenLastCalledWith(false, true);
	},
};

/**
 * Nothing to set up, as for a member who opens Get started from the account menu in a workspace that
 * asks nothing of them: no count, and only the ways to learn.
 */
export const Empty: Story = {
	args: { open: true, state: ready([]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Get started" })).toBeVisible();
		const opened = await card();
		await expect(opened.getByText(/^Nothing to set up here\./u)).toBeVisible();
		await expect(opened.queryByText("Set up")).toBeNull();
		await expect(opened.queryByText(/0 of 0/u)).toBeNull();
	},
};

/** While the facts load, the pill has no count yet and the card holds the rows' shape. */
export const Loading: Story = {
	args: { open: true, state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Get started" })).toBeVisible();
		const opened = await card();
		await expect(opened.queryByText("Set up")).toBeNull();
		await expect(canvas.getByRole("status")).toBeEmptyDOMElement();
	},
};

/** A read that failed says so in the card and offers a retry; the page itself is not affected. */
export const ReadFailed: Story = {
	args: { open: true, state: { status: "error", onRetry: fn() } },
	play: async ({ args, userEvent }) => {
		const opened = await card();
		await expect(opened.getByText(/could not load/u)).toBeVisible();
		await userEvent.click(opened.getByRole("button", { name: "Try again" }));
		if (args.state.status === "error") {
			await expect(args.state.onRetry).toHaveBeenCalledOnce();
		}
	},
};

/**
 * At 320px the pill keeps its icon and count, and its name keeps both words. The card is the
 * viewport less a 16px gutter on each side, and the longest rows wrap inside it.
 */
export const MobileReflow: Story = {
	args: {
		open: true,
		state: ready([
			{
				...AI_CHOICE_TO_DO,
				summary:
					"Hephaestus reviews your work and answers you only with the AI that you allow. Every member of this workspace answers this question before their first review.",
			},
			{ ...ACCOUNT_TO_DO, title: "Connect your Software Engineering Lab GitLab account" },
			REVIEW_TO_DO,
		]),
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		const pill = canvas.getByRole("button", { name: "Get started 0 of 3 done" });
		// The words are visually hidden at this width, and the name still carries them.
		await expect(
			within(pill).getByText("Get started").getBoundingClientRect().width,
		).toBeLessThanOrEqual(1);
		const opened = await card();
		await expectSettledVisible(opened.getByText("Set up"));
		await expectWithinViewport(screen.getByRole("dialog", { name: "Get started" }));
		await expectNoOverflowingElement();
	},
};

/** Dark mode: the status badges and the finished mark keep their contrast. */
export const Dark: Story = {
	args: FinishedChapter.args,
	globals: { theme: "dark" },
	play: async () => {
		const opened = await card();
		await expect(opened.getByText("Finished")).toBeVisible();
	},
};
