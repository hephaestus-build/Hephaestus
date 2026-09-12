import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import { UserViewErrorAlert } from "./UserViewErrorAlert";

const meta = {
	component: UserViewErrorAlert,
	tags: ["autodocs"],
	args: {
		error: { status: 503, detail: "User view audit is unavailable" },
		onRetry: fn(),
	},
} satisfies Meta<typeof UserViewErrorAlert>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText(/Trying again usually helps/)).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const StepUpRequired: Story = {
	args: {
		error: {
			status: 403,
			title: "Confirm access",
			detail: "This action requires a recent sign-in.",
			code: "step_up_required",
			maxAgeSeconds: 300,
		},
	},
	play: async ({ args, canvas }) => {
		await expect(canvas.getByText("Confirm your sign-in to keep viewing")).toBeVisible();
		await expect(canvas.queryByText(/You don't have permission/)).toBeNull();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledOnce();
	},
};

export const Forbidden: Story = {
	args: { error: { status: 403, title: "Forbidden", detail: "Not an instance administrator" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/You don't have permission/)).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Retry" })).toBeNull();
	},
};
