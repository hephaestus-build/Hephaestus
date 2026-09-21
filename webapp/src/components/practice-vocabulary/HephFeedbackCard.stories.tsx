import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { text } from "@/components/common/feedback-text";
import { ARTIFACT_KIND } from "@/lib/artifact-kinds";
import { pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectTouchTarget } from "@/test/controls";

import { HephFeedbackCard } from "./HephFeedbackCard";

/**
 * Heph's card as the page's overview and a group's level speak through it: what is holding up
 * well, the caller's own blocks beneath it, and the reviewed work in the footer. The card draws
 * only what it was given — a block with nothing to say is left out — and with nothing at all it
 * draws nothing.
 */
const meta = {
	title: "Shared/Practice vocabulary/Heph feedback card",
	component: HephFeedbackCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		holdingUp: [
			{
				practiceSlug: "ready-and-traceable-handoff",
				practiceName: "Mark the change ready and link its issue",
				statement: "Every merge request names its issue,",
				note: [text("held across nine pull requests.")],
			},
			{
				practiceSlug: "describe-what-and-why",
				practiceName: "Describe what changed and why",
				statement: "Resolved by the work",
				note: [text("after #20, #21 and #22 came back clean.")],
				resolved: true,
			},
		],
		holdingUpNote: "Another two practices held too.",
		reviewedWork: [
			{ kind: ARTIFACT_KIND.pullRequest, items: [20, 21, 22].map(pullRequest) },
			{ kind: ARTIFACT_KIND.issue, items: [{ id: "13", kind: ARTIFACT_KIND.issue, label: "#13" }] },
		],
		blocks: [
			{
				label: "What changed",
				content: <p className="text-sm">One piece of feedback arrived, on scoping a change.</p>,
			},
		],
		onOpenPractice: fn(),
	},
} satisfies Meta<typeof HephFeedbackCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Held rows, a block after them, and the footer counting the work under the number rule. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("What is holding up well")).toBeVisible();
		await expect(canvas.getByText("What changed")).toBeVisible();
		await expect(canvas.getByText("Another two practices held too.")).toBeVisible();
		// The footer counts each kind: three pull requests, one issue.
		await expect(canvas.getByText("pull requests")).toBeVisible();
		await expect(canvas.getByText("issue")).toBeVisible();
		// A held row names its practice as the one grey pill, never as bold text or a bare link.
		const pill = canvas.getByRole("button", { name: "Describe what changed and why" });
		await expect(pill).toHaveAttribute("data-slot", "badge");
		await expectTouchTarget(pill);
		await userEvent.click(pill);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("describe-what-and-why");
		// The held tick is 14 px of icon and a tooltip; its pointer target is still the minimum.
		for (const tick of canvas.getAllByRole("button", { name: "Strength:" })) {
			await expectTouchTarget(tick);
		}
	},
};

/**
 * A held practice the catalog has no sentence for: the row is the pill and what the work showed,
 * and no words are put in the catalog's mouth.
 */
export const HeldWithoutASentence: Story = {
	args: {
		holdingUp: [
			{
				practiceSlug: "own-practice",
				practiceName: "A practice of our own",
				note: [text("Held across four pull requests")],
			},
		],
		holdingUpNote: undefined,
		blocks: [],
	},
	play: async ({ canvas }) => {
		const row = canvas.getByRole("button", { name: "A practice of our own" }).closest("li");
		if (!row) throw new Error("Every held row is a list item");
		await expect(row).toHaveTextContent(
			/^Strength: A practice of our ownHeld across four pull requests$/,
		);
		await expect(canvas.queryByText("Going well")).toBeNull();
	},
};

/** The skeleton mirrors the card at rest, so the page does not jump when the overview lands. */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("What is holding up well")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

/** Nothing held, no blocks and no reviewed work: the card is not drawn, avatar included. */
export const NothingToSay: Story = {
	args: { holdingUp: [], holdingUpNote: undefined, reviewedWork: [], blocks: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Heph")).toBeNull();
	},
};
