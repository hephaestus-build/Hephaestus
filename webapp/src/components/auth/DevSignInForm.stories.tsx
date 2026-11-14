import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { pending } from "@/test/async";

import { DevSignInForm } from "./DevSignInForm";

const meta = {
	component: DevSignInForm,
	args: { onSignIn: fn(async () => undefined) },
	parameters: { layout: "centered" },
} satisfies Meta<typeof DevSignInForm>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Continue as dev admin" }));
		await expect(args.onSignIn).toHaveBeenCalledWith("dev-admin");
	},
};

export const OtherUsername: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const username = canvas.getByRole("textbox", { name: "Dev username" });
		await userEvent.clear(username);
		await userEvent.type(username, "  ada  ");
		await userEvent.click(canvas.getByRole("button", { name: "Continue as dev admin" }));
		await expect(args.onSignIn).toHaveBeenCalledWith("ada");
	},
};

/** The session request is in flight: the form cannot send a second one. */
export const Pending: Story = {
	args: { onSignIn: fn(pending) },
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Continue as dev admin" }));
		await expect(canvas.getByRole("button", { name: "Continue as dev admin" })).toBeDisabled();
		await expect(canvas.getByRole("textbox", { name: "Dev username" })).toBeDisabled();
	},
};

export const Refused: Story = {
	args: {
		onSignIn: fn(async () => {
			throw new Error("Dev sign-in failed (403)");
		}),
	},
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Continue as dev admin" }));
		await expect(await canvas.findByText("Dev sign-in failed (403)")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Continue as dev admin" })).toBeEnabled();
	},
};
