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
	supportsReasoning: true,
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
		supportsReasoning: false,
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
		models: mockModels,
		mutatingIds: new Set<number>(),
		onEdit: fn(),
		onDelete: fn(),
	},
} satisfies Meta<typeof WorkspaceLlmModelsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		const rows = canvas.getAllByRole("row").slice(1);
		const tiers = rows.map((row) => within(row).getAllByRole("cell")[1]?.textContent);
		await expect(tiers).toStrictEqual(["In-house", "Cloud", "Not declared"]);
	},
};

export const Empty: Story = {
	args: { models: [] },
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		const dialog = await openDeleteConfirm(canvas, /delete gpt-5 mini/iu);
		within(dialog).getByText(/stop working/iu);
	},
};
