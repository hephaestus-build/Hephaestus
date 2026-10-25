import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { expectNoPageOverflow } from "@/stories/reflow";
import { levelsOpenedBy } from "@/test/detail-stack";

import { ReviewSelectionBar } from "./ReviewSelectionBar";

const REVIEW_ID = "11111111-1111-1111-1111-111111111111";
const RESULT_ID = "66666666-6666-6666-6666-666666666666";

/**
 * The record every tab is narrowed to, above them all. It is named by its record, never by its id,
 * and "Show trace" opens the record's trace over the tab. Which tab narrows to what is the route's
 * business, proved by its tests.
 */
const meta = {
	component: ReviewSelectionBar,
	parameters: { layout: "padded", chromatic: { viewports: [320, 1440] } },
	tags: ["autodocs"],
	args: {
		kind: "review",
		name: { status: "ready", name: "Review of #1423 · 5 October, 2:02 pm" },
		trace: { kind: "review", id: REVIEW_ID },
		onClear: fn(),
	},
} satisfies Meta<typeof ReviewSelectionBar>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Review: Story = {
	play: async ({ args, canvas, userEvent }) => {
		canvas.getByText("Review of #1423 · 5 October, 2:02 pm");
		await expect(levelsOpenedBy(canvas.getByRole("link", { name: "Show trace" }))).toEqual([
			`trace:review:${REVIEW_ID}`,
		]);
		await userEvent.click(
			canvas.getByRole("button", {
				name: "Stop tracking (Review of #1423 · 5 October, 2:02 pm)",
			}),
		);
		await expect(args.onClear).toHaveBeenCalledOnce();
	},
};

/** A piece of work is named the way its provider writes it, with its title. No review read it yet. */
export const Item: Story = {
	args: {
		kind: "item",
		name: {
			status: "ready",
			name: "Pull request #1423 · Member-facing review activity: say why a practice stayed quiet",
		},
		trace: { kind: "item", id: "42" },
	},
	play: async ({ canvas }) => {
		canvas.getByText(
			"Pull request #1423 · Member-facing review activity: say why a practice stayed quiet",
		);
		await expect(levelsOpenedBy(canvas.getByRole("link", { name: "Show trace" }))).toEqual([
			"trace:item:42",
		]);
	},
};

export const Result: Story = {
	args: {
		kind: "result",
		name: { status: "ready", name: "The description does not say how the change was tested" },
		trace: { kind: "result", id: RESULT_ID },
	},
	play: async ({ canvas }) => {
		canvas.getByText("The description does not say how the change was tested");
		await expect(levelsOpenedBy(canvas.getByRole("link", { name: "Show trace" }))).toEqual([
			`trace:result:${RESULT_ID}`,
		]);
	},
};

/** Until the record is read, the name is a placeholder. */
export const Loading: Story = {
	args: { kind: "item", name: { status: "loading" }, trace: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("link", { name: "Show trace" })).not.toBeInTheDocument();
		canvas.getByRole("button", { name: "Stop tracking (a piece of work)" });
	},
};

/** A record that could not be read is still a selection the reader can see and clear. */
export const NotRead: Story = {
	args: {
		kind: "result",
		name: { status: "error", error: new Error("Not found"), onRetry: fn() },
	},
	play: async ({ canvas }) => {
		canvas.getByText("an observation");
		canvas.getByRole("button", { name: "Stop tracking (an observation)" });
	},
};

/** The overview counts every review, and says so beside the selection it keeps for the next tab. */
export const OnTheOverview: Story = {
	args: { note: "Not applied on the overview" },
	play: async ({ canvas }) => {
		canvas.getByText("Not applied on the overview");
	},
};

/** A long name truncates inside the chip rather than pushing the page sideways at 320px. */
export const Reflow: Story = {
	args: {
		kind: "result",
		name: {
			status: "ready",
			name: "The description says what changed but not why, and it does not say how the change was tested or which part of the system it touches",
		},
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas }) => {
		canvas.getByRole("group", { name: "Selection" });
		await expectNoPageOverflow();
	},
};
