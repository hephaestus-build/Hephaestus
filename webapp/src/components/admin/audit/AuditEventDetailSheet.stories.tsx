import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { AuthEventView } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { expectNoPageOverflow } from "@/stories/reflow";

import { AuditEventDetailSheet } from "./AuditEventDetailSheet";

const failedSignIn: AuthEventView = {
	id: 418,
	elevatedViaInstanceAdmin: false,
	occurredAt: new Date("2026-07-24T09:14:32Z"),
	eventType: "LOGIN_FAILED",
	result: "FAILURE",
	failureReason: "The identity provider rejected the code.",
	accountId: 7,
	account: { id: 7, displayName: "Ada Lovelace", email: "ada@example.com" },
	ipAddress: "203.0.113.24",
	userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_6) AppleWebKit/605.1.15 Safari/605.1.15",
	details: JSON.stringify({
		provider: "github",
		error: "invalid_grant",
		errorDescription: "The code passed is incorrect or expired.",
		redirectUri: "https://hephaestus.example.com/login/oauth2/code/github",
	}),
};

const meta = {
	component: AuditEventDetailSheet,
	parameters: { layout: "fullscreen" },
	tags: ["autodocs"],
	args: { event: failedSignIn, open: true, onOpenChange: fn() },
} satisfies Meta<typeof AuditEventDetailSheet>;

export default meta;
type Story = StoryObj<typeof meta>;

/** The raw data is the event's JSON as stored, set as code and copyable whole. */
export const Default: Story = {
	play: async () => {
		const body = within(document.body);
		await expectSettledVisible(await body.findByText("The identity provider rejected the code."));
		await expect(body.getByRole("button", { name: "Copy raw data" })).toBeEnabled();
		await expect(body.getByText("Raw data").nextElementSibling).toHaveTextContent(
			'"error": "invalid_grant"',
		);
	},
};

/** An event that stored no details says so, and offers nothing to copy. */
export const NoRawData: Story = {
	args: { event: { ...failedSignIn, details: undefined } },
	play: async () => {
		const body = within(document.body);
		await expectSettledVisible(await body.findByText("Raw data"));
		await expect(body.queryByRole("button", { name: "Copy raw data" })).toBeNull();
	},
};

/** A long value scrolls inside the raw data, so the sheet keeps the width of the screen. */
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		const body = within(document.body);
		await expectSettledVisible(await body.findByText("Raw data"));
		await expectNoPageOverflow();
	},
};
