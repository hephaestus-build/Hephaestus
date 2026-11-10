import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, within } from "storybook/test";

import type { WorkspaceLlmConnection, WorkspaceLlmModel } from "@/api/types.gen";
import { withStandardPage } from "@/stories/decorators";

import { WorkspaceLlmProviderPanel } from "./WorkspaceLlmProviderPanel";

const openAi: WorkspaceLlmConnection = {
	id: 1,
	slug: "my-openai",
	displayName: "My OpenAI account",
	authMode: "BEARER",
	apiProtocol: "openai-responses",
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
	baseUrl: "https://api.openai.com/v1",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "ab12",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

const onPrem: WorkspaceLlmConnection = {
	...openAi,
	id: 2,
	slug: "on-prem-vllm",
	displayName: "On-prem vLLM",
	apiProtocol: "openai-completions",
	baseUrl: "https://vllm.example.test/v1",
	hasApiKey: false,
	apiKeyLast4: undefined,
};

const gpt: WorkspaceLlmModel = {
	id: 10,
	connectionId: openAi.id,
	connectionDisplayName: openAi.displayName,
	slug: "gpt-5-mini",
	displayName: "GPT-5 mini",
	upstreamModelId: "openai/gpt-5-mini",
	brand: "OPENAI",
	dataHandlingTier: "CLOUD",
	operatedBy: "PROVIDER",
	enabled: true,
	pricingMode: "PRICED",
	per1mInputUsd: 0.25,
	per1mOutputUsd: 2,
	currency: "USD",
	createdAt: new Date("2026-06-01T10:00:00Z"),
};

function columnLeft(region: HTMLElement, column: string): number {
	return within(region).getByRole("columnheader", { name: column }).getBoundingClientRect().left;
}

const meta = {
	component: WorkspaceLlmProviderPanel,
	parameters: { layout: "fullscreen" },
	decorators: [withStandardPage],
	tags: ["autodocs"],
	args: {
		state: { status: "ready", connections: [openAi, onPrem], models: [gpt] },
		registrationAllowed: true,
		testResults: new Map(),
		testingConnectionIds: new Set(),
		writingConnectionIds: new Set(),
		writingModelIds: new Set(),
		onAddConnection: fn(),
		onEditConnection: fn(),
		onTestConnection: fn(),
		onDisconnect: fn(),
		onAddModel: fn(),
		onEditModel: fn(),
		onDeleteModel: fn(),
	},
} satisfies Meta<typeof WorkspaceLlmProviderPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Your providers" })).toBeVisible();
		const chat = within(canvas.getByRole("region", { name: "My OpenAI account" }));
		await expect(chat.getByText("Responses API")).toBeVisible();
		await expect(chat.getByText("Key ends in ab12")).toBeVisible();
		await expect(chat.getByRole("table", { name: "Models on My OpenAI account" })).toBeVisible();
		const onPremProvider = within(canvas.getByRole("region", { name: "On-prem vLLM" }));
		await expect(onPremProvider.getByText("Chat Completions API")).toBeVisible();
		await expect(onPremProvider.getByText(", for Practice reviews and Conversations")).toHaveClass(
			"sr-only",
		);
		await expect(
			onPremProvider.getByRole("cell", {
				name: "No models yet. Add a model to use this provider.",
			}),
		).toBeVisible();
		await expect(
			onPremProvider.getByRole("button", { name: "Add model to On-prem vLLM" }),
		).toBeVisible();
		const keeps = chat.getByRole("button", { name: "Disconnect My OpenAI account" });
		await expect(keeps).toBeDisabled();
		await expect(keeps).toHaveAccessibleDescription("To disconnect, delete its models first.");
		await expect(chat.getByText("To disconnect, delete its models first.")).toBeVisible();
		await expect(
			onPremProvider.getByRole("button", { name: "Disconnect On-prem vLLM" }),
		).toBeEnabled();
		await expect(onPremProvider.queryByText("To disconnect, delete its models first.")).toBeNull();
		for (const column of ["Data handling", "Price", "Status", "Actions"]) {
			await expect(columnLeft(canvas.getByRole("region", { name: "On-prem vLLM" }), column)).toBe(
				columnLeft(canvas.getByRole("region", { name: "My OpenAI account" }), column),
			);
		}
	},
};

