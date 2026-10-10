import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { WorkspaceLlmConnection } from "@/api/types.gen";
import { expectSettledVisible } from "@/stories/overlay";
import { expectDialogFitsViewport } from "@/stories/reflow";

import { WorkspaceLlmConnectionFormDialog } from "./WorkspaceLlmConnectionFormDialog";

const mockConnection: WorkspaceLlmConnection = {
	id: 1,
	slug: "my-openai",
	displayName: "My OpenAI account",
	authMode: "BEARER",
	apiProtocol: "openai-completions",
	purposes: ["PRACTICE_REVIEW", "MENTOR", "PRACTICE_DECISION"],
	baseUrl: "https://api.openai.com/v1",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "ab12",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

const meta = {
	component: WorkspaceLlmConnectionFormDialog,
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
} satisfies Meta<typeof WorkspaceLlmConnectionFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Connect: Story = {};

export const Edit: Story = {
	args: { editing: mockConnection },
};

export const ConnectEmbeddings: Story = {
	play: async ({ args }) => {
		await userEvent.type(await screen.findByLabelText("Display name"), "Embeddings");
		await userEvent.click(screen.getByRole("combobox", { name: "API" }));
		await userEvent.click(await screen.findByRole("option", { name: "Embeddings API" }));
		await userEvent.click(screen.getByRole("button", { name: "Add connection" }));
		await expect(args.onCreate).toHaveBeenCalledWith(
			expect.objectContaining({ displayName: "Embeddings", apiProtocol: "openai-embeddings" }),
		);
	},
};

export const EditEmbeddings: Story = {
	args: {
		editing: {
			...mockConnection,
			id: 2,
			slug: "embeddings",
			displayName: "Embeddings",
			apiProtocol: "openai-embeddings",
			purposes: ["PRACTICE_EMBEDDING"],
		},
	},
	play: async () => {
		const api = await screen.findByLabelText("API");
		await expectSettledVisible(api);
		await expect(api).toHaveValue("Embeddings API");
		await expect(screen.queryByRole("combobox", { name: "API" })).toBeNull();
	},
};

export const Submitting: Story = {
	args: { isSubmitting: true },
};

/** WCAG 2.2 SC 1.4.10 at 320 px: a `fixed` popup that outgrows the viewport hangs off both ends. */
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

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Add connection" }));
		await expectSettledVisible(await screen.findByText(/enter a display name/iu));
	},
};
