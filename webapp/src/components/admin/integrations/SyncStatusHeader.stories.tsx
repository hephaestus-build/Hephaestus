import type { Meta, StoryObj } from "@storybook/react";
import { ExternalLinkIcon } from "lucide-react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { ConnectionSyncStatus, SyncJob } from "@/api/types.gen";
import { buttonVariants } from "@/components/ui/button";
import { minutesAfter, minutesBefore } from "@/stories/story-clock";
import { expectSettledVisible } from "@/test/overlay";

import { SyncStatusHeader } from "./SyncStatusHeader";

const SYNC_INTERVAL_SECONDS = 3600;

const baseStatus: ConnectionSyncStatus = {
	connectionId: 7,
	connectionState: "ACTIVE",
	kind: "GITHUB",
	health: "HEALTHY",
	resourceCounts: { total: 12, errored: 0, pending: 0, stale: 0 },
	backfillSupported: true,
	syncIntervalSeconds: SYNC_INTERVAL_SECONDS,
	lastSuccessfulSyncAt: minutesBefore(4),
	nextScheduledSyncAt: minutesAfter(56),
	lastEventProcessedAt: minutesBefore(1),
	webhookRegistered: true,
	rateLimit: {
		limit: 5000,
		remaining: 4812,
		resetAt: minutesAfter(40),
		observedAt: minutesBefore(2),
	},
};

const runningJob: SyncJob = {
	id: 12,
	type: "RECONCILIATION",
	trigger: "MANUAL",
	status: "RUNNING",
	cancelRequested: false,
	createdAt: minutesBefore(1),
	startedAt: minutesBefore(1),
	itemsProcessed: 5,
	itemsTotal: 12,
};

const meta = {
	component: SyncStatusHeader,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		label: "GitHub",
		status: baseStatus,
		isLoading: false,
		isConnectionActive: true,
		triggeringType: null,
		isCancelling: false,
		onRetry: fn(),
		onSync: fn(),
		onBackfill: fn(),
		onCancel: fn(),
	},
} satisfies Meta<typeof SyncStatusHeader>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Healthy: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByLabelText(/connection health/iu)).toHaveTextContent("Healthy");
	},
};

/** ACTIVE, but the stored credential cannot be read: no health verdict and no trigger to run on it. */
export const CredentialUnreadable: Story = {
	args: { credentialsUnreadableSince: new Date("2026-09-05T08:00:00Z") },
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/healthy/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: /sync now/iu })).not.toBeInTheDocument();
	},
};

/** The credential cannot be read while a job still runs: no new trigger, but the job can be cancelled. */
export const CredentialUnreadableWithRunningJob: Story = {
	args: {
		credentialsUnreadableSince: new Date("2026-09-05T08:00:00Z"),
		status: { ...baseStatus, activeJob: runningJob },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /cancel/iu })).toBeVisible();
		await expect(canvas.queryByRole("button", { name: /sync now/iu })).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("button", { name: /more sync options/iu }),
		).not.toBeInTheDocument();
	},
};

export const StaleFreshness: Story = {
	args: {
		status: { ...baseStatus, health: "DEGRADED", lastSuccessfulSyncAt: minutesBefore(150) },
	},
};

export const VeryStaleFreshness: Story = {
	args: {
		status: { ...baseStatus, health: "DEGRADED", lastSuccessfulSyncAt: minutesBefore(60 * 11) },
	},
};

export const NextRunDue: Story = {
	args: { status: { ...baseStatus, nextScheduledSyncAt: minutesBefore(2) } },
	play: async ({ canvas }) => {
		canvas.getByText(/next run due/iu);
	},
};

export const UnknownCadence: Story = {
	args: {
		status: {
			...baseStatus,
			syncIntervalSeconds: undefined,
			nextScheduledSyncAt: undefined,
			lastSuccessfulSyncAt: minutesBefore(60 * 20),
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
		status: { ...baseStatus, health: "PENDING", lastSuccessfulSyncAt: undefined },
	},
	play: async ({ canvas }) => {
		canvas.getByText("Never synced");
	},
};

export const NothingToSyncYet: Story = {
	args: {
		label: "Slack",
		status: {
			...baseStatus,
			kind: "SLACK",
			health: "PENDING",
			backfillSupported: false,
			lastSuccessfulSyncAt: undefined,
			rateLimit: undefined,
			resourceCounts: { total: 0, errored: 0, pending: 0, stale: 0 },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText(/no resources to sync yet/iu);
		await expect(canvas.queryByText(/never synced/iu)).not.toBeInTheDocument();
	},
};

export const WebhookNotRegistered: Story = {
	args: { status: { ...baseStatus, webhookRegistered: false } },
	play: async ({ canvas }) => {
		canvas.getByText(/not registered/iu);
	},
};

export const NoWebhookEventsYet: Story = {
	args: { status: { ...baseStatus, lastEventProcessedAt: undefined } },
	play: async ({ canvas }) => {
		canvas.getByText(/no events yet/iu);
	},
};

export const WebhookNotTracked: Story = {
	args: {
		status: {
			...baseStatus,
			kind: "GITLAB",
			webhookRegistered: undefined,
			lastEventProcessedAt: undefined,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/^webhook$/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/no events yet/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/not registered/iu)).not.toBeInTheDocument();
		canvas.getByText(/rate limit/iu);
	},
};

export const NoDiagnosticsAtAll: Story = {
	args: {
		label: "Slack",
		status: {
			...baseStatus,
			kind: "SLACK",
			backfillSupported: false,
			rateLimit: undefined,
			webhookRegistered: undefined,
			lastEventProcessedAt: undefined,
		},
		onBackfill: undefined,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("listitem")).not.toBeInTheDocument();
	},
};

