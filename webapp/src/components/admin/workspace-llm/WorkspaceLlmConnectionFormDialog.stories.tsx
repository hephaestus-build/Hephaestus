import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { WorkspaceLlmConnection } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
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

/** The panel's "Add provider" opens this dialog, so its title and submit say the same. */
export const Connect: Story = {
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Add provider" });
		await expect(within(dialog).getByRole("button", { name: "Add provider" })).toBeEnabled();
	},
};

export const Edit: Story = {
	args: { editing: mockConnection },
	play: async () => {
		const dialog = await screen.findByRole("dialog", { name: "Edit provider" });
		await expect(within(dialog).getByRole("button", { name: "Save changes" })).toBeEnabled();
	},
};

export const ConnectEmbeddings: Story = {
	play: async ({ args }) => {
		await userEvent.type(await screen.findByLabelText("Display name"), "Embeddings");
		await userEvent.click(screen.getByRole("combobox", { name: "API" }));
		await userEvent.click(await screen.findByRole("option", { name: "Embeddings API" }));
		await userEvent.click(screen.getByRole("button", { name: "Add provider" }));
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

/** The providers failed to refresh while the form was open: the warning is in the form, and the draft stays editable. */
export const RefreshFailed: Story = {
	args: {
		editing: mockConnection,
		notice: (
			<QueryErrorAlert
				error={{ status: 503 }}
				title="We could not refresh your AI providers"
				onRetry={fn()}
			/>
		),
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(within(dialog).getByText("We could not refresh your AI providers"));
		await expect(within(dialog).getByRole("button", { name: "Retry" })).toBeEnabled();
		const name = within(dialog).getByLabelText("Display name");
		await userEvent.clear(name);
		await userEvent.type(name, "My OpenAI draft");
		await expect(name).toHaveValue("My OpenAI draft");
	},
};

export const RefreshRetrying: Story = {
	args: {
		editing: mockConnection,
		notice: (
			<QueryErrorAlert
				error={{ status: 503 }}
				title="We could not refresh your AI providers"
				pending
				onRetry={fn()}
			/>
		),
	},
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expect(within(dialog).getByRole("button", { name: "Retrying…" })).toBeDisabled();
		await expect(within(dialog).getByLabelText("Display name")).toBeEnabled();
	},
};

/** WCAG 2.2 SC 1.4.10 at 320 px: a `fixed` popup that outgrows the viewport hangs off both ends. */
export const MobileReflow: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 375, 768] },
	},
	play: async () => {
		await screen.findByRole("button", { name: "Add provider" });
		await expectDialogFitsViewport();
	},
};

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: "Add provider" }));
		await expectSettledVisible(await screen.findByText(/enter a display name/iu));
	},
};
