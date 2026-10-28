import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { WorkspaceLlmModel } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DATA_HANDLING_DEFS } from "@/components/practice-vocabulary/data-handling-defs";
import { expectSettledVisible } from "@/stories/overlay";
import { expectDialogFitsViewport } from "@/stories/reflow";

import {
	WorkspaceLlmModelFormDialog,
	type WorkspaceLlmModelFormDialogProps,
} from "./WorkspaceLlmModelFormDialog";

const mockModel: WorkspaceLlmModel = {
	dataHandlingTier: "IN_HOUSE",
	operatedBy: "OWN_ORGANISATION",
	dataHandlingNote: "Runs in the Garching data centre",
	id: 1,
	slug: "gpt-5-mini",
	displayName: "GPT-5 mini",
	upstreamModelId: "openai/gpt-5-mini",
	connectionId: 1,
	connectionDisplayName: "My OpenAI account",
	enabled: true,
	contextWindow: 128_000,
	maxOutputTokens: 16_000,
	pricingMode: "PRICED",
	per1mInputUsd: 0.25,
	per1mOutputUsd: 2,
	currency: "USD",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

/** A model from before data handling could be declared: no operator, still saveable. */
const legacyModel: WorkspaceLlmModel = {
	...mockModel,
	id: 2,
	slug: "legacy",
	displayName: "Legacy model",
	dataHandlingTier: "UNDECLARED",
	operatedBy: undefined,
	dataHandlingNote: undefined,
};

/**
 * The same fields as the instance catalog's dialog, with the price inline: the workspace scope has
 * no separate price endpoint. The data-handling declaration is scope-neutral on purpose, because a
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
	await userEvent.type(within(dialog).getByLabelText("Upstream model ID"), "openai/gpt-5-mini");
}

export const Default: Story = {};

export const AddModel: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		within(dialog).getByRole("radiogroup", { name: "Operated by" });
		await expectSettledVisible(within(dialog).getByText("Not declared"));

		await fillIdentity(dialog);
		await userEvent.click(within(dialog).getByRole("radio", { name: "A provider" }));
		await userEvent.click(within(dialog).getByRole("button", { name: /add inactive model/iu }));
		await expect(args.onCreate).toHaveBeenCalledOnce();
	},
};

export const DeclaredPreview: Story = {
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		await fillIdentity(dialog);
		await userEvent.click(within(dialog).getByRole("radio", { name: "A provider" }));

		await expectSettledVisible(within(dialog).getByText("Cloud"));
		// The preview's guarantee rows are the only definition list in the form.
		const rows = within(dialog).getAllByRole("term");
		await expect(rows.map((row) => row.textContent)).toStrictEqual(
			DATA_HANDLING_DEFS.CLOUD.facts.map((fact) => fact.term),
		);

		await userEvent.click(within(dialog).getByRole("button", { name: /add inactive model/iu }));
		await expect(args.onCreate).toHaveBeenCalledOnce();
		await expect(args.onCreate.mock.calls[0]?.[0]).toMatchObject({ operatedBy: "PROVIDER" });
	},
};

export const EditModel: Story = {
	args: { editing: mockModel },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expect(within(dialog).getByRole("radio", { name: "Your organization" })).toBeChecked();
		await expectSettledVisible(within(dialog).getByText("In-house"));
	},
};

/** The save says so in its own button, and Cancel still closes the form while it runs. */
export const Submitting: Story = {
	args: { editing: mockModel, isSubmitting: true },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(dialog);
		await expect(within(dialog).getByRole("button", { name: "Saving…" })).toBeDisabled();
		await expect(within(dialog).getByRole("button", { name: "Cancel" })).toBeEnabled();
	},
};

export const EditLegacyUndeclared: Story = {
	args: { editing: legacyModel },
	play: async ({ args }) => {
		const dialog = await screen.findByRole("dialog");
		for (const name of ["Your organization", "A provider"]) {
			await expect(within(dialog).getByRole("radio", { name })).not.toBeChecked();
		}
		await expectSettledVisible(within(dialog).getByText("Not declared"));
		await userEvent.click(within(dialog).getByRole("button", { name: /save changes/iu }));
		await expect(args.onUpdate).toHaveBeenCalledOnce();
	},
};

/** The models failed to refresh while the form was open: the warning is in the form, and the draft stays editable. */
export const RefreshFailed: Story = {
	args: {
		editing: mockModel,
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
		await userEvent.type(name, "GPT-5 mini draft");
		await expect(name).toHaveValue("GPT-5 mini draft");
	},
};

export const RefreshRetrying: Story = {
	args: {
		editing: mockModel,
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
		await expectSettledVisible(await screen.findByText(/enter a display name/iu));
		await expectSettledVisible(await screen.findByText(/enter the upstream model ID/iu));
	},
};

export const Dark: Story = {
	args: { editing: mockModel },
	globals: { theme: "dark" },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(within(dialog).getByText("In-house"));
	},
};
