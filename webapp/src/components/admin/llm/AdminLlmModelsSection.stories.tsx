import type { Meta, StoryObj } from "@storybook/react";
import { fn, screen, userEvent, within } from "storybook/test";

import type { LlmModel } from "@/api/types.gen";

import { AdminLlmModelsSection } from "./AdminLlmModelsSection";

const mockModels: LlmModel[] = [
	{
		processingLocation: "UNCLASSIFIED",
		id: 1,
		slug: "gpt-5-eu",
		displayName: "GPT-5",
		upstreamModelId: "gpt-5",
		connectionId: 1,
		connectionDisplayName: "OpenAI production",
		enabled: true,
		supportsReasoning: true,
		visibility: "PUBLIC",
		grantedWorkspaceIds: [],
		currentPrice: {
			id: 1,
			pricingMode: "PRICED",
			per1mInputUsd: 3,
			per1mOutputUsd: 15,
			currency: "USD",
			effectiveFrom: new Date("2026-05-01T00:00:00Z"),
		},
		createdAt: new Date("2026-05-01T10:00:00Z"),
	},
	{
		processingLocation: "UNCLASSIFIED",
		id: 2,
		slug: "local-llama",
		displayName: "Local Llama (self-hosted)",
		upstreamModelId: "meta/llama-3-70b",
		connectionId: 1,
		connectionDisplayName: "OpenAI production",
		enabled: true,
		supportsReasoning: false,
		visibility: "GRANTED",
		grantedWorkspaceIds: [10, 11],
		currentPrice: {
			id: 2,
			pricingMode: "NO_CHARGE",
			note: "self-hosted, no cost",
			currency: "USD",
			effectiveFrom: new Date("2026-05-01T00:00:00Z"),
		},
		createdAt: new Date("2026-05-01T10:00:00Z"),
	},
	{
		processingLocation: "UNCLASSIFIED",
		id: 3,
		slug: "unpriced-model",
		displayName: "New model (not priced yet)",
		upstreamModelId: "vendor/new-model",
		connectionId: 1,
		connectionDisplayName: "OpenAI production",
		enabled: false,
		supportsReasoning: false,
		visibility: "PUBLIC",
		grantedWorkspaceIds: [],
		createdAt: new Date("2026-06-01T10:00:00Z"),
	},
];

const meta = {
	component: AdminLlmModelsSection,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		connectionDisplayName: "OpenAI production",
		connectionEnabled: true,
		workspaceOptions: [
			{ id: 10, displayName: "Teaching team", workspaceSlug: "teaching" },
			{ id: 11, displayName: "Research team", workspaceSlug: "research" },
		],
		models: mockModels,
		mutatingIds: new Set<number>(),
		onAdd: fn(),
		onEdit: fn(),
		onManageAccess: fn(),
		onDelete: fn(),
	},
} satisfies Meta<typeof AdminLlmModelsSection>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Empty: Story = {
	args: { models: [] },
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /delete gpt-5/i }));
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/can't be deleted/i);
	},
};
