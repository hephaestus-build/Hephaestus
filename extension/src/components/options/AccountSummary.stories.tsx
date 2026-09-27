import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import { AccountSummary } from "~/components/options/AccountSummary";
import { expectNoHorizontalOverflow } from "~/stories/reflow";

const meta = {
	component: AccountSummary,
	tags: ["autodocs"],
	args: {
		account: { displayName: "Ada Lovelace", username: "ada", instanceAdmin: false },
		instanceHost: "heph.example.test",
		signingOut: false,
		onSignOut: fn(),
	},
} satisfies Meta<typeof AccountSummary>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas, userEvent, args }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Sign out" }));
		await expect(args.onSignOut).toHaveBeenCalledOnce();
	},
};

export const InstanceAdmin: Story = {
	args: { account: { displayName: "Grace Hopper", username: "grace", instanceAdmin: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/Instance admin/u)).toBeVisible();
	},
};

export const SigningOut: Story = {
	args: { signingOut: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Signing out…" })).toBeDisabled();
	},
};

export const LongNamesNarrow: Story = {
	parameters: { reflow: true },
	args: {
		account: {
			displayName: "Maximiliane Alexandra von Hohenzollern-Sigmaringen",
			username: "maximiliane-alexandra-hohenzollern",
			instanceAdmin: true,
		},
		instanceHost: "hephaestus.department-of-informatics.example-university.de",
		sessionExpiresAt: "2026-10-03T09:00:00Z",
	},
	play: async ({ canvasElement }) => {
		await expectNoHorizontalOverflow(canvasElement);
	},
};

export const Dark: Story = {
	globals: { theme: "dark" },
	play: async ({ canvas }) => {
		await expect(document.documentElement).toHaveClass("dark");
		await expect(canvas.getByRole("button", { name: "Sign out" })).toBeVisible();
	},
};
