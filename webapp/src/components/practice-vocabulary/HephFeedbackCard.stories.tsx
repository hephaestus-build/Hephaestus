import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { settledPopup } from "@/stories/overlay";

import { text } from "@/components/common/feedback-text";
import { ARTIFACT_KIND } from "@/lib/artifact-kinds";
import { pullRequest } from "@/stories/practice-profile-story-mock-data";
import { expectTouchTarget } from "@/test/controls";

import { ASSESSMENT_DEFS } from "./assessment-defs";
import { ATTENTION_DEFS } from "./attention-defs";
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

/** Held rows, a block after them, and the footer counting the work under the number rule. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		// The mark stands for Heph and names itself; the word is not written under it.
		await expect(canvas.getByRole("img", { name: "Heph, AI mentor" })).toBeVisible();
		await expect(canvas.queryByText("Heph")).toBeNull();
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
		for (const tick of canvas.getAllByRole("button", { name: `${ASSESSMENT_DEFS.GOOD.label}:` })) {
			await expectTouchTarget(tick);
		}
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
		await expect(canvas.getByText("Back to 0 of 3 clean after")).toBeVisible();
		await expect(canvas.getByText("There is new feedback, seen on")).toBeVisible();
		// Each glyph names itself for a reader who cannot see its colour, and the two never share one.
		await expect(
			canvas.getByRole("button", { name: `${ATTENTION_DEFS.reset.label}:` }),
		).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: `${ATTENTION_DEFS.new.label}:` }),
		).toBeVisible();
		// The link says which card it goes to, so two of them do not read as one link repeated.
		const link = canvas.getByRole("button", {
			name: "Read the feedback for Say which acceptance criteria are done",
		});
		await userEvent.click(link);
		await expect(args.onReadFeedback).toHaveBeenCalledWith("acceptance-criteria");
	},
};

/**
 * More than the block's two rows: the card still draws two, and what the composer left over is the
 * paragraph's business — the card never grows a third row on its own.
 */
export const AttentionRowsAreCapped: Story = {
	args: {
		needsAttention: ATTENTION_ROWS,
		blocks: [
			{
				label: "What changed",
				content: (
					<p className="text-sm">
						Two more practices changed as well; the practices table lists them.
					</p>
				),
			},
		],
	},
	play: async ({ canvas }) => {
		// Two rows, and what did not fit is counted in the paragraph rather than added as a third.
		await expect(canvas.getAllByRole("button", { name: /^Read the feedback for /u })).toHaveLength(
			2,
		);
		await expect(canvas.getByText(/Two more practices changed as well/u)).toBeVisible();
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
		await expect(row).toHaveTextContent(
			new RegExp(
				`^${ASSESSMENT_DEFS.GOOD.label}: A practice of our ownHeld across four pull requests$`,
				"u",
			),
		);
		await expect(canvas.queryByText("Going well")).toBeNull();
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

/** The skeleton mirrors the card at rest, so the page does not jump when the overview lands. */
export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("What is holding up well")).toBeNull();
		// The mark stays while the card fills, and it is not a control.
		await expect(canvas.queryByRole("button")).toBeNull();
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
		// The trigger is the span around the mark; the pointer lands on it, not on the SVG's own box.
		const trigger = canvas
			.getByRole("img", { name: "Heph, AI mentor" })
			.closest("[data-slot=tooltip-trigger]");
		if (!(trigger instanceof HTMLElement)) {
			throw new Error("The mark sits inside its tooltip trigger");
		}
		await userEvent.pointer([{ target: trigger }, { target: trigger, coords: { x: 8, y: 8 } }]);
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent("Heph, AI mentor");
	},
};
