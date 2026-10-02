import type { Meta, StoryObj } from "@storybook/react-vite";
import { GitPullRequestIcon } from "lucide-react";
import { expect } from "storybook/test";

import { WorkspaceTile } from "./WorkspaceTile";

const meta = {
	component: WorkspaceTile,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	decorators: [
		(Story) => (
			<div className="max-w-xs">
				<Story />
			</div>
		),
	],
	args: {
		title: "Pieces of work reviewed",
		icon: <GitPullRequestIcon className="size-4 text-muted-foreground" aria-hidden />,
		figure: { yours: 17, middleLow: 11, middleHigh: 21 },
		qualifier: "this term",
		observedDevelopers: 24,
		window: "TERM",
		showWorkspace: true,
	},
} satisfies Meta<typeof WorkspaceTile>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("11 to 21")).toBeVisible();
		await expect(
			canvas.getByRole("img", { name: "You: 17. The middle half of developers here: 11 to 21." }),
		).toBeVisible();
		await expect(canvas.getByText("Middle half of 24 developers observed this term")).toBeVisible();
	},
};

/** A middle half on one value says it once, and the band still shows. */
export const OneValue: Story = {
	args: { figure: { yours: 0, middleLow: 2, middleHigh: 2 }, window: "DAYS_30" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("2")).toBeVisible();
		await expect(
			canvas.getByText("Middle half of 24 developers observed in the last 30 days"),
		).toBeVisible();
	},
};

export const NeedsMoreData: Story = {
	args: { figure: { yours: 17 } },
	play: async ({ canvas }) => {
		await expect(
			canvas.getByText("Needs more data before the workspace shows here."),
		).toBeVisible();
		await expect(canvas.queryByRole("img")).toBeNull();
	},
};
