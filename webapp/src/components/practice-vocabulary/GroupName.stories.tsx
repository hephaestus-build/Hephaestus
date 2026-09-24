import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { getGroupVisual } from "@/components/practice-vocabulary/group-visuals";

import { GroupName } from "./GroupName";

const packaging = getGroupVisual("Package", "sky");

const meta = {
	component: GroupName,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		name: "Packaging work for review",
		icon: packaging.Icon,
		pill: packaging.pill,
		onOpen: fn(),
	},
	argTypes: {
		// A component, not a value a control can set.
		icon: { control: false },
	},
} satisfies Meta<typeof GroupName>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The name is a link in the group's colour, on the page's ground rather than a pill's. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		const name = canvas.getByRole("button", { name: "Packaging work for review" });
		await expect(name.parentElement).toHaveClass("bg-transparent");
		await userEvent.click(name);
		await expect(args.onOpen).toHaveBeenCalledOnce();
	},
};

/** Without a way to open the group, the name is a word: no button, no hover. */
export const Plain: Story = {
	args: { onOpen: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.getByText("Packaging work for review")).not.toHaveClass("hover:underline");
	},
};

/** A practice in no group: the name is muted grey rather than any group's colour. */
export const NoColour: Story = {
	args: { name: "Unassigned", pill: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Unassigned" }).parentElement).toHaveClass(
			"text-muted-foreground",
		);
	},
};
