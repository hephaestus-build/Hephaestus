import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, userEvent } from "storybook/test";

import { expectGenuinelyDisabled } from "@/test/controls";

import type { ReleaseStatus } from "@/api/types.gen";
import {
	daysBefore,
	hoursBefore,
	minutesAfter,
	minutesBefore,
} from "@/components/common/story-clock";

import {
	InstanceReleaseCard,
	type InstanceReleaseCardState,
	type ReleaseCheckRequest,
} from "./InstanceReleaseCard";

const running = {
	version: "1.2.3",
	channel: "RELEASE",
	commit: "a".repeat(40),
	image: `ghcr.io/hephaestus-build/application-server@sha256:${"b".repeat(64)}`,
	roles: ["SERVER"],
} satisfies ReleaseStatus["running"];

const latest = {
	version: "1.3.0",
	publishedAt: daysBefore(3),
	notesUrl: "https://github.com/hephaestus-build/Hephaestus/releases/tag/v1.3.0",
	schemaMigrations: true,
} satisfies NonNullable<ReleaseStatus["latest"]>;

const onCheck = fn();

function ready(
	release: Partial<ReleaseStatus>,
	check: ReleaseCheckRequest = { status: "idle" },
): InstanceReleaseCardState {
	return {
		status: "ready",
		release: { running, status: "NEVER_CHECKED", ...release },
		check,
		onCheck,
	};
}

const meta = {
	component: InstanceReleaseCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { state: ready({}) },
} satisfies Meta<typeof InstanceReleaseCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not checked yet")).toBeVisible();
		await userEvent.click(canvas.getByRole("button", { name: "Check now" }));
		await expect(onCheck).toHaveBeenCalledOnce();
	},
};

export const UpToDate: Story = {
	args: {
		state: ready({
			status: "CURRENT",
			lastAttempt: hoursBefore(2),
			lastSuccess: hoursBefore(2),
			nextCheck: minutesAfter(22 * 60),
			latest: { ...latest, version: "1.2.3", schemaMigrations: false },
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Up to date")).toBeVisible();
		await expect(canvas.queryByRole("link", { name: /release notes/i })).toBeNull();
	},
};

export const UpdateAvailable: Story = {
	args: {
		state: ready({
			status: "UPDATE_AVAILABLE",
			lastAttempt: hoursBefore(1),
			lastSuccess: hoursBefore(1),
			nextCheck: minutesAfter(23 * 60),
			latest,
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Update available")).toBeVisible();
		await expect(canvas.getByRole("link", { name: /release notes/i })).toHaveAttribute(
			"href",
			latest.notesUrl,
		);
		await expect(canvas.getByText(/includes schema migrations/i)).toBeVisible();
	},
};

export const UpdateAvailableWithoutMigrations: Story = {
	args: {
		state: ready({
			status: "UPDATE_AVAILABLE",
			lastSuccess: hoursBefore(1),
			latest: { ...latest, schemaMigrations: false },
		}),
	},
};

export const UpdateAvailableMigrationsUnknown: Story = {
	args: {
		state: ready({
			status: "UPDATE_AVAILABLE",
			lastSuccess: hoursBefore(1),
			latest: { ...latest, schemaMigrations: undefined },
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/read the release notes/i)).toBeVisible();
	},
};

export const CheckFailedAfterASuccess: Story = {
	args: {
		state: ready({
			status: "FAILED",
			failure: "UNAVAILABLE",
			lastAttempt: minutesBefore(5),
			lastSuccess: daysBefore(2),
			nextCheck: minutesAfter(10),
			latest,
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Check failed")).toBeVisible();
		await expect(canvas.getByText(/found v1\.3\.0/)).toBeVisible();
		await expect(canvas.queryByText("Update available")).toBeNull();
		await expect(canvas.getByRole("button", { name: "Check now" })).toBeEnabled();
	},
};

export const RateLimited: Story = {
	args: {
		state: ready({
			status: "FAILED",
			failure: "RATE_LIMITED",
			lastAttempt: minutesBefore(1),
			nextCheck: minutesAfter(45),
			retryUntil: minutesAfter(45),
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/rate-limited/)).toBeVisible();
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Check now" }));
	},
};

export const Checking: Story = {
	args: { state: ready({}, { status: "pending" }) },
	play: async ({ canvas }) => {
		await expectGenuinelyDisabled(canvas.getByRole("button", { name: "Checking…" }));
	},
};

export const CheckRequestFailed: Story = {
	args: { state: ready({}, { status: "error", error: new Error("Network Error") }) },
};

export const CommitBuild: Story = {
	args: {
		state: ready({
			status: "NOT_APPLICABLE",
			running: { ...running, version: "c".repeat(40), commit: "c".repeat(40) },
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Not a release")).toBeVisible();
		await expect(canvas.getByText("commit ccccccc")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Check now" })).toBeNull();
	},
};

export const DevelopmentBuild: Story = {
	args: {
		state: ready({
			status: "NOT_APPLICABLE",
			running: {
				version: "0.0.0-development",
				channel: "DEVELOPMENT",
				roles: ["SERVER", "WORKER", "WEBHOOK"],
			},
		}),
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show deployment identity" }));
		await expect(canvas.getAllByText("not reported")).toHaveLength(2);
		await expect(canvas.getByText("server, worker, webhook")).toBeVisible();
	},
};

export const IdentityExpandedOnAPhone: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvas, canvasElement }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Show deployment identity" }));
		await expect(canvas.getByText(running.image)).toBeVisible();
		await expect(canvasElement.scrollWidth).toBeLessThanOrEqual(canvasElement.clientWidth);
	},
};

export const ChecksDisabled: Story = {
	args: { state: ready({ status: "DISABLED" }) },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("Checks disabled")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Check now" })).toBeNull();
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
};

export const Unavailable: Story = {
	args: { state: { status: "error", error: new Error("Network Error"), onRetry: fn() } },
};
