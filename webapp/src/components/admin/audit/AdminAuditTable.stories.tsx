import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { AuthEventView } from "@/api/types.gen";
import { expectTablePlaceholders } from "@/stories/loading-more";
import { expectSettledVisible } from "@/stories/overlay";

import { AdminAuditTable, type AdminAuditTableProps } from "./AdminAuditTable";

const events: AuthEventView[] = [
	{
		id: 3,
		elevatedViaInstanceAdmin: false,
		occurredAt: new Date("2026-06-02T10:05:00Z"),
		eventType: "APP_ROLE_CHANGED",
		result: "SUCCESS",
		accountId: 42,
		actingAccountId: 7,
		account: { id: 42, displayName: "Ada Lovelace", email: "ada@example.com" },
		actor: { id: 7, displayName: "Grace Hopper", email: "grace@example.com" },
		details: '{"from":"USER","to":"APP_ADMIN"}',
		workspaceId: 12,
		ipAddress: "203.0.113.7",
		userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/124.0",
	},
	{
		id: 2,
		elevatedViaInstanceAdmin: false,
		occurredAt: new Date("2026-06-02T10:00:00Z"),
		eventType: "IMPERSONATION_BEGIN",
		result: "SUCCESS",
		accountId: 42,
		actingAccountId: 7,
		account: { id: 42, displayName: "Ada Lovelace", email: "ada@example.com" },
		actor: { id: 7, displayName: "Grace Hopper", email: "grace@example.com" },
		details: '{"reason":"Investigating a failed sync reported by the student"}',
		ipAddress: "203.0.113.7",
	},
	{
		id: 1,
		elevatedViaInstanceAdmin: false,
		occurredAt: new Date("2026-06-02T09:30:00Z"),
		eventType: "LOGIN_FAILED",
		result: "FAILURE",
		accountId: 99,
		failureReason: "Email not verified on the GitLab account",
		ipAddress: "198.51.100.4",
		userAgent: "curl/8.4.0",
	},
];

const elevatedAccess: AuthEventView = {
	id: 9,
	elevatedViaInstanceAdmin: true,
	occurredAt: new Date("2026-06-02T12:00:00Z"),
	eventType: "WORKSPACE_ELEVATION",
	result: "SUCCESS",
	accountId: 7,
	account: { id: 7, displayName: "Grace Hopper", email: "grace@example.com" },
	workspaceId: 12,
	ipAddress: "203.0.113.7",
	userAgent: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/124.0",
};

/** A read that stands, with no page left to ask for. */
function loaded(rows: AuthEventView[]) {
	return {
		status: "ready",
		rows,
		hasMore: false,
		isLoadingMore: false,
		onLoadMore: fn(),
	} satisfies AdminAuditTableProps["state"];
}

const meta = {
	component: AdminAuditTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: loaded(events),
		hasFilter: false,
		onFilterAccount: fn(),
		onFilterActor: fn(),
	},
} satisfies Meta<typeof AdminAuditTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		// The table's own rendering of `result`, not `args.events` read back.
		canvas.getByText("Failure");
	},
};

export const DeletedAccountFallback: Story = {
	args: {
		state: loaded([
			{
				id: 5,
				elevatedViaInstanceAdmin: false,
				occurredAt: new Date("2026-06-02T11:00:00Z"),
				eventType: "ACCOUNT_DELETED",
				result: "SUCCESS",
				accountId: 1234,
				ipAddress: "203.0.113.9",
			},
		]),
	},
	play: async ({ canvas }) => {
		canvas.getByText("#1234");
	},
};

export const RowDetail: Story = {
	play: async ({ canvas }) => {
		const [firstDetails] = canvas.getAllByRole("button", { name: /^View details for/iu });
		if (!firstDetails) {
			throw new Error("The table rendered no rows to open");
		}
		await userEvent.click(firstDetails);
		await expectSettledVisible(await screen.findByText("User agent"));
		screen.getByText("Workspace");
	},
};

export const ElevatedWorkspaceAccess: Story = {
	args: { state: loaded([elevatedAccess]) },
	play: async ({ canvas }) => {
		canvas.getByText("Workspace reached as instance admin");
		await expect(canvas.getByText("Elevated")).toBeVisible();
	},
};

