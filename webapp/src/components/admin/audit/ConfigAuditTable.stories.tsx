import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { ConfigAuditEntryView } from "@/api/types.gen";
import { expectTablePlaceholders } from "@/stories/loading-more";
import { expectSettledVisible } from "@/stories/overlay";

import { ConfigAuditTable, type ConfigAuditTableProps } from "./ConfigAuditTable";

const impersonatedUpdate: ConfigAuditEntryView = {
	id: 3,
	elevatedViaInstanceAdmin: false,
	occurredAt: new Date("2026-07-10T09:50:00Z"),
	actorKind: "IMPERSONATED",
	actorAccountId: 42,
	actor: { id: 42, displayName: "Ada Lovelace", email: "ada@example.com" },
	actingAccountId: 7,
	actingActor: { id: 7, displayName: "Grace Hopper", email: "grace@example.com" },
	entityType: "AGENT_CONFIG",
	entityId: "5",
	action: "UPDATED",
	changedKeys: ["modelName", "llmApiKeySet"],
	oldValue: '{"name":"GPT reviewer","modelName":"gpt-4o","llmApiKeySet":false}',
	newValue: '{"name":"GPT reviewer","modelName":"gpt-5","llmApiKeySet":true}',
	workspaceId: 12,
};

const systemCreate: ConfigAuditEntryView = {
	id: 1,
	elevatedViaInstanceAdmin: false,
	occurredAt: new Date("2026-07-10T09:00:00Z"),
	actorKind: "SYSTEM",
	entityType: "PRACTICE_REVIEW_SETTINGS",
	entityId: "12",
	action: "CREATED",
	changedKeys: ["cooldownMinutes"],
	newValue: '{"cooldownMinutes":30}',
	workspaceId: 12,
};

/** An instance admin changing a setting in a workspace they are not a member of. */
const elevatedUpdate: ConfigAuditEntryView = {
	id: 5,
	elevatedViaInstanceAdmin: true,
	occurredAt: new Date("2026-07-10T10:20:00Z"),
	actorKind: "USER",
	actorAccountId: 7,
	actor: { id: 7, displayName: "Grace Hopper", email: "grace@example.com" },
	entityType: "PRACTICE_REVIEW_SETTINGS",
	entityId: "12",
	action: "UPDATED",
	changedKeys: ["cooldownMinutes"],
	oldValue: '{"cooldownMinutes":30}',
	newValue: '{"cooldownMinutes":47}',
	workspaceId: 12,
};

const entries: ConfigAuditEntryView[] = [
	{
		id: 4,
		elevatedViaInstanceAdmin: false,
		occurredAt: new Date("2026-07-10T10:05:00Z"),
		actorKind: "USER",
		actorAccountId: 7,
		actor: { id: 7, displayName: "Grace Hopper", email: "grace@example.com" },
		entityType: "PRACTICE_REVIEW_SETTINGS",
		entityId: "12",
		action: "UPDATED",
		changedKeys: ["cooldownMinutes"],
		oldValue: '{"cooldownMinutes":30,"skipDrafts":true,"deliverToMerged":false}',
		newValue: '{"cooldownMinutes":10,"skipDrafts":true,"deliverToMerged":false}',
		workspaceId: 12,
	},
	impersonatedUpdate,
	{
		id: 2,
		elevatedViaInstanceAdmin: false,
		occurredAt: new Date("2026-07-10T09:30:00Z"),
		actorKind: "USER",
		actorAccountId: 7,
		actor: { id: 7, displayName: "Grace Hopper", email: "grace@example.com" },
		entityType: "AGENT_CONFIG",
		entityId: "5",
		action: "CREATED",
		changedKeys: ["name", "modelName", "enabled"],
		newValue: '{"name":"GPT reviewer","modelName":"gpt-4o","enabled":true,"llmApiKeySet":true}',
		workspaceId: 12,
	},
	systemCreate,
];

/** A read that stands, with no page left to ask for. */
function loaded(rows: ConfigAuditEntryView[]) {
	return {
		status: "ready",
		rows,
		hasMore: false,
		isLoadingMore: false,
		onLoadMore: fn(),
	} satisfies ConfigAuditTableProps["state"];
}

const meta = {
	component: ConfigAuditTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		state: loaded(entries),
		hasFilter: false,
		onFilterActor: fn(),
	},
} satisfies Meta<typeof ConfigAuditTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText(/cooldownMinutes: 30 → 10/u);
		await expect(canvas.getAllByText(/GPT reviewer/u).length).toBeGreaterThan(0);
	},
};

export const Impersonation: Story = {
	args: { state: loaded([impersonatedUpdate]) },
	play: async ({ canvas }) => {
		canvas.getByText(/acting as Ada Lovelace/u);
		canvas.getByText(/not set → ••••••/u);
	},
};

