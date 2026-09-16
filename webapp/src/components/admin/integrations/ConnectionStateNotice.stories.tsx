import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { ConnectionStateNotice } from "./ConnectionStateNotice";

/**
 * The one place a non-ACTIVE connection state, or a stored credential the server cannot read, is
 * explained.
 *
 * Every integration shares this component, so the states read identically wherever they appear.
 * Severity is graded on consequence, not on enum: SUSPENDED and UNINSTALLED mean *nothing is syncing*
 * and warrant a warning; PENDING resolves on its own and stays plain.
 */
const meta = {
	component: ConnectionStateNotice,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { displayName: "Slack" },
} satisfies Meta<typeof ConnectionStateNotice>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Setup is still finishing. Nothing is owed, so this states the fact and doesn't shout. */
export const Pending: Story = {
	args: { connectionState: "PENDING" },
	play: async ({ canvas }) => {
		canvas.getByText(/finishing setup/iu);
		await expect(canvas.queryByText(/slack is pending/iu)).not.toBeInTheDocument();
	},
};

/** The provider suspended the connection — a warning, because sync has stopped. */
export const Suspended: Story = {
	args: { connectionState: "SUSPENDED" },
	play: async ({ canvas }) => {
		canvas.getByText(/syncing is paused/iu);
		canvas.getByText(/reconnect to resume/iu);
	},
};

/** The app was removed upstream. */
export const Uninstalled: Story = {
	args: { connectionState: "UNINSTALLED" },
	play: async ({ canvas }) => {
		canvas.getByText(/the app was removed/iu);
		await expect(canvas.queryByText(/slack is uninstalled/iu)).not.toBeInTheDocument();
	},
};

/** The same states, worded for a different integration — one component, one vocabulary. */
export const SuspendedOutline: Story = {
	args: { connectionState: "SUSPENDED", displayName: "Outline" },
	play: async ({ canvas }) => {
		canvas.getByText(/outline was suspended by the provider/iu);
	},
};

/**
 * The credential was written with a key the server no longer has. The connection is ACTIVE, so this
 * is the only notice; it says what happened and what replaces the token.
 */
export const CredentialUnreadable: Story = {
	args: { connectionState: "ACTIVE", credentialsUnreadableSince: new Date("2026-09-05T08:00:00Z") },
	play: async ({ canvas }) => {
		canvas.getByText(/the stored token can’t be read/iu);
		await expect(canvas.getByText(/the connection's page says how to replace it/iu)).toBeVisible();
	},
};

/** Both conditions at once: the state notice follows the credential notice, each in its own words. */
export const CredentialUnreadableWhileSuspended: Story = {
	args: {
		connectionState: "SUSPENDED",
		credentialsUnreadableSince: new Date("2026-09-05T08:00:00Z"),
	},
	play: async ({ canvas }) => {
		canvas.getByText(/the stored token can’t be read/iu);
		await expect(canvas.getByText(/syncing is paused/iu)).toBeVisible();
	},
};

/** ACTIVE has nothing to explain, so the notice renders nothing at all. */
export const Active: Story = {
	args: { connectionState: "ACTIVE" },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
	},
};

/** No connection at all — also nothing to explain. */
export const NoConnection: Story = {
	args: { connectionState: undefined },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("alert")).not.toBeInTheDocument();
	},
};
