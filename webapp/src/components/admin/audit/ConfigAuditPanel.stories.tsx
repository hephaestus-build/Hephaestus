import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn } from "storybook/test";

import type { ConfigAuditEntryView } from "@/api/types.gen";

import { ConfigAuditPanel, type ConfigAuditPanelProps } from "./ConfigAuditPanel";

const llmConnectionCreated: ConfigAuditEntryView = {
	id: 2,
	elevatedViaInstanceAdmin: false,
	occurredAt: new Date("2026-07-23T14:02:00Z"),
	action: "CREATED",
	entityType: "WORKSPACE_LLM_CONNECTION",
	entityId: "12",
	actorKind: "USER",
	actorAccountId: 7,
	actor: { id: 7, displayName: "Ada Lovelace", email: "ada@example.com" },
	workspaceId: 3,
	newValue: JSON.stringify({ displayName: "Acme OpenAI", enabled: false }),
};

const entries: ConfigAuditEntryView[] = [
	{
		id: 3,
		elevatedViaInstanceAdmin: false,
		occurredAt: new Date("2026-07-24T09:14:32Z"),
		action: "UPDATED",
		entityType: "AGENT_BINDING",
		entityId: "PRACTICE_REVIEW",
		actorKind: "USER",
		actorAccountId: 7,
		actor: { id: 7, displayName: "Ada Lovelace", email: "ada@example.com" },
		workspaceId: 3,
		changedKeys: ["timeoutSeconds"],
		oldValue: JSON.stringify({ timeoutSeconds: 600 }),
		newValue: JSON.stringify({ timeoutSeconds: 900 }),
	},
	llmConnectionCreated,
	{
		id: 1,
		elevatedViaInstanceAdmin: false,
		occurredAt: new Date("2026-07-22T06:00:00Z"),
		action: "UPDATED",
		entityType: "WORKSPACE_STATUS",
		entityId: "3",
		actorKind: "SYSTEM",
		workspaceId: 3,
		changedKeys: ["status"],
		oldValue: JSON.stringify({ status: "ACTIVE" }),
		newValue: JSON.stringify({ status: "SUSPENDED" }),
	},
];

/** The list as a read that answered hands it over. */
function loaded(rows: ConfigAuditEntryView[]) {
	return {
		status: "ready",
		rows,
		hasMore: false,
		isLoadingMore: false,
		onLoadMore: fn(),
	} satisfies ConfigAuditPanelProps["list"];
}

const meta = {
	component: ConfigAuditPanel,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		search: {},
		onSearchChange: fn(),
		resolveWorkspaceName: (id: number) => (id === 3 ? "Acme" : undefined),
		list: loaded(entries),
		total: entries.length,
		read: "all",
		showWorkspace: true,
	},
} satisfies Meta<typeof ConfigAuditPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const InstanceScope: Story = {
	play: async ({ canvas }) => {
		const [acme] = await canvas.findAllByText("Acme");
		await expect(acme).toBeVisible();
		await expect(await canvas.findByText("System")).toBeVisible();
	},
};

/** One workspace's log names no workspace. */
export const WorkspaceScope: Story = {
	args: { showWorkspace: false, resolveWorkspaceName: undefined },
};

export const Empty: Story = {
	args: { list: loaded([]), total: 0 },
};

export const FilteredToNothing: Story = {
	args: {
		search: { entityType: ["AGENT_BINDING"], from: "2026-07-01" },
		list: loaded([]),
		total: 0,
	},
};

export const FiltersFromUrl: Story = {
	args: {
		search: { action: ["CREATED"], entityType: ["WORKSPACE_LLM_CONNECTION"] },
		list: loaded([llmConnectionCreated]),
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
