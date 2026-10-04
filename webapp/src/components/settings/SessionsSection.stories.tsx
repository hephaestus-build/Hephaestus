import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import { storySessions } from "@/stories/sessions-story-mock-data";

import { SessionsSection } from "./SessionsSection";

const meta = {
	component: SessionsSection,
	parameters: { layout: "centered" },
	args: {
		state: {
			status: "ready",
			sessions: storySessions,
			revokingJti: null,
			revokingOthers: false,
			onRevoke: fn(),
			onRevokeOthers: fn(),
		},
	},
	tags: ["autodocs"],
} satisfies Meta<typeof SessionsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The current device, two other browsers and a browser extension. */
export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("listitem", { name: "Browser extension in Chrome on macOS" }),
		).toBeVisible();
		const current = canvas.getByRole("listitem", { name: "Chrome on macOS" });
		await expect(within(current).getByText("This device")).toBeVisible();
		await expect(within(current).getByRole("button", { name: "Current session" })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: "Sign out 3 other sessions" })).toBeEnabled();
	},
};

/** Only the session being revoked waits; every other row stays actionable. */
export const Revoking: Story = {
	args: { state: { ...meta.args.state, revokingJti: "sess-other-002" } },
	play: async ({ canvas }) => {
		const revoking = canvas.getByRole("listitem", { name: "Firefox on Linux" });
		await expect(
			within(revoking).getByRole("button", { name: "Sign out Firefox on Linux" }),
		).toBeDisabled();
		const other = canvas.getByRole("listitem", { name: "Safari on iOS" });
		await expect(
			within(other).getByRole("button", { name: "Sign out Safari on iOS" }),
		).toBeEnabled();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("list", { name: "Loading sessions" })).toHaveAttribute(
			"aria-busy",
			"true",
		);
		await expect(canvas.queryByText("No active sessions found.")).toBeNull();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

/** No other device to sign out, so there is no button for it. */
export const Empty: Story = {
	args: { state: { ...meta.args.state, sessions: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("No active sessions found.")).toBeVisible();
		await expect(canvas.queryByRole("button")).toBeNull();
	},
};

export const ErrorState: Story = {
	args: {
		state: {
			status: "error",
			error: { detail: "The session store is unavailable." },
			onRetry: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("Could not load sessions");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeVisible();
	},
};
