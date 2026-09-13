import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { LlmModel } from "@/api/types.gen";
import { expectSettledVisible } from "@/test/overlay";
import {
	expectControlOnScreen,
	expectDialogBodyScrolls,
	expectDialogFitsViewport,
} from "@/test/reflow";

import {
	AdminLlmModelFormDialog,
	type AdminLlmModelFormDialogProps,
} from "./AdminLlmModelFormDialog";
import type { WorkspaceOption } from "./workspace-options";

const mockModel: LlmModel = {
	dataHandlingTier: "PROVIDER_NOT_KEPT",
	operatedBy: "PROVIDER",
	keptAfterReply: "NONE",
	dataHandlingNote: "EU region, zero-retention agreement renews 2027-01",
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

/** A model from before data handling could be declared: null facts, still saveable. */
const legacyModel: LlmModel = {
	...mockModel,
	id: 2,
	slug: "gpt-4-legacy",
	displayName: "GPT-4 (legacy)",
	dataHandlingTier: "UNDECLARED",
	operatedBy: undefined,
	keptAfterReply: undefined,
	dataHandlingNote: undefined,
};

const mockWorkspaces: WorkspaceOption[] = [
	{ id: 1, displayName: "Example Workspace", workspaceSlug: "example-workspace" },
	{ id: 2, displayName: "Acme Corp", workspaceSlug: "acme" },
];

/**
 * The admin declares two facts and the form derives the tier developers will see, previewed live
 * with the same badge and guarantee rows the developer's page renders. Rejected alternatives: a
 * tier picker (an admin would pick the label that sounds best rather than the facts the agreement
 * states) and inferring the tier from the connection's hostname (a gateway can front anything).
 * "Not declared" stays saveable so an instance upgraded from before the declaration keeps serving
 * members who have not chosen; it is a warning, never a block.
 */
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
		onSave: fn<AdminLlmModelFormDialogProps["onSave"]>(),
	},
} satisfies Meta<typeof AdminLlmModelFormDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

async function fillIdentity(dialog: HTMLElement) {
	await userEvent.type(within(dialog).getByLabelText("Display name"), "GPT-5");
	await userEvent.type(within(dialog).getByLabelText("Upstream model id"), "gpt-5");
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
		await userEvent.click(within(dialog).getByRole("button", { name: /^add model$/i }));
		await expectSettledVisible(
			await within(dialog).findByText("Declare both facts or leave data handling undeclared."),
		);
		await expect(args.onSave).not.toHaveBeenCalled();

		await userEvent.click(within(dialog).getByRole("radio", { name: "Nothing" }));
		await userEvent.click(within(dialog).getByRole("button", { name: /^add model$/i }));
		await expectSettledVisible(
			await within(dialog).findByText(
				"Confirm the training guarantee, or leave data handling undeclared.",
			),
		);
		await expect(args.onSave).not.toHaveBeenCalled();
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

		await userEvent.click(within(dialog).getByRole("button", { name: /^add model$/i }));
		await expect(args.onSave).toHaveBeenCalledOnce();
		await expect(args.onSave.mock.calls[0]?.[0].metadata).toMatchObject({
			operatedBy: "PROVIDER",
			keptAfterReply: "NONE",
		});
	},
};

export const EditModel: Story = {
	args: { editing: mockModel },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expect(within(dialog).getByRole("radio", { name: "A provider" })).toBeChecked();
		await expect(within(dialog).getByRole("radio", { name: "Nothing" })).toBeChecked();
		await expect(within(dialog).getByRole("checkbox", { name: /rule out training/ })).toBeChecked();
		await expectSettledVisible(within(dialog).getByText("Provider, nothing kept"));
	},
};

/**
 * A declared row holds only its exact tier, so re-declaring a model drops it out of every row that
 * held it. The form cannot see bindings, so the warning follows the tier change, not a binding.
 */
export const RedeclareLeavesRows: Story = {
	args: { editing: mockModel },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expect(within(dialog).queryByText(/stop serving/)).not.toBeInTheDocument();

		await userEvent.click(within(dialog).getByRole("radio", { name: "Your organisation" }));
		await expectSettledVisible(
			within(dialog).getByText("Rows holding this model as Provider, nothing kept stop serving"),
		);

		await userEvent.click(within(dialog).getByRole("radio", { name: "A provider" }));
		await expect(within(dialog).queryByText(/stop serving/)).not.toBeInTheDocument();
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
		// The undeclared row takes any model, so declaring one leaves no row behind.
		await userEvent.click(within(dialog).getByRole("radio", { name: "Your organisation" }));
		await expect(within(dialog).queryByText(/stop serving/)).not.toBeInTheDocument();
		await userEvent.click(within(dialog).getByRole("button", { name: "Leave undeclared" }));
		await userEvent.click(within(dialog).getByRole("button", { name: /save changes/i }));
		await expect(args.onSave).toHaveBeenCalledOnce();
	},
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

/** Token limits are an exception to tune, not a step to complete, so they wait behind a disclosure. */
export const AdvancedDisclosure: Story = {
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expect(within(dialog).queryByLabelText(/^Context window/)).not.toBeInTheDocument();

		await userEvent.click(
			within(dialog).getByRole("button", { name: "Advanced: limits and capabilities" }),
		);
		const contextWindow = await within(dialog).findByLabelText(/^Context window/);
		await fillIdentity(dialog);
		await userEvent.type(contextWindow, "3000000000");
		await userEvent.click(
			within(dialog).getByRole("button", { name: "Advanced: limits and capabilities" }),
		);
		await userEvent.click(within(dialog).getByRole("button", { name: /^add model$/i }));

		// The invalid field cannot hide: the disclosure reopens on the error it holds.
		const reopened = await within(dialog).findByLabelText(/^Context window/);
		await expect(reopened).toHaveAttribute("aria-invalid", "true");
		await expectSettledVisible(await within(dialog).findByText(/tokens or fewer/));
	},
};

/**
 * At the WCAG 2.2 SC 1.4.10 reflow width (320 px). Proves `DialogBody`'s bound: only the body
 * scrolls, so the title stays pinned and "Add model" reachable, and the fact cards stack.
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

export const Dark: Story = {
	args: { editing: legacyModel },
	globals: { theme: "dark" },
	play: async () => {
		const dialog = await screen.findByRole("dialog");
		await expectSettledVisible(within(dialog).getByText("Not declared"));
	},
};
