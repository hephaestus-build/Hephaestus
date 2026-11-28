import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { ConversationReferences } from "./ConversationReferences";

const meta = {
	component: ConversationReferences,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		references: [
			{ key: "work", label: "Journey map, version 1" },
			{ key: "observation:41", label: "Observation: Students × Define" },
		],
		onRemove: fn(),
	},
} satisfies Meta<typeof ConversationReferences>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each reference names itself and can be removed before the question is sent. */
export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("list", { name: "Sent with your question" })).toBeVisible();
		await userEvent.click(
			canvas.getByRole("button", { name: "Remove Observation: Students × Define" }),
		);
		await expect(args.onRemove).toHaveBeenCalledWith("observation:41");
	},
};

/** No references: a plain question, and nothing renders. */
export const Empty: Story = {
	args: { references: [] },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("list")).toBeNull();
	},
};
