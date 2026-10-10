import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { WorkspaceLlmConnection, WorkspaceLlmModel } from "@/api/types.gen";

import {
	type ProviderTestResult,
	WorkspaceLlmProviderPanel,
	type WorkspaceLlmProviderPanelProps,
} from "./WorkspaceLlmProviderPanel";

const openAi: WorkspaceLlmConnection = {
	id: 1,
	slug: "openai",
	displayName: "OpenAI production",
	authMode: "BEARER",
	apiProtocol: "openai-responses",
	purposes: ["PRACTICE_REVIEW", "MENTOR"],
	baseUrl: "https://api.openai.com/v1",
	enabled: true,
	hasApiKey: true,
	apiKeyLast4: "1111",
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

const gpu: WorkspaceLlmConnection = {
	id: 2,
	slug: "gpu",
	displayName: "Local GPU",
	authMode: "BEARER",
	apiProtocol: "openai-completions",
	purposes: ["PRACTICE_REVIEW", "MENTOR", "PRACTICE_DECISION"],
	baseUrl: "https://llm.example.test/v1",
	enabled: false,
	hasApiKey: false,
	createdAt: new Date("2026-07-01T00:00:00Z"),
};

const embeddings: WorkspaceLlmConnection = {
	...gpu,
	id: 3,
	slug: "embeddings",
	displayName: "Embeddings",
	apiProtocol: "openai-embeddings",
	purposes: ["PRACTICE_EMBEDDING"],
	enabled: true,
};

function model(id: number, connection: WorkspaceLlmConnection, displayName: string) {
	return {
		dataHandlingTier: "UNDECLARED",
		id,
		connectionId: connection.id,
		connectionDisplayName: connection.displayName,
		slug: `model-${id}`,
		displayName,
		upstreamModelId: `upstream-${id}`,
		enabled: true,
		pricingMode: "UNPRICED",
		currency: "USD",
		createdAt: new Date("2026-07-01T00:00:00Z"),
	} satisfies WorkspaceLlmModel;
}

const notFound = (message: string): ProviderTestResult => ({
	status: "answered",
	result: { reachable: false, modelCount: 0, statusCode: 404, message },
});

function renderPanel(overrides: Partial<WorkspaceLlmProviderPanelProps> = {}) {
	const props: WorkspaceLlmProviderPanelProps = {
		state: { status: "ready", connections: [openAi], models: [] },
		registrationAllowed: true,
		testResults: new Map(),
		testingConnectionIds: new Set(),
		writingConnectionIds: new Set(),
		writingModelIds: new Set(),
		onAddConnection: vi.fn(),
		onEditConnection: vi.fn(),
		onTestConnection: vi.fn(),
		onDisconnect: vi.fn(),
		onAddModel: vi.fn(),
		onEditModel: vi.fn(),
		onDeleteModel: vi.fn(),
		...overrides,
	};
	render(<WorkspaceLlmProviderPanel {...props} />);
	return props;
}

describe("WorkspaceLlmProviderPanel", () => {
	it("renders every workspace connection and groups each model under its owner", () => {
		renderPanel({
			state: {
				status: "ready",
				connections: [openAi, gpu],
				models: [model(10, openAi, "GPT shared endpoint"), model(20, gpu, "GPU coder")],
			},
		});

		within(screen.getByRole("region", { name: "OpenAI production" })).getByText(
			"GPT shared endpoint",
		);
		const gpuCard = within(screen.getByRole("region", { name: "Local GPU" }));
		gpuCard.getByText("GPU coder");
		expect(gpuCard.queryByText("GPT shared endpoint")).toBeNull();
	});

	it("reads a missing model list on a precompute API as no fault, and on a chat API as a failure", () => {
		renderPanel({
			state: { status: "ready", connections: [openAi, embeddings], models: [] },
			testResults: new Map([
				[openAi.id, notFound("HTTP 404")],
				[embeddings.id, notFound("HTTP 404")],
			]),
		});

		const embeddingsCard = within(screen.getByRole("region", { name: "Embeddings" }));
		expect(embeddingsCard.getByRole("status").textContent).toBe(
			"No model list at this address. Add the model by its ID. If its calls fail too, check the base URL.",
		);
		expect(embeddingsCard.queryByRole("alert")).toBeNull();
		const chatCard = within(screen.getByRole("region", { name: "OpenAI production" }));
		expect(chatCard.getByRole("alert").textContent).toBe("HTTP 404");
	});

	it("keeps a failed request in the words it came with, and announces a success politely", () => {
		renderPanel({
			state: { status: "ready", connections: [openAi, embeddings], models: [] },
			testResults: new Map([
				[openAi.id, { status: "answered", result: { reachable: true, modelCount: 3 } }],
				[embeddings.id, { status: "failed", message: "The provider answered with HTTP 401." }],
			]),
		});

		expect(
			within(screen.getByRole("region", { name: "OpenAI production" })).getByRole("status")
				.textContent,
		).toBe("Connected. 3 models available.");
		const embeddingsCard = within(screen.getByRole("region", { name: "Embeddings" }));
		expect(embeddingsCard.getByRole("alert").textContent).toBe(
			"The provider answered with HTTP 401.",
		);
		expect(embeddingsCard.getByRole("status").textContent).toBe("");
	});

	it("confirms before irreversibly disconnecting a provider", () => {
		const { onDisconnect } = renderPanel();
		fireEvent.click(screen.getByRole("button", { name: "Disconnect OpenAI production" }));
		expect(onDisconnect).not.toHaveBeenCalled();
		const dialog = screen.getByRole("alertdialog");
		fireEvent.click(within(dialog).getByRole("button", { name: "Disconnect provider" }));
		expect(onDisconnect).toHaveBeenCalledWith(openAi);
	});
});
