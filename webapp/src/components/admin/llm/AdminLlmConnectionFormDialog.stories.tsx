import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { LlmConnection } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";
import { expectDialogFitsViewport } from "@/test/reflow";

import {
	AdminLlmConnectionFormDialog,
	type AdminLlmConnectionFormDialogProps,
} from "./AdminLlmConnectionFormDialog";

const mockConnection: LlmConnection = {
	id: 1,
	slug: "openai-production",
	displayName: "OpenAI production",
	authMode: "BEARER",
	apiProtocol: "openai-responses",
	baseUrl: "https://openai-production.example.com/openai",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "ab12",
	createdAt: new Date("2026-05-01T10:00:00Z"),
};

const meta = {
	component: AdminLlmConnectionFormDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		editing: null,
		isSubmitting: false,
		onCreate: fn(),
		onUpdate: fn(),
		onProbe: fn(),
		isProbing: false,
		onProbed: fn(),
	},
} satisfies Meta<typeof AdminLlmConnectionFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const AddConnection: Story = {};

export const EditConnection: Story = {
	args: { editing: mockConnection },
};

export const Probing: Story = {
	args: { isProbing: true },
};

export const DiscoveryUnsupported: Story = {
	args: {
		onProbe: fn<AdminLlmConnectionFormDialogProps["onProbe"]>((_request, callbacks) =>
			callbacks.onSuccess({ reachable: false, models: [], message: "Connection timed out" }),
		),
	},
	play: async () => {
		await userEvent.type(await screen.findByLabelText("Base URL"), "https://example.com");
		await userEvent.click(screen.getByRole("button", { name: /test & fetch models/iu }));
		await expectSettledVisible(await screen.findByText(/discovery unsupported/iu));
		await expect(screen.getByRole("button", { name: /save inactive connection/iu })).toBeEnabled();
	},
};

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(
			await screen.findByRole("button", { name: /save inactive connection/iu }),
		);
		await expectSettledVisible(await screen.findByText(/display name is required/iu));
	},
};

/** At the WCAG 2.2 SC 1.4.10 reflow width (320 px): the dialog must stay inside the viewport. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		await screen.findByRole("button", { name: /save inactive connection/iu });
		await expectDialogFitsViewport();
	},
};