export const ProviderOff: Story = {
	args: {
		state: {
			status: "ready",
			connections: [{ ...openAi, enabled: false }, onPrem],
			models: [gpt],
		},
	},
	play: async ({ canvas }) => {
		const off = within(canvas.getByRole("region", { name: "My OpenAI account" }));
		await expect(off.getByText("Off")).toBeVisible();
		await expect(off.getByRole("cell", { name: "Connection off" })).toBeVisible();
		await expect(off.queryByText("Ready")).toBeNull();
		const on = within(canvas.getByRole("region", { name: "On-prem vLLM" }));
		await expect(on.queryByText("Off")).toBeNull();
	},
};

export const Empty: Story = {
	args: { state: { status: "ready", connections: [], models: [] } },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("heading", { name: "Your providers" })).toBeVisible();
		await expect(canvas.getByText("Connect your own provider")).toBeVisible();
		await expect(canvas.getAllByRole("button", { name: "Add provider" })).toHaveLength(1);
	},
};

export const Loading: Story = {
	args: { state: { status: "loading" } },
	play: async ({ canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(canvas.queryByRole("status")).toBeNull();
	},
};

export const LoadFailed: Story = {
	args: {
		state: { status: "error", error: { status: 503, title: "Unavailable" }, onRetry: fn() },
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not load your AI providers")).toBeVisible();
		await expect(canvas.queryByText("Connect your own provider")).toBeNull();
	},
};

export const RegistrationBlocked: Story = {
	args: { registrationAllowed: false },
	play: async ({ canvas }) => {
		await expect(canvas.getByText("New workspace providers and models are disabled")).toBeVisible();
		await expect(canvas.queryByRole("button", { name: "Add provider" })).toBeNull();
		await expect(canvas.queryByRole("button", { name: /^Add model/u })).toBeNull();
		await expect(canvas.getByRole("button", { name: "Edit My OpenAI account" })).toBeEnabled();
	},
};

export const TestResults: Story = {
	args: {
		testResults: new Map([
			[openAi.id, { status: "answered", result: { reachable: true, modelCount: 12 } }],
			[
				onPrem.id,
				{
					status: "answered",
					result: { reachable: false, modelCount: 0, statusCode: 404, message: "HTTP 404" },
				},
			],
		]),
	},
	play: async ({ canvas }) => {
		const chat = within(canvas.getByRole("region", { name: "My OpenAI account" }));
		await expect(chat.getByRole("status")).toHaveTextContent("Connected. 12 models available.");
		const onPremProvider = within(canvas.getByRole("region", { name: "On-prem vLLM" }));
		await expect(onPremProvider.getByRole("alert")).toHaveTextContent("HTTP 404");
		await expect(onPremProvider.queryByRole("status")).toBeNull();
	},
};

export const Testing: Story = {
	args: { testingConnectionIds: new Set([openAi.id]) },
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("button", { name: "Testing… My OpenAI account" })).toBeDisabled();
		await expect(
			canvas.getByRole("button", { name: "Test connection to On-prem vLLM" }),
		).toBeEnabled();
	},
};

export const RefreshFailed: Story = {
	args: {
		state: {
			status: "ready",
			connections: [openAi],
			models: [gpt],
			refreshFailure: { error: { status: 503 }, pending: false, onRetry: fn() },
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText("We could not refresh your AI providers")).toBeVisible();
		await expect(canvas.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(canvas.getByRole("table", { name: "Models on My OpenAI account" })).toBeVisible();
	},
};
