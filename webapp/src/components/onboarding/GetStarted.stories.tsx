import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/stories/reflow";
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
		render: <button type="button" onClick={fn()} />,
		finished: false,
	},
	{
		id: "your-profile",
		title: "Learn to read a feedback card",
		summary: "A short tour of the parts of a card and what you can do with it.",
		render: <button type="button" />,
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

/**
 * A new member's next steps, from the server's facts: each setup row shows where it stands and its
 * one way on, then ways to learn the page.
 */
const meta = {
	component: GetStarted,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	parameters: { chromatic: { viewports: [320, 1440] } },
	args: { state: ready([REVIEW_TO_DO]), fold: { open: true, onOpenChange: fn() } },
	// The page keeps the reader's choice to hide the list; here a story's state stands in for it.
	render: (args) => (
		<Stateful initial={args.fold?.open ?? true}>
			{(open, setOpen) => (
				<GetStarted
					{...args}
					fold={{
						open,
						onOpenChange: (next) => {
							setOpen(next);
							args.fold?.onOpenChange(next);
						},
					}}
				/>
			)}
		</Stateful>
	),
} satisfies Meta<typeof GetStarted>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Day one: the review is the reader's to do, with what starts one. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const list = canvas.getByRole("region", { name: "Get started" });
		await expect(list).toHaveTextContent("0 of 1 done");
		await expect(canvas.getByText("To do")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "See how your work becomes feedback" }),
		).toBeVisible();
		await expect(canvas.getByRole("link", { name: "Ask Hephaestus a question" })).toBeVisible();
	},
};

/** A choice and an account still to do. The review waits for them, so it has no way on of its own. */
export const SetupToDo: Story = {
	args: {
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
		await expect(canvas.getByRole("region", { name: "Get started" })).toHaveTextContent(
			"0 of 3 done",
		);
		await expect(canvas.getAllByText("To do")).toHaveLength(2);
		await expect(canvas.getByText("Cannot start yet")).toBeVisible();
		await expect(canvas.getAllByRole("link", { name: /Choose your AI/u })).toHaveLength(1);
	},
};

/** The first review is running: nothing to do, and no time promised. */
export const ReviewInProgress: Story = {
	args: {
		state: ready([
			AI_CHOICE_DONE,
			{
				id: "first-review",
				title: "Your first practice review",
				status: "in-progress",
				summary: (
					<>
						<span className="block font-medium text-foreground">Your first review is running</span>
						It is on #12. Its result appears on this page when it finishes.
					</>
				),
			},
		]),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("region", { name: "Get started" })).toHaveTextContent(
			"1 of 2 done",
		);
		await expect(canvas.getByText("In progress")).toBeVisible();
		await expect(canvas.getByText("Done")).toBeVisible();
	},
};

/** A chapter this account finished in this browser carries its mark; the others do not. */
export const FinishedChapter: Story = {
	args: {
		state: ready(
			[REVIEW_TO_DO],
			LEARN.map((row) => ({ ...row, finished: row.id === "how-it-works" })),
		),
	},
	play: async ({ canvas }) => {
		const finished = canvas.getByText("Finished");
		await expect(finished.closest("li")).toHaveTextContent("See how your work becomes feedback");
		await expect(canvas.getAllByText("Finished")).toHaveLength(1);
	},
};

/** Hide keeps the heading and the count, and tells the page, which keeps the choice. */
export const Hidden: Story = {
	play: async ({ args, canvas }) => {
		const fold = canvas.getByRole("button", { name: "Get started" });
		await userEvent.click(fold);
		await expect(fold).toHaveAttribute("aria-expanded", "false");
		await expect(args.fold?.onOpenChange).toHaveBeenLastCalledWith(false);
		await expect(canvas.getByText("Set up")).not.toBeVisible();
		await expect(canvas.getByText("0 of 1 done")).toBeVisible();
		await userEvent.click(fold);
		await expect(canvas.getByText("Set up")).toBeVisible();
	},
};

/** Every setup step is done: the list is gone, and only its status line stays. */
export const AllDone: Story = {
	args: { state: ready([AI_CHOICE_DONE, { ...REVIEW_TO_DO, status: "done", action: undefined }]) },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", { name: "Get started" })).toBeNull();
		await expect(canvas.getByRole("status")).toBeEmptyDOMElement();
	},
};

/**
 * While the facts load, and when a read fails, nothing shows: the list is optional guidance that
 * most readers never see.
 */
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("region", { name: "Get started" })).toBeNull();
		await expect(canvas.getByRole("status")).toBeEmptyDOMElement();
	},
};

/** The longest rows wrap at 320 px without widening the page. */
export const MobileReflow: Story = {
	args: {
		state: ready([
			{
				...AI_CHOICE_TO_DO,
				summary:
					"Hephaestus reviews your work and answers you only with the AI that you allow. The interdisciplinary software engineering project course asks every member first.",
			},
			{ ...ACCOUNT_TO_DO, title: "Connect your Interdisciplinary Course GitLab account" },
			REVIEW_TO_DO,
		]),
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};

/** Dark mode: the status badges and the finished mark keep their contrast. */
export const Dark: Story = {
	args: FinishedChapter.args,
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Finished")).toBeVisible();
	},
};
