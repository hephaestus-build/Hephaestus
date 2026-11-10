import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { GitHubInstallationSetupPage } from "./GitHubInstallationSetupPage";

const meta = {
	component: GitHubInstallationSetupPage,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { state: { status: "confirm", onConfirm: fn() } },
} satisfies Meta<typeof GitHubInstallationSetupPage>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Nothing reaches the server until the person continues. */
export const Confirm: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Continue" }));
		if (args.state.status === "confirm") {
			await expect(args.state.onConfirm).toHaveBeenCalledOnce();
		}
	},
};

/** The loading state: the check runs, and Continue waits for it. */
export const Pending: Story = {
	args: { state: { status: "pending" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Checking…" })).toBeDisabled();
	},
};

/** The error state: the server's reason. GitHub's code works once, so there is no Continue to repeat it. */
export const Failed: Story = {
	args: {
		state: {
			status: "error",
			message: "Sign in to Hephaestus with the GitHub account that installed the app.",
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent(
			"Sign in to Hephaestus with the GitHub account that installed the app.",
		);
		await expect(canvas.queryByRole("button", { name: "Continue" })).toBeNull();
		await expect(canvas.getByRole("link", { name: "Go to Hephaestus" })).toHaveAttribute(
			"href",
			"/",
		);
	},
};

/** Never a silent nothing: the installer learns who decides next. */
export const AwaitingApproval: Story = {
	args: { state: { status: "awaiting-approval", canApprove: false } },
	play: async ({ canvas }) => {
		canvas.getByText(
			"An instance administrator must approve this installation before Hephaestus can use it.",
		);
		await expect(canvas.queryByRole("link", { name: "Review installations" })).toBeNull();
	},
};

/** An instance admin goes on to the list where they adopt the installation. */
export const AwaitingApprovalForAnAdmin: Story = {
	args: { state: { status: "awaiting-approval", canApprove: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("link", { name: "Review installations" })).toHaveAttribute(
			"href",
			"/admin/github-installations",
		);
	},
};

export const SettingUp: Story = {
	args: { state: { status: "setting-up" } },
	play: async ({ canvas }) => {
		canvas.getByText("Setting up your workspace");
	},
};

/** Signed out, the page makes no request and shows the same approval notice. */
export const SignedOut: Story = {
	args: { state: { status: "signed-out", requiresApproval: true, onSignIn: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText(
			"An instance administrator must approve this installation before Hephaestus can use it.",
		);
		await expect(canvas.queryByRole("button", { name: "Continue" })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Sign in" })).toBeEnabled();
	},
};

export const Invalid: Story = {
	args: { state: { status: "invalid" } },
	play: async ({ canvas }) => {
		canvas.getByText("This link is incomplete");
		await expect(canvas.getByRole("link", { name: "Go to Hephaestus" })).toHaveAttribute(
			"href",
			"/",
		);
	},
};