export const RateLimitNearlyExhausted: Story = {
	args: {
		status: {
			...baseStatus,
			health: "DEGRADED",
			rateLimit: {
				limit: 5000,
				remaining: 220,
				resetAt: minutesAfter(12),
				observedAt: minutesBefore(1),
			},
		},
	},
};

export const RateLimitResetTooltip: Story = {
	play: async ({ canvas }) => {
		await userEvent.hover(canvas.getByText("4,812"));
		await expectSettledVisible(await screen.findByText(/resets in/iu));
	},
};

export const RateLimitThrottled: Story = {
	args: {
		label: "Slack",
		status: {
			...baseStatus,
			kind: "SLACK",
			health: "DEGRADED",
			backfillSupported: false,
			rateLimit: { observedAt: minutesBefore(1), throttledUntil: minutesAfter(10) },
		},
		onBackfill: undefined,
	},
	play: async ({ canvas }) => {
		const reading = canvas.getByText(/throttled/iu);
		await expect(reading).toHaveTextContent(/retry in/iu);
		await expect(canvas.queryByText(/^\/\s*[\d,]+$/u)).not.toBeInTheDocument();
	},
};

export const RateLimitCeilingOnly: Story = {
	args: {
		status: {
			...baseStatus,
			rateLimit: { limit: 5000, observedAt: minutesBefore(90) },
		},
	},
	play: async ({ canvas }) => {
		canvas.getByText(/limit 5,000/iu);
		await expect(canvas.queryByText(/^\/\s*[\d,]+$/u)).not.toBeInTheDocument();
	},
};

export const RateLimitNotReported: Story = {
	args: {
		status: {
			...baseStatus,
			rateLimit: { observedAt: minutesBefore(90), throttledUntil: minutesBefore(30) },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/rate limit/iu)).not.toBeInTheDocument();
		await expect(canvas.queryByText(/throttled/iu)).not.toBeInTheDocument();
	},
};

export const ScheduledBackfill: Story = {
	args: { status: { ...baseStatus, backfill: { state: "IN_PROGRESS", percent: 40 } } },
	play: async ({ canvas }) => {
		canvas.getByText("In Progress");
		await expect(canvas.queryByText(/IN_PROGRESS/u)).not.toBeInTheDocument();
	},
};

export const BackfillFromSplitMenu: Story = {
	play: async ({ args, canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /more sync options/iu }));
		await userEvent.click(await screen.findByRole("menuitem", { name: /run backfill/iu }));
		await expect(args.onBackfill).toHaveBeenCalledTimes(1);
	},
};

export const BackfillUnsupported: Story = {
	args: { status: { ...baseStatus, backfillSupported: false } },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("button", { name: /more sync options/iu }),
		).not.toBeInTheDocument();
	},
};

export const SyncTriggerPending: Story = {
	args: { triggeringType: "RECONCILIATION" },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: /starting…/iu })).toBeDisabled();
		await expect(canvas.getByRole("button", { name: /more sync options/iu })).toBeDisabled();
	},
};

export const ActiveJobRunning: Story = {
	args: { status: { ...baseStatus, activeJob: runningJob } },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByLabelText(/connection health/iu)).toHaveTextContent("Syncing");
		const cancel = canvas.getByRole("button", { name: /^cancel$/iu });
		await expect(cancel).toBeEnabled();
		await userEvent.click(cancel);
		await expect(args.onCancel).toHaveBeenCalledTimes(1);
	},
};

export const Cancelling: Story = {
	args: {
		isCancelling: true,
		status: { ...baseStatus, activeJob: { ...runningJob, cancelRequested: true } },
	},
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: /stopping after current step/iu }),
		).toBeDisabled();
	},
};

export const Slack: Story = {
	args: {
		label: "Slack",
		status: {
			...baseStatus,
			kind: "SLACK",
			backfillSupported: false,
			rateLimit: undefined,
			webhookRegistered: undefined,
			resourceCounts: { total: 3, errored: 0, pending: 0, stale: 0 },
		},
		onBackfill: undefined,
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByText(/rate limit/iu)).not.toBeInTheDocument();
		canvas.getByText(/^webhook$/iu);
		await expect(canvas.queryByText(/not registered/iu)).not.toBeInTheDocument();
	},
};

export const WithActions: Story = {
	args: {
		actions: (
			<a
				href="https://github.com/settings/installations"
				target="_blank"
				rel="noreferrer"
				className={buttonVariants({ variant: "outline", size: "sm" })}
			>
				Manage installation on GitHub
				<ExternalLinkIcon className="size-3.5" />
			</a>
		),
	},
	play: async ({ canvas }) => {
		const link = canvas.getByText(/manage installation on github/iu).closest("a");
		await expect(link).toHaveAttribute("href", "https://github.com/settings/installations");
	},
};

export const ConnectionInactive: Story = {
	args: {
		isConnectionActive: false,
		status: { ...baseStatus, connectionState: "SUSPENDED", health: "SUSPENDED" },
	},
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button", { name: /sync now/iu })).not.toBeInTheDocument();
	},
};

export const Loading: Story = { args: { status: undefined, isLoading: true } };

export const Missing: Story = { args: { status: undefined, isConnectionActive: false } };

export const LoadError: Story = {
	args: { status: undefined, error: new Error("503 Service Unavailable") },
	play: async ({ args, canvas }) => {
		canvas.getByText(/couldn't load the github connection/iu);
		await userEvent.click(canvas.getByRole("button", { name: /retry/iu }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};
