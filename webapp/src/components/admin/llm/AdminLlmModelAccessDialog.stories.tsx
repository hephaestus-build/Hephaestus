import type { Meta, StoryObj } from "@storybook/react";
import { fn, screen, userEvent } from "storybook/test";

import type { LlmModel } from "@/api/types.gen";
import { expectDialogFitsViewport } from "@/test/reflow";

import { AdminLlmModelAccessDialog } from "./AdminLlmModelAccessDialog";

const model: LlmModel = {
	processingLocation: "UNCLASSIFIED",
	id: 7,
	slug: "gpt-5",
	displayName: "GPT-5",
	upstreamModelId: "gpt-5",
	connectionId: 1,
	connectionDisplayName: "OpenAI production",
	enabled: true,
	supportsReasoning: true,
	visibility: "GRANTED",
	grantedWorkspaceIds: [10],
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

const meta = {
	component: AdminLlmModelAccessDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		model,
		workspaceOptions: [
			{ id: 10, displayName: "Teaching team", workspaceSlug: "teaching" },
			{ id: 11, displayName: "Research team", workspaceSlug: "research" },
		],
		isSubmitting: false,
		onSave: fn(),
	},
} satisfies Meta<typeof AdminLlmModelAccessDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const SelectedWorkspaces: Story = {};

export const WorkspaceListError: Story = {
	args: {
		workspacesError: {
			type: "about:blank",
			title: "Service Unavailable",
			status: 503,
			detail: "The workspace directory is temporarily unavailable.",
		},
		onRetryWorkspaces: fn(),
	},
};

/** At the WCAG 2.2 SC 1.4.10 reflow width (320 px): the dialog must stay inside the viewport. */
export const MobileReflow: Story = {
	args: {
		model: { ...model, visibility: "PUBLIC", grantedWorkspaceIds: [] },
		workspaceOptions: Array.from({ length: 14 }, (_, index) => ({
			id: index + 1,
			displayName: `Workspace ${index + 1}`,
			workspaceSlug: `workspace-${index + 1}`,
		})),
	},
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		await userEvent.click(await screen.findByRole("radio", { name: /^Selected workspaces/i }));
		await screen.findByRole("button", { name: /save access/i });
		await expectDialogFitsViewport();
	},
};
