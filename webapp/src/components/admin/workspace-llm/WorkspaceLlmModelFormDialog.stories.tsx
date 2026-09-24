import type { Meta, StoryObj } from "@storybook/react";
import { fn, screen, userEvent } from "storybook/test";

import type { WorkspaceLlmModel } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { expectDialogFitsViewport } from "@/stories/reflow";

import { WorkspaceLlmModelFormDialog } from "./WorkspaceLlmModelFormDialog";

const mockModel: WorkspaceLlmModel = {
	id: 1,
	slug: "gpt-5-mini",
	displayName: "GPT-5 mini",
	upstreamModelId: "openai/gpt-5-mini",
	connectionId: 1,
	connectionDisplayName: "My OpenAI account",
	enabled: true,
	reasoningEffort: "MEDIUM",
	contextWindow: 128_000,
	maxOutputTokens: 16_000,
	pricingMode: "PRICED",
	per1mInputUsd: 0.25,
	per1mOutputUsd: 2,
	currency: "USD",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

const meta = {
	component: WorkspaceLlmModelFormDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		editing: null,
		isSubmitting: false,
		onCreate: fn(),
		onUpdate: fn(),
	},
} satisfies Meta<typeof WorkspaceLlmModelFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AddModel: Story = {};

export const EditModel: Story = {
	args: { editing: mockModel },
};

export const FreeModel: Story = {
	args: { editing: { ...mockModel, pricingMode: "NO_CHARGE", priceNote: "self-hosted, no cost" } },
};

/** WCAG 2.2 SC 1.4.10 at 320 px: `DialogBody`'s height bound is all that keeps the popup on screen. */
export const MobileReflow: Story = {
	args: { editing: mockModel },
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		await screen.findByRole("button", { name: /save changes/iu });
		await expectDialogFitsViewport();
	},
};

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: /add inactive model/iu }));
		await expectSettledVisible(await screen.findByText(/display name is required/iu));
		await expectSettledVisible(await screen.findByText(/upstream model id is required/iu));
	},
};
