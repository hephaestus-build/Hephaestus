import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import { InstanceEmailCard } from "./InstanceEmailCard";

const meta = {
	component: InstanceEmailCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		isPending: false,
		onSendTest: fn(),
	},
} satisfies Meta<typeof InstanceEmailCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "Recipient" })).toHaveAccessibleDescription(
			"Leave empty to send to the verified address on your own account.",
		);
	},
};

export const Sending: Story = {
	args: { isPending: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /sending/i })).toBeDisabled();
	},
};

export const Sent: Story = {
	args: {
		result: {
			outcome: "SENT",
			to: "ops@example.org",
			messageId: "<3f0c9a1e-6d43-4a3b-9b0e-0c1f2a3b4c5d@hephaestus.example>",
		},
	},
};

export const WithheldBySilentMode: Story = {
	args: { result: { outcome: "SILENT_MODE", to: "ops@example.org" } },
};

export const NotConfigured: Story = {
	args: { result: { outcome: "NOT_CONFIGURED", to: "ops@example.org" } },
};

export const NoRecipient: Story = {
	args: { result: { outcome: "NO_RECIPIENT" } },
};

export const RelayUnavailable: Story = {
	args: { result: { outcome: "UNAVAILABLE", to: "ops@example.org" } },
};

export const InvalidRecipient: Story = {
	args: { result: { outcome: "INVALID_ADDRESS" } },
};

export const RejectedByRelay: Story = {
	args: { result: { outcome: "REJECTED" } },
};

export const NarrowResult: Story = {
	...Sent,
	globals: { viewport: { value: "reflow" }, theme: "dark" },
};

export const RateLimited: Story = {
	args: { result: { outcome: "RATE_LIMITED" } },
};
