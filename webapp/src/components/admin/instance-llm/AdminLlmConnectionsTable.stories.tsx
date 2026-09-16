import type { Meta, StoryObj } from "@storybook/react";
import { fn, screen, userEvent, within } from "storybook/test";

import type { LlmConnection } from "@/api/types.gen";

import { AdminLlmConnectionsTable } from "./AdminLlmConnectionsTable";

const mockConnections: LlmConnection[] = [
	{
		id: 1,
		slug: "openai-production",
		displayName: "OpenAI production",
		authMode: "BEARER",
		apiProtocol: "openai-responses",
		baseUrl: "https://openai-production.example.com/openai",
		enabled: true,
		hasApiKey: true,
		apiKeyLast4: "ab12",
		createdAt: new Date("2026-05-01T10:00:00Z"),
	},
	{
		id: 2,
		slug: "on-prem-gpu",
		displayName: "On-prem GPU (vLLM)",
		authMode: "BEARER",
		apiProtocol: "openai-completions",
		baseUrl: "https://gpu.internal.example.com/v1",
		enabled: false,
		hasApiKey: false,
		createdAt: new Date("2026-05-10T10:00:00Z"),
	},
];

const meta = {
	component: AdminLlmConnectionsTable,
	parameters: { layout: "padded" },
	tags: ["autodocs"],
	args: {
		connections: mockConnections,
		modelCounts: { 1: 3, 2: 0 },
		isLoading: false,
		isError: false,
		mutatingIds: new Set<number>(),
		selectedId: null,
		onSelect: fn(),
		onEdit: fn(),
		onToggleEnabled: fn(),
		onDelete: fn(),
		onAdd: fn(),
	},
} satisfies Meta<typeof AdminLlmConnectionsTable>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const SelectedRow: Story = {
	args: { selectedId: 1 },
};

export const Loading: Story = {
	args: { isLoading: true },
};

export const ErrorState: Story = {
	args: { isError: true, error: new Error("Network error") },
};

export const Empty: Story = {
	args: { connections: [], modelCounts: {} },
};

export const DeleteConfirm: Story = {
	play: async ({ canvas }) => {
		await userEvent.click(canvas.getByRole("button", { name: /delete openai production/iu }));
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/still on it/iu);
	},
};
