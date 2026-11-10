import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { AuthEventView } from "@/api/types.gen";

import { AuthAuditPanel, type AuthAuditPanelProps } from "./AuthAuditPanel";

const failedLogin: AuthEventView = {
	id: 2,
	elevatedViaInstanceAdmin: false,
	eventType: "LOGIN_FAILED",
	result: "FAILURE",
	failureReason: "Bad credentials",
	occurredAt: new Date("2026-07-24T08:02:11Z"),
	accountId: 7,
	account: { id: 7, displayName: "Ada Lovelace", email: "ada@example.com" },
	ipAddress: "203.0.113.7",
};

const events: AuthEventView[] = [
	{
		id: 3,
		elevatedViaInstanceAdmin: false,
		eventType: "LOGIN",
		result: "SUCCESS",
		occurredAt: new Date("2026-07-24T09:14:32Z"),
		accountId: 7,
		account: { id: 7, displayName: "Ada Lovelace", email: "ada@example.com" },
		ipAddress: "203.0.113.7",
		userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/140.0 Safari/537.36",
	},
	failedLogin,
	{
		id: 1,
		elevatedViaInstanceAdmin: false,
		eventType: "APP_ROLE_CHANGED",
		result: "SUCCESS",
		occurredAt: new Date("2026-07-23T17:40:00Z"),
		accountId: 7,
		account: { id: 7, displayName: "Ada Lovelace", email: "ada@example.com" },
		actingAccountId: 1,
		actor: { id: 1, displayName: "Grace Hopper", email: "grace@example.com" },
		details: JSON.stringify({ from: "USER", to: "APP_ADMIN" }),
		workspaceId: 3,
	},
];

/** The list as a read that answered hands it over. */
function loaded(rows: AuthEventView[]) {
	return {
		status: "ready",
		rows,
		hasMore: false,
		isLoadingMore: false,
		onLoadMore: fn(),
	} satisfies AuthAuditPanelProps["list"];
}

const meta = {
	component: AuthAuditPanel,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		search: { tab: "signins" },
		onSearchChange: fn(),
		resolveWorkspaceName: (id: number) => (id === 3 ? "Acme" : undefined),
		list: loaded(events),
		total: events.length,
		read: "all",
		exporting: false,
		onExport: fn(),
	},
} satisfies Meta<typeof AuthAuditPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(await canvas.findByText("Sign-in")).toBeVisible();
		await expect(await canvas.findByText("Failed sign-in")).toBeVisible();
		await expect(await canvas.findByText("Instance role changed")).toBeVisible();
	},
};

export const Empty: Story = {
	args: { list: loaded([]), total: 0 },
};

export const FilteredToNothing: Story = {
	args: {
		search: { tab: "signins", outcome: ["FAILURE"], from: "2026-07-01" },
		list: loaded([]),
		total: 0,
	},
};

export const FiltersFromUrl: Story = {
	args: {
		search: { tab: "signins", eventType: ["LOGIN_FAILED"], outcome: ["FAILURE"] },
		list: loaded([failedLogin]),
		total: 1,
	},
};

export const Loading: Story = {
	args: { list: { status: "loading" }, total: undefined },
};

export const LoadFailed: Story = {
	args: {
		list: {
			status: "error",
			error: {
				type: "about:blank",
				title: "Internal Server Error",
				status: 500,
				detail: "The audit store is unavailable.",
			},
			onRetry: fn(),
		},
		total: undefined,
	},
};

/** The CSV export is under way. */
export const Exporting: Story = {
	args: { exporting: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /export csv/iu })).toBeDisabled();
	},
};
