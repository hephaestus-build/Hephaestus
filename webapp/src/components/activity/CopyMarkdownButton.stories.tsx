import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { withStandardPage } from "@/stories/decorators";
import { pending } from "@/test/async";

import { CopyMarkdownButton } from "./CopyMarkdownButton";

const meta = {
	component: CopyMarkdownButton,
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		onCopy: fn(async () => {
			/* copied at once */
		}),
	},
} satisfies Meta<typeof CopyMarkdownButton>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Copy as Markdown" }));
		await expect(args.onCopy).toHaveBeenCalledOnce();
		await expect(canvas.getByRole("button", { name: "Copy as Markdown" })).toBeEnabled();
	},
};

/** While the rest of a long range is read, the button says so and cannot be pressed twice. */
export const Copying: Story = {
	args: {
		// Still reading the remaining pages.
		onCopy: fn(async (): Promise<void> => pending()),
	},
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Copy as Markdown" }));
		await expect(canvas.getByRole("button", { name: "Copying…" })).toBeDisabled();
		await expect(args.onCopy).toHaveBeenCalledOnce();
	},
};
