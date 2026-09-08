import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { DirectoryPolicy, DirectorySource } from "@/api/types.gen";
import { minutesBefore } from "@/components/common/story-clock";
import { withStandardPage } from "@/stories/decorators";
import { expectSettledVisible } from "@/test/overlay";
import { expectNoPageOverflow } from "@/test/reflow";
import { WorkspaceDirectoryAccessPage } from "./WorkspaceDirectoryAccessPage";

const sources: DirectorySource[] = [
	{
		registrationId: "organization",
		displayName: "Engineering identity",
		issuer: "https://identity.example.com/realms/engineering",
		groupIds: ["engineering", "research"],
	},
];
const evidence = {
	startedAt: minutesBefore(2),
	completedAt: minutesBefore(1),
	fresh: true,
	eligiblePeople: 3,
	awaitingIdentity: 1,
	additions: 1,
	removals: 0,
	groupNames: { engineering: "Engineering" },
};
const policy: DirectoryPolicy = {
	connectionId: 9,
	registrationId: "organization",
	issuer: "https://identity.example.com/realms/engineering",
	status: "ACTIVE",
	health: "HEALTHY",
	configurationVersion: 2,
	draftGroupIds: ["engineering"],
	approvedGroupIds: ["engineering"],
	approvedAt: minutesBefore(1),
	approvedEvidence: evidence,
	previewEvidence: evidence,
	blockers: [],
	members: [
		{
			accountId: 1,
			displayName: "Workspace owner",
			role: "OWNER",
			source: "MANUAL",
			eligible: true,
			suspended: false,
			adoptable: false,
			change: "PROTECTED",
		},
		{
			accountId: 2,
			displayName: "Manual administrator",
			role: "ADMIN",
			source: "MANUAL",
			eligible: true,
			suspended: false,
			adoptable: true,
			change: "PROTECTED",
		},
		{
			accountId: 3,
			displayName: "Directory member",
			role: "MEMBER",
			source: "DIRECTORY",
			eligible: true,
			suspended: false,
			adoptable: false,
			change: "UNCHANGED",
		},
	],
};
const meta = {
	component: WorkspaceDirectoryAccessPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "team",
		isOwner: true,
		state: { status: "ready", policy, sources, onRetrySources: fn() },
		jobs: { jobs: [], isLoading: false, isError: false, onRetry: fn() },
		onConfigure: fn().mockResolvedValue(undefined),
		onPreview: fn(),
		onApprove: fn(),
		onReconcile: fn(),
		onStatusChange: fn(),
		onAdopt: fn(),
		onCancel: fn(),
	},
} satisfies Meta<typeof WorkspaceDirectoryAccessPage>;
export default meta;
type Story = StoryObj<typeof meta>;

export const Active: Story = {
	play: async ({ canvas }) => {
		await expect(
			canvas.getByRole("button", { name: "Adopt directory access for Manual administrator" }),
		).toBeEnabled();
		await expect(
			canvas.queryByRole("button", { name: "Adopt directory access for Workspace owner" }),
		).not.toBeInTheDocument();
	},
};
export const Loading: Story = { args: { state: { status: "loading" } } };
export const LoadError: Story = {
	args: { state: { status: "error", error: new Error("Unavailable"), onRetry: fn() } },
};
export const Empty: Story = { args: { state: { status: "ready", sources, onRetrySources: fn() } } };
export const NoApprovedSources: Story = {
	args: { state: { status: "ready", sources: [], onRetrySources: fn() } },
};
export const SourceError: Story = {
	args: {
		state: {
			status: "ready",
			policy,
			sources: [],
			sourceError: new Error("Unavailable"),
			onRetrySources: fn(),
		},
	},
};
export const Draft: Story = {
	args: {
		state: {
			status: "ready",
			policy: { ...policy, status: "DRAFT", approvedEvidence: undefined, approvedGroupIds: [] },
			sources,
			onRetrySources: fn(),
		},
	},
};
export const Stale: Story = {
	args: {
		state: {
			status: "ready",
			policy: {
				...policy,
				approvedEvidence: { ...evidence, fresh: false, startedAt: minutesBefore(20) },
				previewEvidence: { ...evidence, fresh: false, startedAt: minutesBefore(20) },
				blockers: ["A fresh complete read is required before new access can be granted."],
			},
			sources,
			onRetrySources: fn(),
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Approve this preview" })).toBeDisabled();
		await expect(
			canvas.getByRole("button", { name: "Adopt directory access for Manual administrator" }),
		).toBeDisabled();
	},
};
export const FailedRead: Story = {
	args: {
		state: {
			status: "ready",
			policy: {
				...policy,
				health: "FAILED",
				failureReason: "Directory read could not be completed. Review the connection and retry.",
				approvedEvidence: { ...evidence, fresh: false },
				previewEvidence: undefined,
			},
			sources,
			onRetrySources: fn(),
		},
	},
};
export const Paused: Story = {
	args: {
		state: {
			status: "ready",
			policy: { ...policy, status: "PAUSED" },
			sources,
			onRetrySources: fn(),
		},
	},
};
export const Ended: Story = {
	args: {
		state: {
			status: "ready",
			policy: {
				...policy,
				status: "ENDED",
				members: [policy.members[0]].filter((member) => member !== undefined),
			},
			sources,
			onRetrySources: fn(),
		},
	},
};
export const Administrator: Story = {
	args: { isOwner: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Reconcile now" })).toBeEnabled();
		await expect(
			canvas.queryByRole("button", { name: "Save configuration" }),
		).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("button", { name: "Approve this preview" }),
		).not.toBeInTheDocument();
		await expect(canvas.queryByRole("button", { name: "End management" })).not.toBeInTheDocument();
	},
};
export const Running: Story = {
	args: {
		activeJob: {
			id: 12,
			type: "RECONCILIATION",
			trigger: "MANUAL",
			status: "RUNNING",
			cancelRequested: false,
			createdAt: minutesBefore(1),
			progress: { currentStep: "Reading approved groups" },
		},
	},
};
export const ConfirmAdoption: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(
			canvas.getByRole("button", { name: "Adopt directory access for Manual administrator" }),
		);
		const dialog = await screen.findByRole("alertdialog", {
			name: "Adopt Manual administrator's access?",
		});
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveTextContent("directory-managed Member role");
		await expect(dialog).toHaveTextContent("suspension will not be lifted");
	},
};
export const ConfirmEnd: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "End management" }));
		const dialog = await screen.findByRole("alertdialog", { name: "End directory management?" });
		await expectSettledVisible(dialog);
		await expect(dialog).toHaveTextContent("removed immediately");
		await expect(dialog).toHaveTextContent("suspension records remain");
	},
};
export const MobileReflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: expectNoPageOverflow,
};
