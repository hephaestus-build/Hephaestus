import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import type { ConnectionSyncStatus, SyncJob } from "@/api/types.gen";
import { minutesBefore } from "@/stories/story-clock";

import { IntegrationOverviewCard } from "./IntegrationOverviewCard";

const SYNC_INTERVAL_SECONDS = 3600;

const status: ConnectionSyncStatus = {
	connectionId: 7,
	connectionState: "ACTIVE",
	kind: "GITHUB",
	health: "HEALTHY",
	resourceCounts: { total: 12, errored: 0, pending: 0, stale: 0 },
	backfillSupported: true,
	syncIntervalSeconds: SYNC_INTERVAL_SECONDS,
	lastSuccessfulSyncAt: minutesBefore(4),
	lastEventProcessedAt: minutesBefore(1),
};

const runningJob: SyncJob = {
	id: 11,
	type: "RECONCILIATION",
	trigger: "MANUAL",
	status: "RUNNING",
	cancelRequested: false,
	createdAt: minutesBefore(2),
	startedAt: minutesBefore(2),
	itemsProcessed: 5,
	itemsTotal: 12,
};

const meta = {
	component: IntegrationOverviewCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		workspaceSlug: "demo",
		entry: {
			kind: "GITHUB",
			displayName: "GitHub",
			connected: true,
			connectionId: 7,
			connectionState: "ACTIVE",
		},
		status,
		onSync: fn(),
	},
} satisfies Meta<typeof IntegrationOverviewCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Connected: Story = {};

export const Syncing: Story = {
	args: { status: { ...status, health: "FAILED", activeJob: runningJob } },
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText(/connection health/iu)).toHaveTextContent("Syncing");
	},
};

export const WithErroredResources: Story = {
	args: {
		status: {
			...status,
			health: "DEGRADED",
			resourceCounts: { total: 12, errored: 3, pending: 0, stale: 0 },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("3 errored");
		canvas.getByText(/of 12 resources/iu);
	},
};

export const WithStaleResources: Story = {
	args: { status: { ...status, resourceCounts: { total: 12, errored: 0, pending: 0, stale: 4 } } },
	play: async ({ canvas }) => {
		canvas.getByText("4 stale");
	},
};

export const WithErroredAndStaleResources: Story = {
	args: {
		status: {
			...status,
			health: "DEGRADED",
			resourceCounts: { total: 12, errored: 1, pending: 0, stale: 3 },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("1 errored");
		canvas.getByText("3 stale");
	},
};

export const StaleFreshness: Story = {
	args: { status: { ...status, lastSuccessfulSyncAt: minutesBefore(200) } },
};

export const UnknownCadence: Story = {
	args: {
		status: {
			...status,
			syncIntervalSeconds: undefined,
			lastSuccessfulSyncAt: minutesBefore(60 * 30),
			lastEventProcessedAt: undefined,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /stale/iu })).not.toBeInTheDocument();
		canvas.getByText(/ago$/u);
	},
};

export const NeverSynced: Story = {
	args: {
		status: {
			...status,
			health: "PENDING",
			lastSuccessfulSyncAt: undefined,
			lastEventProcessedAt: undefined,
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText("Never synced");
		canvas.getByText(/no events received yet/iu);
	},
};

export const StatusError: Story = {
	args: {
		status: undefined,
		isStatusError: true,
		statusError: { status: 503, detail: "The GitHub API is unavailable." },
		onRetryStatus: fn(),
	},
	play: async ({ args, canvas }) => {
		canvas.getByText(/couldn't load sync status/iu);
		canvas.getByText(/github api is unavailable/iu);
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetryStatus).toHaveBeenCalledTimes(1);
	},
};

export const StatusErrorForbidden: Story = {
	args: {
		status: undefined,
		isStatusError: true,
		statusError: { status: 403, detail: "You are not an admin of this workspace." },
		onRetryStatus: fn(),
	},
	play: async ({ canvas }) => {
		canvas.getByText(/not an admin of this workspace/iu);
		await expect(canvas.queryByRole("button", { name: /retry/iu })).not.toBeInTheDocument();
	},
};

/**
 * ACTIVE, but the stored token cannot be read with the server's key: the overview must say so where
 * it would otherwise show a healthy status strip.
 */
export const CredentialUnreadable: Story = {
	args: {
		entry: {
			...meta.args.entry,
			connectionState: "ACTIVE",
			credentialsUnreadableSince: new Date("2026-09-05T08:00:00Z"),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/the stored token can't be read/iu)).toBeVisible();
		// The overview does not know the provider's door, so it points at the page that does.
		await expect(canvas.getByText(/the connection's page says how to replace it/iu)).toBeVisible();
		// Neither a health verdict nor a sync trigger beside a token nothing can use.
		await expect(canvas.queryByText(/healthy/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: /sync now/iu })).not.toBeInTheDocument();
	},
};

export const ConnectionSuspended: Story = {
	args: {
		entry: {
			kind: "GITHUB",
			displayName: "GitHub",
			connected: true,
			connectionId: 7,
			connectionState: "SUSPENDED",
		},
		status: { ...status, connectionState: "SUSPENDED", health: "SUSPENDED" },
	},
	play: async ({ canvas }) => {
		canvas.getByText(/syncing is paused/iu);
		canvas.getByText(/suspended by the provider/iu);
		await expect(canvas.queryByText(/connection is suspended/iu)).not.toBeInTheDocument();
	},
};

export const ConnectionPending: Story = {
	args: {
		entry: {
			kind: "SLACK",
			displayName: "Slack",
			connected: true,
			connectionId: 9,
			connectionState: "PENDING",
		},
		status: undefined,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/finishing setup/iu);
		await expect(canvas.queryByText(/slack is pending/iu)).not.toBeInTheDocument();
	},
};

export const ConnectionUninstalled: Story = {
	args: {
		entry: {
			kind: "SLACK",
			displayName: "Slack",
			connected: true,
			connectionId: 9,
			connectionState: "UNINSTALLED",
		},
		status: undefined,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/the app was removed/iu);
		await expect(canvas.queryByText(/slack is uninstalled/iu)).not.toBeInTheDocument();
	},
};

export const ScmNotConnected: Story = {
	args: {
		entry: { kind: "GITHUB", displayName: "GitHub", connected: false },
		status: undefined,
	},
	play: async ({ canvas }) => {
		canvas.getByText(/source control is selected when the workspace is created/iu);
		await expect(canvas.queryByRole("link", { name: /connect/iu })).not.toBeInTheDocument();
	},
};

export const Disconnected: Story = {
	args: {
		entry: { kind: "OUTLINE", displayName: "Outline", connected: false },
		status: undefined,
	},
};

export const Loading: Story = { args: { status: undefined, isStatusLoading: true } };
