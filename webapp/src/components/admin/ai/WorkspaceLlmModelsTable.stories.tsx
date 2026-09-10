import type { Meta, StoryContext, StoryObj } from "@storybook/react";
import { fn, screen, userEvent, within } from "storybook/test";

import type { WorkspaceLlmModel } from "@/api/types.gen";

import { WorkspaceLlmModelsTable } from "./WorkspaceLlmModelsTable";

async function openDeleteConfirm(canvas: StoryContext["canvas"], name: RegExp) {
	await userEvent.click(canvas.getByRole("button", { name }));
	return await screen.findByRole("alertdialog");
}

const mockModels: WorkspaceLlmModel[] = [
	{
		processingLocation: "UNCLASSIFIED",
		id: 1,
		slug: "gpt-5-mini",
		displayName: "GPT-5 mini",
		upstreamModelId: "openai/gpt-5-mini",
		connectionId: 1,
		connectionDisplayName: "My OpenAI account",
		enabled: true,
		supportsReasoning: true,
		pricingMode: "PRICED",
		per1mInputUsd: 0.25,
		currency: "USD",
		createdAt: new Date("2026-06-01T10:00:00Z"),
	},
	{
		processingLocation: "UNCLASSIFIED",
		id: 2,
		slug: "local-llama",
		displayName: "Local Llama",
		upstreamModelId: "local/llama-3-70b",
		connectionId: 1,
		connectionDisplayName: "My OpenAI account",
		enabled: false,
		supportsReasoning: false,
		pricingMode: "NO_CHARGE",
		priceNote: "self-hosted, no cost",
		currency: "USD",
		createdAt: new Date("2026-06-01T10:00:00Z"),
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

export const Default: Story = {};

export const Empty: Story = {
	args: { models: [] },
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		const dialog = await openDeleteConfirm(canvas, /delete gpt-5 mini/i);
		within(dialog).getByText(/stop working/i);
	},
};
