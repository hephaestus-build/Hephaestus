import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn } from "storybook/test";

import type { ReleaseStatus } from "@/api/types.gen";
import { hoursBefore } from "@/components/common/story-clock";

import { ReleaseStatusCard, type ReleaseStatusCardState } from "./ReleaseStatusCard";

const data = {
	running: {
		version: "1.2.3",
		commit: "a".repeat(40),
		channel: "stable",
		identityStatus: "DEPLOYMENT_REPORTED",
		roles: ["server"],
		images: {
			"application-server": `ghcr.io/hephaestus-build/application-server@sha256:${"b".repeat(64)}`,
		},
	},
	status: "NEVER_CHECKED",
	enabled: true,
	backupRestoreStatus: "UNKNOWN",
	upgradeGuideUrl: "https://docs.hephaestus.build/admin/install#upgrades",
} satisfies ReleaseStatus;

const ready = {
	status: "ready",
	data,
	check: { status: "idle" },
	onCheck: fn(),
} satisfies ReleaseStatusCardState;

const meta = {
	component: ReleaseStatusCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { state: ready },
} satisfies Meta<typeof ReleaseStatusCard>;
export default meta;
type Story = StoryObj<typeof meta>;

export const NeverChecked: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Never checked")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Check for updates" })).toBeEnabled();
	},
};
export const UpdateAvailable: Story = {
	args: {
		state: {
			...ready,
			data: {
				...data,
				status: "UPDATE_AVAILABLE",
				lastAttempt: hoursBefore(1),
				lastSuccess: hoursBefore(1),
				available: {
					version: "1.3.0",
					notesUrl: "https://github.com/hephaestus-build/Hephaestus/releases/tag/v1.3.0",
					schemaMigrations: "REQUIRED",
					securityRelevance: "UNKNOWN",
					operatorActions:
						"Review this release and all intervening migration notes before upgrading.",
				},
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Update available")).toBeVisible();
		await expect(canvas.getByText(/Schema migrations: required/)).toBeVisible();
		await expect(canvas.getByRole("link", { name: /release notes/ })).toHaveAttribute(
			"href",
			"https://github.com/hephaestus-build/Hephaestus/releases/tag/v1.3.0",
		);
	},
};
export const Current: Story = {
	args: {
		state: {
			...ready,
			data: {
				...data,
				status: "CURRENT",
				lastAttempt: hoursBefore(1),
				lastSuccess: hoursBefore(1),
				nextCheck: hoursBefore(-23),
			},
		},
	},
};
export const Failed: Story = {
	args: {
		state: {
			...ready,
			data: {
				...data,
				status: "CHECK_FAILED",
				failureReason: "RATE_LIMITED",
				lastAttempt: hoursBefore(1),
				lastSuccess: hoursBefore(48),
			},
		},
	},
};
export const Disabled: Story = {
	args: { state: { ...ready, data: { ...data, enabled: false, status: "DISABLED" } } },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("button", { name: "Check for updates" }),
		).not.toBeInTheDocument();
	},
};
export const Unsupported: Story = {
	args: {
		state: {
			...ready,
			data: {
				...data,
				status: "UNSUPPORTED",
				running: {
					...data.running,
					version: "0.0.0-development",
					channel: "unknown",
					identityStatus: "UNKNOWN",
					images: {},
				},
			},
		},
	},
};
export const MixedIdentity: Story = {
	args: {
		state: {
			...ready,
			data: {
				...data,
				status: "UNSUPPORTED",
				running: { ...data.running, identityStatus: "MISMATCH" },
			},
		},
	},
};
export const Stale: Story = {
	args: {
		state: {
			...ready,
			data: {
				...data,
				status: "CURRENT",
				lastAttempt: hoursBefore(25),
				lastSuccess: hoursBefore(25),
				nextCheck: hoursBefore(1),
			},
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Update information is stale")).toBeVisible();
		await expect(canvas.queryByText("Current release")).not.toBeInTheDocument();
	},
};
export const Loading: Story = { args: { state: { status: "pending" } } };
export const Unavailable: Story = {
	args: { state: { status: "error", error: new Error("Offline"), onRetry: fn() } },
};
export const Checking: Story = { args: { state: { ...ready, check: { status: "pending" } } } };
export const RefreshFailed: Story = {
	args: { state: { ...ready, check: { status: "error", error: new Error("Offline") } } },
};
export const BackgroundRefreshFailed: Story = {
	args: { state: { ...ready, refreshError: { error: new Error("Offline"), onRetry: fn() } } },
};
export const DeploymentIdentity: Story = {
	play: async ({ canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Build and deployment identity" }));
		await expect(canvas.getByText(data.running.images["application-server"])).toBeVisible();
	},
};
