import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { WorkspaceChooser } from "~/components/report/WorkspaceChooser";

const meta = {
	component: WorkspaceChooser,
	tags: ["autodocs"],
	args: {
		choices: [
			{ slug: "team", displayName: "Team" },
			{ slug: "course", displayName: "Course" },
		],
		value: "team",
		onChange: fn(),
	},
} satisfies Meta<typeof WorkspaceChooser>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await expect(canvas.getByRole("radio", { name: "Team" })).toBeChecked();
		// Arrow keys move within the group, as for any native radio group.
		canvas.getByRole("radio", { name: "Team" }).focus();
		await userEvent.keyboard("{ArrowDown}");
		await expect(canvas.getByRole("radio", { name: "Course" })).toHaveFocus();
		await expect(args.onChange).toHaveBeenCalledWith("course");
	},
};
