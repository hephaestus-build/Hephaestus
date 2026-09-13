import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { WorkspaceLlmModel } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";
import { expectDialogFitsViewport } from "@/test/reflow";

import {
	WorkspaceLlmModelFormDialog,
	type WorkspaceLlmModelFormDialogProps,
} from "./WorkspaceLlmModelFormDialog";

const mockModel: WorkspaceLlmModel = {
	dataHandlingTier: "IN_HOUSE",
	operatedBy: "OWN_ORGANISATION",
	keptAfterReply: "NONE",
	dataHandlingNote: "Runs in the Garching data centre",
	id: 1,
	slug: "gpt-5-mini",
	displayName: "GPT-5 mini",
	upstreamModelId: "openai/gpt-5-mini",
	connectionId: 1,
	connectionDisplayName: "My OpenAI account",
	enabled: true,
	supportsReasoning: true,
	contextWindow: 128_000,
	maxOutputTokens: 16_000,
	pricingMode: "PRICED",
	per1mInputUsd: 0.25,
	per1mOutputUsd: 2,
	currency: "USD",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

/** A model from before data handling could be declared: null facts, still saveable. */
const legacyModel: WorkspaceLlmModel = {
	...mockModel,
	id: 2,
	slug: "legacy",
	displayName: "Legacy model",
	dataHandlingTier: "UNDECLARED",
	operatedBy: undefined,
	keptAfterReply: undefined,
	dataHandlingNote: undefined,
};

/**
 * The same fields as the instance catalog's dialog, with the price inline: the workspace scope has
 * no separate price endpoint. The data-handling declaration is scope-neutral on purpose — a
 * workspace admin's own provider makes the same promise to the same developers.
 */
const meta = {
	component: WorkspaceLlmModelFormDialog,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: {
		open: true,
		onOpenChange: fn(),
		editing: null,
		isSubmitting: false,
		onCreate: fn<WorkspaceLlmModelFormDialogProps["onCreate"]>(),
		onUpdate: fn(),
	},
} satisfies Meta<typeof WorkspaceLlmModelFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

async function fillIdentity(dialog: HTMLElement) {
	await userEvent.type(within(dialog).getByLabelText("Display name"), "GPT-5 mini");
	await userEvent.type(within(dialog).getByLabelText("Upstream model id"), "openai/gpt-5-mini");
}

export const Default: Story = {};

export const AddModel: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		within(dialog).getByRole("radiogroup", { name: "Operated by" });
		within(dialog).getByRole("radiogroup", { name: "Kept after the reply" });
		await expectSettledVisible(within(dialog).getByText("Not declared"));

		await fillIdentity(dialog);
		await userEvent.click(within(dialog).getByRole("radio", { name: "A provider" }));
		await userEvent.click(within(dialog).getByRole("button", { name: /add inactive model/i }));
		await expectSettledVisible(
			await within(dialog).findByText("Declare both facts or leave data handling undeclared."),
		);
		await expect(args.onCreate).not.toHaveBeenCalled();

		await userEvent.click(within(dialog).getByRole("radio", { name: "Nothing" }));
		await userEvent.click(within(dialog).getByRole("button", { name: /add inactive model/i }));
		await expectSettledVisible(
			await within(dialog).findByText(
				"Confirm the training guarantee, or leave data handling undeclared.",
			),
		);
		await expect(args.onCreate).not.toHaveBeenCalled();
	},
};

export const DeclaredPreview: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		await fillIdentity(dialog);
		await userEvent.click(within(dialog).getByRole("radio", { name: "A provider" }));
		await userEvent.click(within(dialog).getByRole("radio", { name: "Nothing" }));
		await userEvent.click(within(dialog).getByRole("checkbox", { name: /rule out training/ }));

		await expectSettledVisible(within(dialog).getByText("Provider, nothing kept"));
		// The preview's guarantee rows are the only definition list in the form.
		const rows = within(dialog).getAllByRole("term");
		await expect(rows.map((row) => row.textContent)).toStrictEqual([
			"Operated by",
			"Kept after the reply",
			"Training",
		]);

		await userEvent.click(within(dialog).getByRole("button", { name: /add inactive model/i }));
		await expect(args.onCreate).toHaveBeenCalledOnce();
		await expect(args.onCreate.mock.calls[0]?.[0]).toMatchObject({
			operatedBy: "PROVIDER",
			keptAfterReply: "NONE",
		});
	},
};

export const EditModel: Story = {
	args: { editing: mockModel },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expect(within(dialog).getByRole("radio", { name: "Your organisation" })).toBeChecked();
		await expectSettledVisible(within(dialog).getByText("Stays in-house"));
	},
};

export const EditLegacyUndeclared: Story = {
	args: { editing: legacyModel },
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		for (const name of ["Your organisation", "A provider", "Nothing", "For safety checks"]) {
			await expect(within(dialog).getByRole("radio", { name })).not.toBeChecked();
		}
		await expectSettledVisible(within(dialog).getByText("Not declared"));
		await userEvent.click(within(dialog).getByRole("button", { name: /save changes/i }));
		await expect(args.onUpdate).toHaveBeenCalledOnce();
	},
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
		await screen.findByRole("button", { name: /save changes/i });
		await expectDialogFitsViewport();
	},
};

export const ValidationError: Story = {
	play: async () => {
		await userEvent.click(await screen.findByRole("button", { name: /add inactive model/i }));
		await expectSettledVisible(await screen.findByText(/display name is required/i));
		await expectSettledVisible(await screen.findByText(/upstream model id is required/i));
	},
};

export const Dark: Story = {
	args: { editing: mockModel },
	globals: { theme: "dark" },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(within(dialog).getByText("Stays in-house"));
	},
};