export const ElevatedRowDetail: Story = {
	args: { state: loaded([elevatedAccess]) },
	play: async ({ canvas }) => {
		const [details] = canvas.getAllByRole("button", { name: /^View details for/iu });
		if (!details) {
			throw new Error("The table rendered no rows to open");
		}
		await userEvent.click(details);
		await expectSettledVisible(await screen.findByText("Access"));
		screen.getByText(/not a member of/iu);
	},
};

export const UnelevatedRowHasNoBadge: Story = {
	args: { state: loaded([{ ...elevatedAccess, elevatedViaInstanceAdmin: false }]) },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText("Elevated")).toBeNull();
	},
};

export const EmptyInitial: Story = {
	args: { state: loaded([]), hasFilter: false },
	play: async ({ canvas }) => {
		canvas.getByText("No events yet");
		canvas.getByText(/Sign-ins, user views, role changes/iu);
	},
};

export const EmptyWithFilter: Story = {
	args: { state: loaded([]), hasFilter: true },
	play: async ({ canvas }) => {
		canvas.getByText("No events match your filters");
	},
};

/** The first read failed; nothing was read to show, so nothing claims the log is empty. */
export const ErrorState: Story = {
	args: { state: { status: "error", error: { status: 503 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText(/We could not load the audit log/iu);
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByText("No events yet")).toBeNull();
	},
};

/** A refused read says why and offers no retry that would be refused again. */
export const Refused: Story = {
	args: { state: { status: "error", error: { status: 403 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText("You do not have access to the audit log");
		await expect(canvas.queryByRole("button", { name: "Retry" })).toBeNull();
		await expect(canvas.queryByRole("table")).toBeNull();
	},
};

/** A refresh failed: the events already read stay, marked as possibly out of date. */
export const RefreshFailed: Story = {
	args: {
		state: {
			...loaded(events),
			refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("We could not refresh the audit log");
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.getAllByRole("button", { name: /^View details for/iu })).toHaveLength(
			events.length,
		);
	},
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadingMore: Story = {
	args: { state: { ...loaded(events), hasMore: true, isLoadingMore: true } },
	play: async ({ canvas }) => {
		await expectTablePlaceholders(canvas.getByRole("table"));
		await expect(canvas.getByRole("button", { name: "Load more" })).toBeDisabled();
	},
};

/** The next page failed: the first stays readable, and Load more asks again. */
export const LoadMoreFailed: Story = {
	args: { state: { ...loaded(events), hasMore: true, loadMoreError: { status: 503 } } },
	play: async ({ canvas }) => {
		canvas.getByText("We could not load more events.");
		await expect(canvas.getByRole("button", { name: "Load more" })).toBeEnabled();
		await expect(canvas.getAllByRole("button", { name: /^View details for/iu })).toHaveLength(
			events.length,
		);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		canvas.getByRole("columnheader", { name: "Event" });
		await expect(canvas.queryByText("No events yet")).toBeNull();
	},
};

export const ColumnCountMatchesHeader: Story = {
	args: {},
	play: async ({ canvas }) => {
		const headers = canvas.getAllByRole("columnheader");
		const [, firstBodyRow] = canvas.getAllByRole("row");
		if (!firstBodyRow) {
			throw new Error("The table rendered no body rows");
		}
		const cells = within(firstBodyRow).getAllByRole("cell");
		await expect(headers).toHaveLength(cells.length);
	},
};

// Unclaimed accounts still require their account id for a user view.
export const UnclaimedAccountView: Story = {
	args: {
		state: loaded([
			{
				id: 20,
				occurredAt: new Date("2026-09-11T12:00:00Z"),
				eventType: "USER_VIEW",
				result: "SUCCESS",
				elevatedViaInstanceAdmin: true,
				accountId: 7,
				viewedAccountId: 99,
				workspaceId: 12,
				account: { id: 7, displayName: "Grace Hopper" },
				details:
					'{"phase":"AUTHORIZED","reason":"Investigate missing feedback","surface":"practices"}',
			},
		]),
	},
	play: async ({ canvas }) => {
		canvas.getByText("High-risk event:");
		await expect(canvas.getByText("Elevated")).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "View details for User view authorized, Grace Hopper" }),
		).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: /^View details for/u }));
		await expectSettledVisible(await screen.findByText("Viewed account"));
		screen.getByText("#99");
	},
};
