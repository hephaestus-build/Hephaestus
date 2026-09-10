import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { LlmModel } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";
import {
	expectControlOnScreen,
	expectDialogBodyScrolls,
	expectDialogFitsViewport,
} from "@/test/reflow";

import { AdminLlmModelFormDialog } from "./AdminLlmModelFormDialog";
import type { WorkspaceOption } from "./workspace-options";

const mockModel: LlmModel = {
	processingLocation: "UNCLASSIFIED",
	id: 1,
	slug: "gpt-5-eu",
	displayName: "GPT-5",
	upstreamModelId: "gpt-5",
	connectionId: 1,
	connectionDisplayName: "OpenAI production",
	enabled: true,
	supportsReasoning: true,
	visibility: "GRANTED",
	grantedWorkspaceIds: [1],
	currentPrice: {
		id: 1,
		pricingMode: "PRICED",
		per1mInputUsd: 3,
		per1mOutputUsd: 15,
		currency: "USD",
		effectiveFrom: new Date("2026-05-01T00:00:00Z"),
	},
	createdAt: new Date("2026-05-01T10:00:00Z"),
};

const mockWorkspaces: WorkspaceOption[] = [
	{ id: 1, displayName: "Example Workspace", workspaceSlug: "example-workspace" },
	{ id: 2, displayName: "Acme Corp", workspaceSlug: "acme" },
];

const meta = {
	component: AdminLlmModelFormDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		editing: null,
		workspaceOptions: mockWorkspaces,
		probedModelIds: ["gpt-5", "gpt-5-mini"],
		isSubmitting: false,
		onSave: fn(),
	},
} satisfies Meta<typeof AdminLlmModelFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AddModel: Story = {};

export const EditModel: Story = {
	args: { editing: mockModel },
};

export const EditKeepsAccessSeparate: Story = {
	args: { editing: mockModel },
	play: async () => {
		await expect(screen.queryByText("Initial workspace access")).not.toBeInTheDocument();
	},
};

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: /add model/i }));
		await expectSettledVisible(await screen.findByText(/display name is required/i));
		await expectSettledVisible(await screen.findByText(/upstream model id is required/i));
	},
};

/**
 * At the WCAG 2.2 SC 1.4.10 reflow width (320 px). Proves `DialogBody`'s bound: only the body
 * scrolls, so the title stays pinned and "Add model" reachable.
 */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		const submit = await screen.findByRole("button", { name: /^add model$/i });
		await expectDialogFitsViewport();
		await expectDialogBodyScrolls();
		await expectControlOnScreen(submit);
		await expectControlOnScreen(screen.getByRole("button", { name: /^close$/i }));
	},
};
