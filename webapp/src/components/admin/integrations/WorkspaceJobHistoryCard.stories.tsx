import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";

import type { SyncJob } from "@/api/types.gen";

import { WorkspaceJobHistoryCard } from "./WorkspaceJobHistoryCard";

const succeeded: SyncJob = {
	id: 2,
	type: "RECONCILIATION",
	trigger: "SCHEDULED",
	status: "SUCCEEDED",
	cancelRequested: false,
	createdAt: new Date("2026-07-13T02:00:00Z"),
	startedAt: new Date("2026-07-13T02:00:00Z"),
	finishedAt: new Date("2026-07-13T02:04:30Z"),
	itemsProcessed: 40,
	itemsTotal: 40,
};

/**
 * The card around a connection's sync history, the same on the GitHub, GitLab, Slack and Outline
 * pages. The table's own states live in `WorkspaceSyncJobsTable`; these stories show that the card keeps its
 * heading around each of them.
 */
const meta = {
	component: WorkspaceJobHistoryCard,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: { jobs: [succeeded] },
	argTypes: { jobs: { control: false } },
} satisfies Meta<typeof WorkspaceJobHistoryCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		canvas.getByText("Sync history");
		canvas.getByText("Succeeded");
	},
};

/** The heading stands while the rows load, and no job is claimed before the history arrives. */
export const Loading: Story = {
	args: { jobs: [], isLoading: true },
	play: async ({ canvas }) => {
		canvas.getByText("Sync history");
		await expect(canvas.queryByText("No syncs yet")).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: /^Show details/u })).not.toBeInTheDocument();
	},
};

export const LoadFailed: Story = {
	args: { jobs: [], isError: true, error: { status: 503 }, onRetry: fn() },
	play: async ({ args, canvas }) => {
		canvas.getByText("Sync history");
		canvas.getByText("We could not load the sync history");
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onRetry).toHaveBeenCalledTimes(1);
	},
};

export const Empty: Story = {
	args: { jobs: [] },
	play: async ({ canvas }) => {
		canvas.getByText("Sync history");
		canvas.getByText("No syncs yet");
	},
};
