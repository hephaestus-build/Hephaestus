import type { Meta, StoryObj } from "@storybook/react";
import { expect, fn, screen, userEvent } from "storybook/test";

import type { LlmConnection } from "@/api/types.gen";

import { AdminLlmConnectionsTable } from "./AdminLlmConnectionsTable";

const mockConnections: LlmConnection[] = [
	{
		id: 1,
		slug: "openai-production",
		displayName: "OpenAI production",
		authMode: "BEARER",
		apiProtocol: "openai-responses",
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
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
		purposes: ["PRACTICE_REVIEW", "MENTOR", "PRACTICE_DECISION"],
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

/**
 * The server keeps a connection that still has models, so its Delete is off, and one line under the
 * table says why.
 */
export const Default: Story = {
	play: async ({ canvas }) => {
		const reason = "To delete a connection, delete its models first.";
		const keeps = canvas.getByRole("button", { name: "Delete OpenAI production" });
		await expect(keeps).toBeDisabled();
		await expect(keeps).toHaveAccessibleDescription(reason);
		await expect(canvas.getByText(reason)).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Delete On-prem GPU (vLLM)" })).toBeEnabled();
	},
};

/** No connection has models: every Delete is on, and no line explains a block. */
export const NothingKeepsModels: Story = {
	args: { modelCounts: {} },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Delete OpenAI production" })).toBeEnabled();
		await expect(canvas.queryByText(/delete its models first/u)).toBeNull();
	},
};

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
		await userEvent.click(canvas.getByRole("button", { name: "Delete On-prem GPU (vLLM)" }));
		const dialog = await screen.findByRole("alertdialog");
		await expect(dialog).toHaveTextContent("You cannot undo this.");
		await expect(dialog).not.toHaveTextContent(/models/u);
	},
};
