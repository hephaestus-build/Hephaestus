import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { expectNoPageOverflow } from "@/test/reflow";

import { UserViewBanner } from "./UserViewBanner";

const meta = {
	component: UserViewBanner,
	tags: ["autodocs"],
	args: { name: "Sam", workspace: "Engineering", hasAccount: true, onExit: fn() },
} satisfies Meta<typeof UserViewBanner>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(
			canvas.getByRole("region", { name: "Viewing Sam in Engineering — read-only" }),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Exit user view" }));
		await expect(args.onExit).toHaveBeenCalledOnce();
	},
};

export const WithoutAccount: Story = {
	args: { hasAccount: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/No linked Hephaestus account/)).toBeVisible();
	},
};

export const LongName: Story = {
	args: {
		name: "Maximilian Alexander von Hohenzollern-Sigmaringen-Weiler",
		workspace: "Research Platform Engineering — Northern Europe",
	},
};

export const Reflow: Story = {
	args: { hasAccount: false },
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
