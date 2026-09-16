import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { daysAfter, daysBefore } from "@/stories/story-clock";

import { OutlineConnectCard } from "./OutlineConnectCard";

/**
 * Outline's token-paste lifecycle card — the one piece with no SCM/Slack analogue. When disconnected
 * it is the connect form (server URL + API token); when connected it names the linked instance and
 * shows the stored token's health plus a guarded disconnect. The connection plane (health, freshness,
 * diagnostics, Sync/Cancel) lives in the shared `SyncStatusHeader` above this card on the real page, so
 * every story here is one immutable snapshot of connect / token / disconnect only.
 */
const meta = {
	component: OutlineConnectCard,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		connected: false,
		onConnect: fn(),
		onDisconnect: fn(),
	},
} satisfies Meta<typeof OutlineConnectCard>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A token Outline accepts, whose key metadata it also lets us read. */
const healthyToken = {
	accepted: true,
	name: "Hephaestus mirror",
	last4: "9f2c",
	lastActiveAt: daysBefore(1),
	expiresAt: daysAfter(120),
};

/** Cold start — no prefilled server URL (a prefill would ship a self-host token to Outline Cloud). */
export const Disconnected: Story = {
	args: { connected: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText(/server url/iu)).toHaveValue("");
		await expect(canvas.getByRole("button", { name: /connect outline/iu })).toBeDisabled();
	},
};

/** Both fields have to be filled — the token alone leaves connect disabled. */
export const DisconnectedReadyToConnect: Story = {
	args: { connected: false },
	play: async ({ canvas }) => {
		await userEvent.type(canvas.getByLabelText(/api token/iu), "ol_api_secret");
		await expect(canvas.getByRole("button", { name: /connect outline/iu })).toBeDisabled();

		await userEvent.type(canvas.getByLabelText(/server url/iu), "https://wiki.acme.dev");
		await expect(canvas.getByRole("button", { name: /connect outline/iu })).toBeEnabled();
	},
};

/** Edge: a non-https server URL surfaces the format error and keeps connect disabled. */
export const InvalidServerUrl: Story = {
	args: { connected: false },
	play: async ({ canvas }) => {
		await userEvent.type(canvas.getByLabelText(/server url/iu), "ftp://internal");
		await expect(canvas.getByText(/enter an https:\/\/ url/iu)).toBeVisible();
		await expect(canvas.getByRole("button", { name: /connect outline/iu })).toBeDisabled();
	},
};

/** Connect failed — the ProblemDetail message is anchored inline under the form. */
export const ConnectError: Story = {
	args: {
		connected: false,
		errorMessage: "The Outline API rejected the token (401).",
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/rejected the token/iu)).toBeVisible();
	},
};

/**
 * The instance has no Outline integration enabled — the initiate 400 (no ConnectionStrategy for the
 * kind) is turned into a clear "not available here" hint so the admin does not keep retrying a
 * connect that can never succeed. The raw ProblemDetail stays visible above it.
 */
export const ConnectUnavailable: Story = {
	args: {
		connected: false,
		errorMessage: "No ConnectionStrategy registered for kind=OUTLINE",
		connectUnavailable: true,
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/no connectionstrategy registered/iu)).toBeVisible();
		await expect(canvas.getByText(/outline may not be enabled on this instance/iu)).toBeVisible();
		await expect(canvas.getByText(/ask your server administrator/iu)).toBeVisible();
	},
};

/**
 * Connected and healthy — the linked instance is named and the stored token is accepted, with its
 * metadata and expiry. Health/freshness/webhook and the Sync controls are not here: they live in the
 * shared `SyncStatusHeader` above this card on the page.
 */
export const Connected: Story = {
	args: {
		connected: true,
		connectionLabel: "Acme Wiki",
		tokenStatus: healthyToken,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/outline connected — acme wiki/iu);
		canvas.getByText(/outline accepts this token/iu);
		canvas.getByText(/hephaestus mirror/iu);
		canvas.getByText(/…9f2c/u);
		canvas.getByText(/expires in \d+ days \(on /iu);
		await expect(canvas.getByRole("button", { name: /disconnect outline/iu })).toBeEnabled();
		// The connection plane's Sync control is not in this card.
		await expect(canvas.queryByRole("button", { name: /sync now/iu })).not.toBeInTheDocument();
	},
};

