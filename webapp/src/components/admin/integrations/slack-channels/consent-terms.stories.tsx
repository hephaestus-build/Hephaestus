import type { Meta, StoryObj } from "@storybook/react";

import { ConsentStateBadge } from "./consent-terms";

/**
 * The one rendering of a Slack channel's consent lifecycle. Word + icon for every state (never
 * colour alone, so it survives WCAG 1.4.1), drawn from the single term map shared by the channel
 * row and the history sheet — a state can never be spelled "Monitoring" in one place and "ACTIVE"
 * in another. The wire enum is never shown; only the humanised label.
 */
const meta = {
	component: ConsentStateBadge,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { state: "ACTIVE" },
	argTypes: {
		state: {
			control: "select",
			options: ["PENDING", "ACTIVE", "PAUSED", "REVOKED", "ACTIVATING", "FAILED"],
			description: "The per-channel consent lifecycle state.",
		},
	},
} satisfies Meta<typeof ConsentStateBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

/** PENDING — allow-listed but nothing read yet. */
export const NotStarted: Story = { args: { state: "PENDING" } };

/** ACTIVE — messages are being read. */
export const Monitoring: Story = { args: { state: "ACTIVE" } };

/** PAUSED — reading stopped, collected data kept. */
export const Paused: Story = { args: { state: "PAUSED" } };

/** REVOKED — terminal; nothing left to erase. */
export const Revoked: Story = { args: { state: "REVOKED" } };

/** ACTIVATING — activation queued; waiting for the announcement outbox to confirm. */
export const Activating: Story = { args: { state: "ACTIVATING" } };

/** FAILED — the outbox delivery failed; admin must retry via the activation dialog. */
export const ActivationFailed: Story = { args: { state: "FAILED" } };
