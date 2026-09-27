import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { SetupSteps } from "~/components/options/SetupSteps";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

const meta = {
	component: SetupSteps,
	tags: ["autodocs"],
	args: { current: "connect" },
} satisfies Meta<typeof SetupSteps>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Connect: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Connect").closest("li")).toHaveAttribute("aria-current", "step");
	},
};

export const SignIn: Story = {
	args: { current: "sign-in" },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Connect").closest("li")).toHaveTextContent("Connect (done)");
		await expect(canvas.getByText("Sign in").closest("li")).toHaveAttribute("aria-current", "step");
	},
};

export const AllowASite: Story = {
	args: { current: "sites" },
	parameters: { reflow: true },
	play: async ({ canvas, canvasElement }) => {
		await expect(canvas.getByText("Allow a site").closest("li")).toHaveAttribute(
			"aria-current",
			"step",
		);
		await expectNoHorizontalOverflow(canvasElement);
	},
};
