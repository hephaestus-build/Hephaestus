import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { settledPopup } from "@/stories/overlay";
import {
	groups,
	OVERVIEW_FIXTURE,
	SHARED_TRANSITION_OVERVIEW,
} from "@/stories/practice-profile-story-mock-data";
import { expectNoPageOverflow } from "@/stories/reflow";

import { ASSESSMENT_DEFS } from "@/components/practice-vocabulary/assessment-defs";
import { composeOverview } from "./compose-overview";

import { PracticeFeedbackOverview } from "./PracticeFeedbackOverview";

/** The fixture's events through the composer: what the route hands the page. */
const composed = composeOverview(OVERVIEW_FIXTURE);

const meta = {
	component: PracticeFeedbackOverview,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		overview: composed,
		onOpenPractice: fn(),
		groups,
		onOpenGroup: fn(),
		onReadFeedback: fn(),
		isLoading: false,
	},
	argTypes: {
		// One composed record: nothing in it is a control a reader could set by hand.
		overview: { control: false },
		groups: { control: false },
	},
} satisfies Meta<typeof PracticeFeedbackOverview>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, args, userEvent }) => {
		// No heading over the card: the intro under the page title introduces it.
		await expect(canvas.queryByRole("heading", { level: 2 })).toBeNull();
		// The registry names the kind, and the count is what the component derived from the list.
		await expect(canvas.getByText("4")).toBeVisible();
		await expect(canvas.getByText("pull requests")).toBeVisible();

		// Resolved feedback leads the held rows, worded by what resolved it: the work that came back
		// clean, each piece a link. What does not fit in three rows is only counted.
		const resolvedPill = canvas.getByRole("button", { name: "Describe what changed and why" });
		const resolvedRow = resolvedPill.closest("li");
		if (!resolvedRow) {
			throw new Error("Every held row is a list item");
		}
		// Each reference carries its own "(opens in a new tab)", so the words are matched around them.
		await expect(resolvedRow).toHaveTextContent(
			/Resolved by the work after #22.*, #21.* and #20.* came back clean/u,
		);
		await expect(within(resolvedRow).getByRole("link", { name: /^#21/u })).toHaveAttribute(
			"href",
			"https://github.com/HephaestusTest/practice-validation/pull/21",
		);
		await expect(canvas.getByText("Another two practices held too.")).toBeVisible();
		await userEvent.click(resolvedPill);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith("describe-what-and-why");

		// "What needs your attention" carries what the reader can act on today: the fall back first,
		// then the new feedback, each with the card it belongs to one link away.
		const attention = canvas.getByText("What needs your attention").parentElement;
		if (!attention) {
			throw new Error("The block's label sits in the block");
		}
		await expect(within(attention).getByText(/Back to 0 of 3 clean after/u)).toBeVisible();
		await expect(within(attention).getByText(/There is new feedback, seen on/u)).toBeVisible();
		const attentionPill = within(attention).getByRole("button", {
			name: "Scope the change to one concern",
		});
		await expect(attentionPill).toHaveAttribute("data-slot", "badge");
		await expect(resolvedPill).toHaveAttribute("data-slot", "badge");
		await userEvent.click(attentionPill);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith("scope-one-reviewable-change");
		await userEvent.click(
			within(attention).getByRole("button", {
				name: "Read the feedback for Scope the change to one concern",
			}),
		);
		await expect(args.onReadFeedback).toHaveBeenLastCalledWith("scope-one-concern-new");

		// "What changed" keeps the standing that slipped: a standing is a balance over runs rather
		// than something to do, so it stays a sentence and gets no row.
		const changed = canvas.getByText("What changed").parentElement;
		if (!changed) {
			throw new Error("The block's label sits in the block");
		}
		await expect(within(changed).getByText(/moved to Needs attention after/u)).toBeVisible();
		await userEvent.click(
			within(changed).getByRole("button", { name: "Keep the diff reviewable in one sitting" }),
		);
		await expect(args.onOpenPractice).toHaveBeenLastCalledWith("reviewable-diff-size");
	},
};

/**
 * A run in which three practices made one move and two groups made their own: the practices are
 * named together in one sentence, and each group is its own icon and colour rather than a name in
 * plain words.
 */
export const SharedMoves: Story = {
	args: { overview: composeOverview(SHARED_TRANSITION_OVERVIEW) },
	play: async ({ canvas, args, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: /^Show the/u }));

		// One sentence for the three practices whose trend turned the same way, each still its own
		// pill, and the predicate agreeing with all three.
		const trends = canvas.getByText("Trends turned.").parentElement;
		if (!trends) {
			throw new Error("The paragraph's title sits in the paragraph");
		}
		// The work reference carries its own "(opens in a new tab)", so the words are matched
		// around it.
		await expect(trends).toHaveTextContent(
			/Scope the change to one concern, Write commit subjects a reviewer can follow and Keep the diff reviewable in one sitting now show More positive recently over #22/u,
		);
		for (const name of [
			"Scope the change to one concern",
			"Write commit subjects a reviewer can follow",
			"Keep the diff reviewable in one sitting",
		]) {
			await expect(within(trends).getByRole("button", { name })).toHaveAttribute(
				"data-slot",
				"badge",
			);
		}

		// A group that moved shows as itself: its own icon in front and its own colour, and a press
		// opens it.
		const groupName = canvas.getByRole("button", { name: "Communicating in the open" });
		const visual = groupName.parentElement;
		if (!visual) {
			throw new Error("The group's name sits inside its icon and colour");
		}
		await expect(visual).toHaveClass("text-violet-700");
		await expect(visual.querySelector(".lucide-message-circle")).not.toBeNull();
		await expect(canvas.getByText(/is now Mixed feedback after/u)).toBeVisible();
		await userEvent.click(groupName);
		await expect(args.onOpenGroup).toHaveBeenLastCalledWith("communication");
	},
};

