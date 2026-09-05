import type { Meta, StoryObj } from "@storybook/react-vite";
import { HttpResponse, http } from "msw";
import { expect, fn } from "storybook/test";

import type { AuthEventView } from "@/api/types.gen";

import { AuthAuditPanel } from "./AuthAuditPanel";

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

function page(content: AuthEventView[]) {
	return {
		content,
		number: 0,
		size: 50,
		totalElements: content.length,
		totalPages: 1,
		last: true,
		first: true,
	};
}

const handlers = (content: AuthEventView[] = events) => [
	http.get("*/admin/audit", () => HttpResponse.json(page(content))),
];

const meta = {
	component: AuthAuditPanel,
	parameters: {
		layout: "padded",
		msw: { handlers: handlers() },
		// One MSW worker answers a whole Docs page, so each story gets its own frame until MSW goes.
		docs: { story: { inline: false, height: "600px" } },
	},
	tags: ["autodocs"],
	args: {
		search: { tab: "signins" },
		onSearchChange: fn(),
		resolveWorkspaceName: (id: number) => (id === 3 ? "Acme" : undefined),
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
	parameters: { msw: { handlers: handlers([]) } },
};

export const FilteredToNothing: Story = {
	args: { search: { tab: "signins", outcome: ["FAILURE"], from: "2026-07-01" } },
	parameters: { msw: { handlers: handlers([]) } },
};

export const FiltersFromUrl: Story = {
	args: { search: { tab: "signins", eventType: ["LOGIN_FAILED"], outcome: ["FAILURE"] } },
	parameters: { msw: { handlers: handlers([failedLogin]) } },
};

export const LoadFailed: Story = {
	parameters: {
		msw: {
			handlers: [
				http.get("*/admin/audit", () =>
					HttpResponse.json(
						{
							type: "about:blank",
							title: "Internal Server Error",
							status: 500,
							detail: "The audit store is unavailable.",
						},
						{ status: 500 },
					),
				),
			],
		},
	},
};
