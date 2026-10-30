import type { Meta, StoryObj } from "@storybook/react";

import type { SyncJob } from "@/api/types.gen";

import { ActiveJobProgress } from "./ActiveJobProgress";

const base: SyncJob = {
	id: 1,
	type: "RECONCILIATION",
	trigger: "MANUAL",
	status: "RUNNING",
	cancelRequested: false,
	createdAt: new Date("2026-07-14T10:00:00Z"),
};

/**
 * Live progress for the currently running job. With a known, positive `itemsTotal` it renders a
 * determinate bar with an accessible value text; otherwise it degrades to an indeterminate spinner
 * labelled with the current phase (or the job type). Renders nothing when there is no active job.
 */
const meta = {
	component: ActiveJobProgress,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
} satisfies Meta<typeof ActiveJobProgress>;

export default meta;
type Story = StoryObj<typeof meta>;

/** No active job — the component renders nothing rather than an empty bar. */
export const NoActiveJob: Story = { args: { job: null } };

/** A known total drives a determinate bar with an accessible "X of Y records" value text. */
export const DeterminateProgress: Story = {
	args: { job: { ...base, itemsProcessed: 4, itemsTotal: 12 } },
};

/** Unknown total — falls back to the indeterminate spinner labelled with the current phase. */
export const IndeterminateWithStep: Story = {
	args: { job: { ...base, progress: { currentStep: "pull-requests" } } },
};

/** No phase either — the spinner is labelled with the humanised job type. */
export const IndeterminateNoStep: Story = {
	args: { job: { ...base, type: "BACKFILL" } },
};

/** A total of 0 must not render a 0/0 "complete" bar — it stays indeterminate. */
export const ZeroTotal: Story = {
	args: { job: { ...base, itemsProcessed: 0, itemsTotal: 0 } },
};

/** A long phase label truncates rather than pushing the layout. */
export const LongStep: Story = {
	args: {
		job: {
			...base,
			progress: {
				currentStep:
					"reconciling pull-request review threads for octocat/an-extremely-long-repository-name",
			},
		},
	},
};
