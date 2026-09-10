import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";
import { withStandardPage } from "@/stories/decorators";
import { expectNoPageOverflow } from "@/test/reflow";
import { githubTarget } from "./github-access-fixtures";
import { WorkspaceGithubAccessPage } from "./WorkspaceGithubAccessPage";

const meta = {
	component: WorkspaceGithubAccessPage,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		workspaceSlug: "engineering",
		jobs: {},
		onCancelJob: fn(),
		isOwner: true,
		busy: false,
		savingPolicy: false,
		state: {
			status: "ready",
			data: { configured: true, targets: [githubTarget] },
			approvedGroups: ["engineering", "research"],
		},
		onConfigure: fn().mockResolvedValue(undefined),
		onRenew: fn(),
		onPreview: fn(),
		onApprove: fn(),
		onReconcile: fn(),
		onPause: fn(),
		onEnd: fn(),
		onDecide: fn(),
	},
} satisfies Meta<typeof WorkspaceGithubAccessPage>;
export default meta;
type Story = StoryObj<typeof meta>;
export const Owner: Story = {};
export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("group", { name: "Loading GitHub access" })).toBeVisible();
	},
};
export const ErrorState: Story = {
	args: { state: { status: "error", error: new Error("Service unavailable"), onRetry: fn() } },
};
export const Empty: Story = {
	args: {
		state: {
			status: "ready",
			data: { configured: true, targets: [] },
			approvedGroups: ["engineering"],
		},
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Add GitHub target" }));
		await expect(canvas.getByRole("textbox", { name: "Organization login" })).toHaveValue("");
		await expect(
			canvas.getByRole("button", { name: "Save and create approval link" }),
		).toBeEnabled();
		await expect(
			canvas.getByRole("checkbox", {
				name: "Use directory eligibility instead of access requests",
			}),
		).not.toBeChecked();
	},
};
export const RequestsWithoutDirectory: Story = {
	args: {
		state: { status: "ready", data: { configured: true, targets: [] }, approvedGroups: [] },
	},
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Add GitHub target" }));
		await expect(
			canvas.getByRole("checkbox", {
				name: "Use directory eligibility instead of access requests",
			}),
		).toBeDisabled();
		await expect(
			canvas.getByRole("button", { name: "Save and create approval link" }),
		).toBeEnabled();
	},
};
export const AppNotConfigured: Story = {
	args: {
		state: { status: "ready", data: { configured: false, targets: [] }, approvedGroups: [] },
	},
};
export const Administrator: Story = {
	args: { isOwner: false },
	play: async ({ canvas }) => {
		await expect(
			canvas.queryByRole("button", { name: "Add GitHub target" }),
		).not.toBeInTheDocument();
		await expect(
			canvas.queryByRole("button", { name: "Approve this exact preview" }),
		).not.toBeInTheDocument();
		await expect(canvas.getByRole("button", { name: "Reconcile / retry" })).toBeEnabled();
	},
};
export const PausedRemoval: Story = {
	args: {
		state: {
			status: "ready",
			approvedGroups: ["engineering"],
			data: {
				configured: true,
				targets: [
					{
						...githubTarget,
						paused: true,
						status: "ENDING",
						preview: undefined,
						failureReason:
							"The Access App is unavailable. Restore credentials to finish pending removals.",
						members: githubTarget.members.map((member) => ({
							...member,
							revocationRequested: true,
							blocker: "External access remains until GitHub confirms removal",
						})),
					},
				],
			},
		},
	},
};
export const EndConfirmation: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: "End management" }));
		const dialog = await screen.findByRole("alertdialog");
		await expect(dialog).toHaveTextContent(
			"Authority and credentials remain until GitHub confirms removal",
		);
	},
};
export const Reflow: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectNoPageOverflow();
	},
};
