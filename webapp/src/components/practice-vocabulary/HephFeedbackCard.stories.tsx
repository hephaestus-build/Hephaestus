import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, within } from "storybook/test";

import { settledPopup } from "@/stories/overlay";

import { ARTIFACT_KIND } from "@/lib/artifact-kinds";
import { pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectTouchTarget } from "@/test/controls";

import { ATTENTION_DEFS } from "./attention-defs";
import { text } from "./feedback-text";
import { HephFeedbackCard } from "./HephFeedbackCard";

/**
 * Heph's card as the page's overview and a group's level speak through it: what is holding up
 * well, the caller's own blocks beneath it, and the reviewed work in the footer. The card draws
 * only what it was given — a block with nothing to say is left out — and with nothing at all it
 * draws nothing.
 */
const meta = {
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
		onReadFeedback: fn(),
	},
} satisfies Meta<typeof HephFeedbackCard>;

/** The two kinds of row the attention block draws, in the precedence the composer hands them over. */
const ATTENTION_ROWS = [
	{
		feedbackId: "acceptance-criteria",
		practiceSlug: "honours-linked-issue-acceptance-criteria",
		practiceName: "Say which acceptance criteria are done",
		def: ATTENTION_DEFS.reset,
		sentence: [
			text("Back to 0 of 3 clean after "),
			{ type: "work", ref: pullRequest(22) } as const,
		],
	},
	{
		feedbackId: "scope-one-concern-new",
		practiceSlug: "scope-one-reviewable-change",
		practiceName: "Scope the change to one concern",
		def: ATTENTION_DEFS.new,
		sentence: [
			text("There is new feedback, seen on "),
			{ type: "work", ref: pullRequest(21) } as const,
		],
	},
];

export default meta;
type Story = StoryObj<typeof meta>;

/** Held rows, a block after them, and the footer counting the work of each kind in digits. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		// The mark stands for Heph and names itself; the word is not written under it.
		await expect(canvas.getByRole("img", { name: "Heph, AI mentor" })).toBeVisible();
		await expect(canvas.queryByText("Heph")).toBeNull();
		await expect(canvas.getByText("What is holding up well")).toBeVisible();
		// The footer counts each kind: three pull requests, one issue.
		await expect(canvas.getByText("pull requests")).toBeVisible();
		await expect(canvas.getByText("issue")).toBeVisible();
		// A held row names its practice as a control that opens it, large enough to press.
		const pill = canvas.getByRole("button", { name: "Describe what changed and why" });
		await expectTouchTarget(pill);
		await userEvent.click(pill);
		await expect(args.onOpenPractice).toHaveBeenCalledWith("describe-what-and-why");
		// The held tick is named for what it stands for: a practice that held, or feedback resolved.
		await expect(canvas.getByRole("button", { name: "Going well" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Resolved" })).toBeVisible();
	},
};

/**
 * The block between the held rows and the paragraph: a piece of feedback the work fell back on,
 * then new feedback, each with the card it belongs to one link away. Status is in the glyph alone;
 * the sentences stay the body colour.
 */
export const NeedsYourAttention: Story = {
	args: { needsAttention: ATTENTION_ROWS },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("What needs your attention")).toBeVisible();
		// Each glyph names itself for a reader who cannot see its colour.
		await expect(canvas.getByRole("button", { name: "Back to no clean work" })).toBeVisible();
		await expect(canvas.getByRole("button", { name: "New feedback" })).toBeVisible();
		// The link says which card it goes to, so two of them do not read as one link repeated.
		const link = canvas.getByRole("button", {
			name: "Read the feedback for Say which acceptance criteria are done",
		});
		await userEvent.click(link);
		await expect(args.onReadFeedback).toHaveBeenCalledWith("acceptance-criteria");
	},
};

/** Nothing to act on: the block is left out rather than drawn as an empty heading. */
export const NothingNeedsAttention: Story = {
	args: { needsAttention: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("What needs your attention")).toBeNull();
		await expect(canvas.getByText("What is holding up well")).toBeVisible();
	},
};

/** Without a handler the rows carry no link: a control nobody can answer is left out. */
export const AttentionWithoutSomewhereToGo: Story = {
	args: { needsAttention: ATTENTION_ROWS, onReadFeedback: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("What needs your attention")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /^Read the feedback/u })).toBeNull();
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
		if (!row) {
			throw new Error("Every held row is a list item");
		}
		await expect(within(row).getByRole("button", { name: "Going well" })).toBeVisible();
		await expect(row).toHaveTextContent(/^A practice of our ownHeld across four pull requests$/u);
	},
};

/**
 * Work at GitLab: the footer counts merge requests, and the glyph beside them is the forge the work
 * lives at, not its kind — the same mark the admin console's review pages put on it.
 */
export const GitLabWork: Story = {
	args: {
		reviewedWork: [
			{
				kind: ARTIFACT_KIND.pullRequest,
				provider: "GITLAB",
				items: [20, 21, 22].map((number) => ({
					...pullRequest(number),
					provider: "GITLAB" as const,
					label: `!${number}`,
					url: `https://gitlab.example.com/aet/hephaestus/-/merge_requests/${number}`,
				})),
			},
		],
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("merge requests")).toBeVisible();
		// The glyph is decorative, so the mark is read off the brand icon's own <title>.
		canvas.getByTitle("GitlabIcon");
		await expect(canvas.queryByTitle("GithubIcon")).toBeNull();
	},
};

/** Nothing held, no blocks and no reviewed work: the card is not drawn, the mark included. */
export const NothingToSay: Story = {
	args: { holdingUp: [], holdingUpNote: undefined, reviewedWork: [], blocks: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("img", { name: "Heph, AI mentor" })).toBeNull();
	},
};

/** The name a pointer reaches: hovering the mark says the same words its label does. */
export const MarkTooltip: Story = {
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByRole("img", { name: "Heph, AI mentor" }));
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent("Heph, AI mentor");
	},
};
