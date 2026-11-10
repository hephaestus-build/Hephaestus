import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { LlmConnection } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { expectDialogFitsViewport } from "@/stories/reflow";

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
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
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

/** The save says so in its own button, and Cancel still closes the form while it runs. */
export const Submitting: Story = {
	args: { editing: mockConnection, isSubmitting: true },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByRole("button", { name: "Saving…" })).toBeDisabled();
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeEnabled();
	},
};

export const SavedProbeRefused: Story = {
	args: {
		editing: mockConnection,
		onProbeSaved: fn<NonNullable<AdminLlmConnectionFormDialogProps["onProbeSaved"]>>(
			(_id, callbacks) =>
				callbacks.onSuccess({
					reachable: false,
					models: [],
					statusCode: 401,
					message: "The provider answered with HTTP 401.",
				}),
		),
	},
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Test saved connection" }));
		const failure = await screen.findByRole("alert");
		await expectSettledVisible(failure);
		await expect(failure).toHaveTextContent(
			"We could not fetch the model list. The provider answered with HTTP 401. You can still save the connection and enter a model ID.",
		);
	},
};

export const Probing: Story = {
	args: { isProbing: true },
};

export const DiscoveryUnsupported: Story = {
	args: {
		onProbe: fn<AdminLlmConnectionFormDialogProps["onProbe"]>((_request, callbacks) =>
			callbacks.onSuccess({
				reachable: false,
				models: [],
				statusCode: 404,
				message: "The provider answered with HTTP 404.",
			}),
		),
	},
	play: async () => {
		const baseUrl = await screen.findByLabelText("Base URL");
		await userEvent.clear(baseUrl);
		await userEvent.type(baseUrl, "https://example.com");
		await userEvent.click(screen.getByRole("button", { name: "Test and fetch models" }));
		await expectSettledVisible(await screen.findByText(/could not fetch the model list/iu));
		await expect(screen.getByRole("button", { name: "Add connection" })).toBeEnabled();
	},
};

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Add connection" }));
		await expectSettledVisible(await screen.findByText(/enter a display name/iu));
	},
};

/** At the WCAG 2.2 SC 1.4.10 reflow width (320 px): the dialog must stay inside the viewport. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		await screen.findByRole("button", { name: "Add connection" });
		await expectDialogFitsViewport();
	},
};
