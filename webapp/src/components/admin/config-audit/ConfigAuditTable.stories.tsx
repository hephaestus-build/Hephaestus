import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { ConfigAuditEntryView } from "@/api/types.gen";

import { ConfigAuditTable } from "./ConfigAuditTable";

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

const meta = {
	component: ConfigAuditTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		entries,
		isLoading: false,
		isError: false,
		hasFilter: false,
		hasNextPage: false,
		isFetchingNextPage: false,
		onLoadMore: fn(),
		onRetry: fn(),
		onFilterActor: fn(),
	},
} satisfies Meta<typeof ConfigAuditTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText(/cooldownMinutes: 30 → 10/);
		await expect(canvas.getAllByText(/GPT reviewer/).length).toBeGreaterThan(0);
	},
};

export const Impersonation: Story = {
	args: { entries: [impersonatedUpdate] },
	play: async ({ canvas }) => {
		canvas.getByText(/acting as Ada Lovelace/);
		canvas.getByText(/not set → ••••••/);
	},
};

export const SystemActor: Story = {
	args: { entries: [systemCreate] },
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
		const [firstDetails] = canvas.getAllByRole("button", { name: /View details/i });
		if (!firstDetails) throw new Error("The table rendered no rows to open");
		await userEvent.click(firstDetails);
		const dialog = within(await screen.findByRole("dialog"));
		dialog.getByText("cooldownMinutes");
		dialog.getByText("30");
		dialog.getByText("10");
	},
};

export const EmptyWithFilter: Story = {
	args: { entries: [], hasFilter: true },
	play: async ({ canvas }) => {
		canvas.getByText("No changes match your filters");
	},
};

export const EmptyInitial: Story = {
	args: { entries: [] },
	play: async ({ canvas }) => {
		canvas.getByText("No settings changes yet");
	},
};

export const ErrorState: Story = {
	args: { entries: [], isError: true },
	play: async ({ canvas }) => {
		canvas.getByText(/Couldn’t load the audit log/i);
	},
};

export const LoadMore: Story = {
	args: { hasNextPage: true, isFetchingNextPage: true },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /Load more/i })).toBeDisabled();
	},
};

export const ElevatedAccess: Story = {
	args: { entries: [elevatedUpdate] },
	play: async ({ canvas }) => {
		canvas.getByText("Grace Hopper");
		await expect(canvas.getByText("Elevated")).toBeVisible();
	},
};

export const MemberChangeIsNotBadged: Story = {
	args: { entries: [{ ...elevatedUpdate, elevatedViaInstanceAdmin: false }] },
	play: async ({ canvas }) => {
		canvas.getByText("Grace Hopper");
		await expect(canvas.queryByText("Elevated")).toBeNull();
	},
};

export const ElevatedRowDetail: Story = {
	args: { entries: [elevatedUpdate] },
	play: async ({ canvas }) => {
		const [details] = canvas.getAllByRole("button", { name: /View details/i });
		if (!details) throw new Error("The table rendered no rows to open");
		await userEvent.click(details);
		const dialog = within(await screen.findByRole("dialog"));
		dialog.getByText("Access");
		await expect(dialog.getByText(/not a member of/i)).toBeVisible();
	},
};

export const ColumnCountMatchesHeader: Story = {
	args: { showWorkspace: true },
	play: async ({ canvas }) => {
		const headers = canvas.getAllByRole("columnheader");
		const [, firstBodyRow] = canvas.getAllByRole("row");
		if (!firstBodyRow) throw new Error("The table rendered no body rows");
		const cells = within(firstBodyRow).getAllByRole("cell");
		await expect(headers).toHaveLength(cells.length);
	},
};