/**
 * What the paragraph only counted unfolds in place, one paragraph per kind, and folds away again.
 */
export const RestUnfolded: Story = {
	play: async ({ canvas, userEvent }) => {
		// The trigger counts what it holds: the spelled number and the plural.
		const toggle = canvas.getByRole("button", { name: /^Show the \w+ changes$/u });
		await expect(toggle).toHaveAttribute("aria-expanded", "false");
		await expect(canvas.queryByText("Moved up.")).toBeNull();
		await userEvent.click(toggle);
		await expect(toggle).toHaveAttribute("aria-expanded", "true");
		await expect(canvas.getByText("Moved up.")).toBeVisible();
		await expect(canvas.getByText("Seen for the first time.")).toBeVisible();
		await expect(canvas.getByText("Trends turned.")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Show less" }));
		await expect(canvas.queryByText("Moved up.")).toBeNull();
	},
};

/**
 * One event folded away: the trigger names that change, since the paragraph named none of them
 * and there is no other one to see.
 */
export const OneChangeFolded: Story = {
	args: { overview: { ...composed, rest: composed.rest.slice(0, 1), restCount: 1 } },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show the change" }));
		await expect(canvas.getByRole("button", { name: "Show less" })).toBeVisible();
	},
};

/**
 * Nothing held, nothing moved and nothing reviewed: the heading stays and the card is not drawn.
 */
export const Empty: Story = {
	args: {
		overview: {
			...composed,
			holdingUp: [],
			holdingUpNote: undefined,
			changed: [],
			rest: [],
			reviewedWork: [],
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("heading", { level: 2 })).toBeNull();
		await expect(canvas.queryByText("Heph")).toBeNull();
		await expect(canvas.queryByText("What is holding up well")).toBeNull();
		await expect(canvas.queryByText("What changed")).toBeNull();
	},
};

/**
 * Only the footer has something to say: the card is the reviewed work alone, with no empty block
 * over it.
 */
export const OnlyReviewedWork: Story = {
	args: {
		overview: { ...composed, holdingUp: [], holdingUpNote: undefined, changed: [], rest: [] },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("pull requests")).toBeVisible();
		await expect(canvas.queryByText("What is holding up well")).toBeNull();
		await expect(canvas.queryByText("What changed")).toBeNull();
		await expect(canvas.queryByRole("button", { name: /^Show the/u })).toBeNull();
	},
};

/** The held tick explains itself: the registry's words and sentence, reachable by keyboard. */
export const HeldTickExplained: Story = {
	play: async ({ canvas }) => {
		const [tick] = canvas.getAllByRole("button", { name: `${ASSESSMENT_DEFS.GOOD.label}:` });
		if (!tick) {
			throw new Error("Every held row carries its tick");
		}
		// Keyboard path: focus opens the tooltip as it does for every status icon.
		tick.focus();
		const tooltip = await settledPopup();
		await expect(tooltip).toHaveTextContent(ASSESSMENT_DEFS.GOOD.description);
	},
};

export const Loading: Story = {
	args: { isLoading: true },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("heading", { level: 2 })).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320] },
	},
	play: expectNoPageOverflow,
};