export const SystemActor: Story = {
	args: { state: loaded([systemCreate]) },
	play: async ({ canvas }) => {
		canvas.getByText("System");
	},
};

export const WithWorkspaceColumn: Story = {
	args: {
		showWorkspace: true,
		resolveWorkspaceName: (id) => (id === 12 ? "Acme Engineering" : undefined),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getAllByText("Acme Engineering").length).toBeGreaterThan(0);
	},
};

export const RowDetail: Story = {
	play: async ({ canvas }) => {
		const [firstDetails] = canvas.getAllByRole("button", { name: /^View details for/iu });
		if (!firstDetails) {
			throw new Error("The table rendered no rows to open");
		}
		await userEvent.click(firstDetails);
		const dialog = within(await screen.findByRole("dialog"));
		dialog.getByText("cooldownMinutes");
		dialog.getByText("30");
		dialog.getByText("10");
	},
};

export const EmptyWithFilter: Story = {
	args: { state: loaded([]), hasFilter: true },
	play: async ({ canvas }) => {
		canvas.getByText("No changes match your filters");
	},
};

export const EmptyInitial: Story = {
	args: { state: loaded([]) },
	play: async ({ canvas }) => {
		canvas.getByText("No settings changes yet");
		canvas.getByText(
			"Changes to settings, member roles, feature flags, and AI configuration will appear here.",
		);
	},
};

/** The instance log has the workspace column, and its empty state reads the same. */
export const EmptyInitialInstanceLog: Story = {
	args: { state: loaded([]), showWorkspace: true },
	play: async ({ canvas }) => {
		canvas.getByText(
			"Changes to settings, member roles, feature flags, and AI configuration will appear here.",
		);
	},
};

/** The first read is on its way: the real columns, with no empty state. */
export const Loading: Story = {
	args: { state: { status: "loading" }, showWorkspace: true },
	play: async ({ canvas }) => {
		canvas.getByRole("columnheader", { name: "Workspace" });
		await expect(canvas.queryByText("No settings changes yet")).toBeNull();
	},
};

/** The first read failed; nothing was read to show, so nothing claims there are no changes. */
export const ErrorState: Story = {
	args: { state: { status: "error", error: { status: 503 }, onRetry: fn() } },
	play: async ({ canvas }) => {
		canvas.getByText(/We could not load the audit log/iu);
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.queryByText("No settings changes yet")).toBeNull();
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

/** A refresh failed: the changes already read stay, marked as possibly out of date. */
export const RefreshFailed: Story = {
	args: {
		state: {
			...loaded(entries),
			refreshFailure: { error: { status: 503 }, pending: true, onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("We could not refresh the audit log");
		await expect(canvas.getByRole("button", { name: /Retrying/u })).toBeDisabled();
		canvas.getByText(/cooldownMinutes: 30 → 10/u);
	},
};

/** The next page's placeholder rows continue the table in its own columns while it is busy. */
export const LoadMore: Story = {
	args: { state: { ...loaded(entries), hasMore: true, isLoadingMore: true } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /Load more/iu })).toBeDisabled();
		await expectTablePlaceholders(canvas.getByRole("table"));
	},
};

/** The next page failed: the first stays readable, and Load more asks again. */
export const LoadMoreFailed: Story = {
	args: { state: { ...loaded(entries), hasMore: true, loadMoreError: { status: 503 } } },
	play: async ({ canvas }) => {
		canvas.getByText("We could not load more changes.");
		await expect(canvas.getByRole("button", { name: "Load more" })).toBeEnabled();
		canvas.getByText(/cooldownMinutes: 30 → 10/u);
	},
};

export const ElevatedAccess: Story = {
	args: { state: loaded([elevatedUpdate]) },
	play: async ({ canvas }) => {
		canvas.getByText("Grace Hopper");
		await expect(canvas.getByText("Elevated")).toBeVisible();
	},
};

export const MemberChangeIsNotBadged: Story = {
	args: { state: loaded([{ ...elevatedUpdate, elevatedViaInstanceAdmin: false }]) },
	play: async ({ canvas }) => {
		canvas.getByText("Grace Hopper");
		await expect(canvas.queryByText("Elevated")).toBeNull();
	},
};

export const ElevatedRowDetail: Story = {
	args: { state: loaded([elevatedUpdate]) },
	play: async ({ canvas }) => {
		const [details] = canvas.getAllByRole("button", { name: /^View details for/iu });
		if (!details) {
			throw new Error("The table rendered no rows to open");
		}
		await userEvent.click(details);
		const dialog = within(await screen.findByRole("dialog"));
		dialog.getByText("Access");
		await expectSettledVisible(dialog.getByText(/not a member of/iu));
	},
};

export const ColumnCountMatchesHeader: Story = {
	args: { showWorkspace: true },
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