/** A key created without an expiry — nothing to warn about, so it stays a muted fact. */
export const TokenNeverExpires: Story = {
	args: {
		connected: true,
		connectionLabel: "Acme Wiki",
		tokenStatus: { accepted: true, name: "Hephaestus mirror", last4: "9f2c" },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/never expires/iu);
		await expect(canvas.queryByText(/cannot be rotated/iu)).not.toBeInTheDocument();
	},
};

/**
 * Inside the 14-day window: Outline keys cannot be rotated through the API, so the only fix is a
 * human creating a fresh key and re-entering it. The UI says exactly that.
 */
export const TokenExpiringSoon: Story = {
	args: {
		connected: true,
		connectionLabel: "Acme Wiki",
		tokenStatus: { ...healthyToken, expiresAt: daysAfter(5) },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/this api key expires in [45] days/iu);
		canvas.getByText(/cannot be rotated through the api/iu);
		canvas.getByText(/settings → api keys/iu);
	},
};

/** Outline rejects the stored token — the mirror is dead until an admin reconnects with a new key. */
export const TokenRejected: Story = {
	args: {
		connected: true,
		connectionLabel: "Acme Wiki",
		tokenStatus: { accepted: false },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/outline no longer accepts this token — reconnect with a new one/iu);
		await expect(canvas.queryByText(/expires in/iu)).not.toBeInTheDocument();
	},
};

/**
 * A scoped key cannot list itself, so Outline accepts it while telling us nothing about it —
 * we claim only what we know, and never guess an expiry.
 */
export const TokenMetadataUnavailable: Story = {
	args: {
		connected: true,
		connectionLabel: "Acme Wiki",
		tokenStatus: { accepted: true },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/outline accepts this token/iu);
		await expect(canvas.queryByText(/never expires/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/expires in/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/last used/iu)).not.toBeInTheDocument();
	},
};

/** Connected — opening the disconnect dialog surfaces the erase warning + destructive confirm. */
export const ConnectedDisconnectDialog: Story = {
	args: {
		connected: true,
		connectionLabel: "Acme Wiki",
		tokenStatus: healthyToken,
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /disconnect outline/iu }));
		// AlertDialog renders in a portal — query the whole document.
		const dialog = await screen.findByRole("alertdialog", { name: /disconnect outline\?/iu });
		within(dialog).getByText(/every mirrored document.*is\s+erased/iu);
		within(dialog).getByRole("button", { name: /^disconnect$/iu });
	},
};

/**
 * The provider suspended the connection. The identity line lowercases nothing — it reads the wire enum
 * through `CONNECTION_STATE_LABEL` and drops the green check (spent only on ACTIVE). The consequence
 * ("Syncing is paused…") is explained by the shared `ConnectionStateNotice` above this card on the page.
 */
export const ConnectedButSuspended: Story = {
	args: {
		connected: true,
		connectionState: "SUSPENDED",
		connectionLabel: "Acme Wiki",
		tokenStatus: healthyToken,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/outline suspended — acme wiki/iu);
		// The token panel still reports the stored key even while syncing is paused.
		canvas.getByText(/outline accepts this token/iu);
	},
};

/** Setup hasn't finished — the identity line states PENDING plainly, since it resolves on its own. */
export const ConnectedButPending: Story = {
	args: {
		connected: true,
		connectionState: "PENDING",
		connectionLabel: "Acme Wiki",
		tokenStatus: healthyToken,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/outline finishing setup — acme wiki/iu);
	},
};

/** ACTIVE keeps the green check and names the linked instance — the steady state stays quiet. */
export const ConnectedActiveState: Story = {
	args: {
		connected: true,
		connectionState: "ACTIVE",
		connectionLabel: "Acme Wiki",
		tokenStatus: healthyToken,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/outline connected — acme wiki/iu);
	},
};
