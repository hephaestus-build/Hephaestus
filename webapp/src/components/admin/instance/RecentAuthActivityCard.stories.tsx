import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import type { AuthEventView } from "@/api/types.gen";
import { hoursBefore, minutesBefore } from "@/components/common/story-clock";

import { RecentAuthActivityCard } from "./RecentAuthActivityCard";

const events: AuthEventView[] = [
	{
		id: 1,
		elevatedViaInstanceAdmin: false,
		eventType: "LOGIN",
		result: "SUCCESS",
		occurredAt: minutesBefore(5),
		account: { id: 7, displayName: "Ada Lovelace" },
		accountId: 7,
	},
	{
		id: 2,
		elevatedViaInstanceAdmin: false,
		eventType: "LOGIN_FAILED",
		result: "FAILURE",
		failureReason: "invalid_grant",
		occurredAt: minutesBefore(20),
		accountId: 12,
	},
	{
		id: 3,
		elevatedViaInstanceAdmin: false,
		eventType: "IMPERSONATION_BEGIN",
		result: "SUCCESS",
		occurredAt: hoursBefore(3),
		account: { id: 7, displayName: "Ada Lovelace" },
		accountId: 7,
		actor: { id: 1, displayName: "Instance Admin" },
		actingAccountId: 1,
	},
	{
		id: 4,
		elevatedViaInstanceAdmin: false,
		eventType: "APP_ROLE_CHANGED",
		result: "SUCCESS",
		occurredAt: hoursBefore(26),
		account: { id: 12, displayName: "Grace Hopper" },
		accountId: 12,
	},
];

const meta = {
	component: RecentAuthActivityCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { events },
} satisfies Meta<typeof RecentAuthActivityCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("Failed sign-in");
	},
};

export const Empty: Story = {
	args: { events: [] },
	play: async ({ canvas }) => {
		canvas.getByText(/no activity yet/i);
	},
};

export const Loading: Story = {
	args: { events: [], isLoading: true },
};

export const LoadFailed: Story = {
	args: { events: [], error: { status: 500 }, onRetry: fn() },
	play: async ({ canvas }) => {
		// The failure must not read as "no activity"; a 5xx is retryable so the affordance shows.
		await expect(canvas.queryByText(/no activity yet/i)).not.toBeInTheDocument();
		canvas.getByRole("button", { name: /retry/i });
	},
};

/** A 403 cannot be retried into success, so the alert must not offer a button that always fails. */
export const Forbidden: Story = {
	args: { events: [], error: { status: 403 }, onRetry: fn() },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /retry/i })).not.toBeInTheDocument();
	},
};
