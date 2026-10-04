import type { Meta, StoryObj } from "@storybook/react";
import { expect } from "storybook/test";

import { ConnectionHealthBadge } from "./ConnectionHealthBadge";

/**
 * The single rendering of a connection's derived health. Word + colour (never colour alone), with
 * `role="status"`/`aria-live` so a transition is announced. A running job supersedes a stale
 * last-failed health via `isSyncing`, so the badge reads "Syncing" rather than a misleading "Failed".
 * The wire enum (`HEALTHY`/`DEGRADED`/…) is never shown — only the humanised label.
 */
const meta = {
	component: ConnectionHealthBadge,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { health: "HEALTHY", isSyncing: false },
	argTypes: {
		health: {
			control: "select",
			options: ["PENDING", "HEALTHY", "DEGRADED", "FAILED", "SUSPENDED"],
			description: "Derived connection health from the unified sync status.",
		},
		isSyncing: {
			control: "boolean",
			description: "A running job overrides the health label with “Syncing”.",
		},
	},
} satisfies Meta<typeof ConnectionHealthBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Healthy: Story = { args: { health: "HEALTHY" } };
export const Pending: Story = { args: { health: "PENDING" } };
export const Degraded: Story = { args: { health: "DEGRADED" } };
export const Failed: Story = { args: { health: "FAILED" } };
export const Suspended: Story = { args: { health: "SUSPENDED" } };

/**
 * The documented override: a live job wins over a stale FAILED health, so the badge reads
 * "Syncing" — the wire health is not what the admin sees while a run is in flight.
 */
export const Syncing: Story = {
	args: { health: "FAILED", isSyncing: true },
	play: async ({ canvas }) => {
		const badge = canvas.getByRole("status");
		await expect(badge).toHaveTextContent("Syncing");
		await expect(badge).toHaveAttribute("aria-label", "Connection health: Syncing");
		await expect(badge).not.toHaveTextContent("Failed");
	},
};
