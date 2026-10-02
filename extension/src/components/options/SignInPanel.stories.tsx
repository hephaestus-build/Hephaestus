import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { SignInPanel } from "~/components/options/SignInPanel";

const OPTIONS = {
	options: [
		{ registrationId: "github", displayName: "GitHub", providerType: "GITHUB" },
		{ registrationId: "gitlab-lrz", displayName: "LRZ GitLab", providerType: "GITLAB" },
	],
	devSignIn: false,
	registered: true,
	extensionId: "ijkajblcbajjpjbknfgdiiiljipafiko",
};

const meta = {
	component: SignInPanel,
	tags: ["autodocs"],
	args: {
		instanceHost: "heph.example.test",
		options: { status: "ready", options: OPTIONS },
		attempt: { status: "idle" },
		onSignIn: fn(),
		onDevSignIn: fn(),
	},
} satisfies Meta<typeof SignInPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Sign in with LRZ GitLab" }));
		await expect(args.onSignIn).toHaveBeenCalledWith("gitlab-lrz");
		await expect(canvas.getByText(/until you close Chrome, or at most 7 days/u)).toBeVisible();
	},
};

export const DevelopmentSignIn: Story = {
	args: { options: { status: "ready", options: { ...OPTIONS, options: [], devSignIn: true } } },
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.type(canvas.getByLabelText("User name"), "e2e");
		await userEvent.click(canvas.getByRole("checkbox", { name: "Instance admin" }));
		await userEvent.click(canvas.getByRole("button", { name: "Sign in as developer" }));
		await expect(args.onDevSignIn).toHaveBeenCalledWith("e2e", true);
	},
};

export const Pending: Story = {
	args: { attempt: { status: "pending", registrationId: "github" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Sign in with GitHub" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Sign in with LRZ GitLab" })).toBeDisabled();
	},
};

/** Closing the sign-in window is not an error: nothing changed, and every way in is offered again. */
export const Cancelled: Story = {
	args: { attempt: { status: "cancelled" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("status")).toHaveTextContent(/cancelled and nothing changed/u);
		await expect(canvas.queryByRole("alert")).toBeNull();
		await expect(canvas.getByRole("button", { name: "Sign in with GitHub" })).toBeEnabled();
	},
};

export const SignInFailed: Story = {
	args: {
		attempt: { status: "failed", message: "Hephaestus could not complete the sign-in. Try again." },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(/could not complete the sign-in/u);
		await expect(canvas.getByRole("button", { name: "Sign in with GitHub" })).toBeEnabled();
	},
};

export const NotRegistered: Story = {
	args: { options: { status: "ready", options: { ...OPTIONS, registered: false } } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("ijkajblcbajjpjbknfgdiiiljipafiko")).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const Loading: Story = { args: { options: { status: "loading" } } };

export const Failed: Story = {
	args: {
		options: { status: "error", message: "Hephaestus could not be reached.", onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Try again" })).toBeEnabled();
	},
};
