import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import type { WorkspaceLlmModel } from "@/api/types.gen";

import { WorkspaceLlmModelsTable } from "./WorkspaceLlmModelsTable";

async function openDeleteConfirm(canvas: StoryContext["canvas"], name: RegExp) {
	await userEvent.click(canvas.getByRole("button", { name }));
	return screen.findByRole("alertdialog");
}

const base = {
	connectionId: 1,
	connectionDisplayName: "My OpenAI account",
	enabled: true,
	reasoningEffort: "MEDIUM",
	pricingMode: "PRICED",
	per1mInputUsd: 0.25,
	per1mOutputUsd: 2,
	currency: "USD",
	createdAt: new Date("2026-06-01T10:00:00Z"),
} satisfies Partial<WorkspaceLlmModel>;

/** One model per declared tier, then one that predates the declaration. */
const mockModels: WorkspaceLlmModel[] = [
	{
		...base,
		id: 2,
		slug: "local-llama",
		displayName: "Local Llama",
		upstreamModelId: "local/llama-3-70b",
		dataHandlingTier: "IN_HOUSE",
		operatedBy: "OWN_ORGANISATION",
		enabled: false,
		pricingMode: "NO_CHARGE",
		per1mInputUsd: undefined,
		per1mOutputUsd: undefined,
		priceNote: "self-hosted, no cost",
	},
	{
		...base,
		id: 1,
		slug: "gpt-5-mini",
		displayName: "GPT-5 mini",
		upstreamModelId: "openai/gpt-5-mini",
		brand: "OPENAI",
		dataHandlingTier: "CLOUD",
		operatedBy: "PROVIDER",
		dataHandlingNote: "EU region",
	},
	{
		...base,
		id: 4,
		slug: "legacy",
		displayName: "Legacy model",
		upstreamModelId: "legacy/model",
		dataHandlingTier: "UNDECLARED",
	},
];

const meta = {
	component: WorkspaceLlmModelsTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		providerName: "My OpenAI account",
		connectionEnabled: true,
		models: mockModels,
		mutatingIds: new Set<number>(),
		onEdit: fn(),
		onDelete: fn(),
	},
} satisfies Meta<typeof WorkspaceLlmModelsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

/** Each name leads with its maker's mark, as everywhere else a model is named. */
export const Default: Story = {
	play: async ({ canvas }) => {
		const rows = canvas.getAllByRole("row").slice(1);
		const tiers = rows.map((row) => within(row).getAllByRole("cell")[1]?.textContent);
		await expect(tiers).toStrictEqual(["In-house", "Cloud", "Not declared"]);
		const gpt = canvas.getByRole("cell", { name: "GPT-5 mini" });
		await expect(gpt.querySelector("img")).not.toBeNull();
		// A model with no maker declared keeps the tile, with the generic glyph.
		await expect(
			canvas.getByRole("cell", { name: "Local Llama" }).querySelector("svg"),
		).not.toBeNull();
	},
};

/** A provider with no model yet keeps its table's header and says so in one row, with no box inside. */
export const Empty: Story = {
	args: { models: [] },
	play: async ({ canvas }) => {
		const table = canvas.getByRole("table", { name: "Models on My OpenAI account" });
		await expect(
			within(table).getByRole("cell", { name: "No models yet. Add a model to use this provider." }),
		).toBeVisible();
	},
};

/** A model turned off wears the registry's badge; a model that can run says so in plain words. */
export const ModelOff: Story = {
	args: { models: mockModels.map((model, index) => ({ ...model, enabled: index !== 0 })) },
	play: async ({ canvas }) => {
		const rows = canvas.getAllByRole("row").slice(1);
		const status = rows.map((row) => within(row).getAllByRole("cell")[3]?.textContent);
		await expect(status).toStrictEqual(["Off", "Ready", "Ready"]);
	},
};

/** A turned-off provider stops every model on it, so none of them reads as ready. */
export const ConnectionOff: Story = {
	args: { connectionEnabled: false },
	play: async ({ canvas }) => {
		const rows = canvas.getAllByRole("row").slice(1);
		const status = rows.map((row) => within(row).getAllByRole("cell")[3]?.textContent);
		await expect(status).toStrictEqual(["Connection off", "Connection off", "Connection off"]);
	},
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		const dialog = await openDeleteConfirm(canvas, /delete gpt-5 mini/iu);
		within(dialog).getByText(/stop using this model/iu);
	},
};
