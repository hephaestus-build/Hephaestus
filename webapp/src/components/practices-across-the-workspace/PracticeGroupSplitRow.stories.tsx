import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { ACROSS_WORKSPACE } from "@/stories/practices-across-the-workspace-story-data";

import { PracticeGroupSplitRow } from "./PracticeGroupSplitRow";

const bySlug = (slug: string) => ACROSS_WORKSPACE.groups.find((group) => group.groupSlug === slug);
const failure = bySlug("robust-error-handling");
const packaging = bySlug("review-ready-work");
const maintainable = bySlug("code-craftsmanship");
if (failure === undefined || packaging === undefined || maintainable === undefined) {
	throw new Error("The fixture carries the three shapes");
}

/** One link per row: the group's name opens the reader's own group level on their Practice profile. */
const meta = {
	component: PracticeGroupSplitRow,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<ul aria-label="All practice groups" className="rounded-xl border">
				<Story />
			</ul>
		),
	],
	args: {
		workspaceSlug: "aet",
		group: packaging,
		window: "TERM",
		readerCounted: true,
		showWorkspace: true,
		asking: true,
		onEstimate: fn(),
	},
} satisfies Meta<typeof PracticeGroupSplitRow>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Asking: Story = {
	play: async ({ canvas, args }) => {
		await expect(canvas.getByText("Shown after you answer")).toBeVisible();
		await expect(
			canvas.getByRole("link", {
				name: "Packaging work for review, open your own group on your Practice profile",
			}),
		).toHaveAttribute("href", expect.stringMatching(/\/w\/aet\/practice-profile\?detail=/u));
		await userEvent.click(canvas.getByRole("button", { name: "Skip" }));
		await expect(args.onEstimate).toHaveBeenCalledWith("SKIPPED");
	},
};

export const Revealed: Story = {
	args: { asking: false, estimate: "MIXED" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"You expected Mixed feedback; your latest reviewed work reads Needs attention.",
			),
		).toBeVisible();
		await expect(canvas.getByText("Your estimate: Mixed feedback")).toBeVisible();
	},
};

export const CollapsedSplit: Story = {
	args: { group: failure, asking: false, estimate: "DEVELOPING" },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText(
				"19 of the 24 developers observed here this term have a standing; the split is held back.",
			),
		).toBeVisible();
	},
};

export const Withheld: Story = {
	args: { group: maintainable, asking: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Not enough developers observed here to compare yet."),
		).toBeVisible();
		await expect(canvas.getByText("Going well")).toBeVisible();
	},
};
