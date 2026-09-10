import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it } from "vitest";

import type { WorkspaceLlmConnection, WorkspaceLlmModel } from "@/api/types.gen";
import { server } from "@/mocks/server";

import { WorkspaceLlmProviderPanel } from "./WorkspaceLlmProviderPanel";

const connections: WorkspaceLlmConnection[] = [
	{
		id: 1,
		slug: "openai",
		displayName: "OpenAI production",
		authMode: "BEARER",
		apiProtocol: "openai-responses",
		baseUrl: "https://api.openai.com/v1",
		enabled: true,
		hasApiKey: true,
		apiKeyLast4: "1111",
		createdAt: new Date("2026-07-01T00:00:00Z"),
	},
	{
		id: 2,
		slug: "gpu",
		displayName: "Local GPU",
		authMode: "BEARER",
		apiProtocol: "openai-completions",
		baseUrl: "https://llm.example.test/v1",
		enabled: false,
		hasApiKey: false,
		createdAt: new Date("2026-07-01T00:00:00Z"),
	},
];

function model(id: number, connectionId: number, displayName: string): WorkspaceLlmModel {
	return {
		processingLocation: "UNCLASSIFIED",
		id,
		connectionId,
		connectionDisplayName:
			connections.find((connection) => connection.id === connectionId)?.displayName ?? "",
		slug: `model-${id}`,
		displayName,
		upstreamModelId: `upstream-${id}`,
		enabled: true,
		supportsReasoning: false,
		pricingMode: "UNPRICED",
		currency: "USD",
		createdAt: new Date("2026-07-01T00:00:00Z"),
	};
}

describe("WorkspaceLlmProviderPanel", () => {
	function renderPanel(ownProviderAllowed = true) {
		const queryClient = new QueryClient({
			defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
		});
		return render(
			<QueryClientProvider client={queryClient}>
				<WorkspaceLlmProviderPanel workspaceSlug="demo" ownProviderAllowed={ownProviderAllowed} />
			</QueryClientProvider>,
		);
	}

	it("renders every workspace connection and groups each model under its owner", async () => {
		server.use(
			http.get("*/workspaces/demo/llm/connections", () => HttpResponse.json(connections)),
			http.get("*/workspaces/demo/llm/models", () =>
				HttpResponse.json([model(10, 1, "GPT shared endpoint"), model(20, 2, "GPU coder")]),
			),
		);
		renderPanel();

		await screen.findByText("OpenAI production");
		screen.getByText("Local GPU");
		await screen.findByText("GPT shared endpoint");
		screen.getByText("GPU coder");
	});

	it("keeps each provider's probe pending independently when two run at once", async () => {
		// A single "which row is probing" flag would be cleared by the fast one and put the slow row
		// back to idle mid-flight.
		let releaseSlowProbe: (() => void) | undefined;
		const slowProbe = new Promise<void>((resolve) => {
			releaseSlowProbe = resolve;
		});
		server.use(
			http.get("*/workspaces/demo/llm/connections", () => HttpResponse.json(connections)),
			http.get("*/workspaces/demo/llm/models", () => HttpResponse.json([])),
			http.post("*/workspaces/demo/llm/connections/1/probe", async () => {
				await slowProbe;
				return HttpResponse.json({ reachable: true, modelCount: 3 });
			}),
			http.post("*/workspaces/demo/llm/connections/2/probe", () =>
				HttpResponse.json({ reachable: true, modelCount: 1 }),
			),
		);
		renderPanel();

		const openAiCard = await screen.findByRole("region", { name: "OpenAI production" });
		const gpuCard = screen.getByRole("region", { name: "Local GPU" });

		fireEvent.click(
			within(openAiCard).getByRole("button", { name: "Test connection to OpenAI production" }),
		);
		fireEvent.click(within(gpuCard).getByRole("button", { name: "Test connection to Local GPU" }));

		await within(gpuCard).findByText(/1 model available/);

		const slowButton = within(openAiCard).getByRole<HTMLButtonElement>("button", {
			name: "Testing… OpenAI production",
		});
		expect(slowButton.disabled).toBe(true);

		releaseSlowProbe?.();
		await within(openAiCard).findByText(/3 models available/);
	});

	it("keeps each model's delete pending independently when two run at once", async () => {
		// A single "which model is mutating" id would be cleared by the fast one, re-enabling the slow
		// row's Delete mid-flight: a second DELETE, and a failure toast for a model that was deleted.
		let releaseSlowDelete: (() => void) | undefined;
		const slowDelete = new Promise<void>((resolve) => {
			releaseSlowDelete = resolve;
		});
		let slowDeleteCalls = 0;
		server.use(
			http.get("*/workspaces/demo/llm/connections", () => HttpResponse.json([connections[0]])),
			http.get("*/workspaces/demo/llm/models", () =>
				HttpResponse.json([model(10, 1, "Slow model"), model(20, 1, "Fast model")]),
			),
			http.delete("*/workspaces/demo/llm/models/10", async () => {
				slowDeleteCalls += 1;
				await slowDelete;
				return new HttpResponse(null, { status: 204 });
			}),
			http.delete("*/workspaces/demo/llm/models/20", () => new HttpResponse(null, { status: 204 })),
		);
		renderPanel();

		const confirmDelete = async (name: string) => {
			fireEvent.click(await screen.findByRole("button", { name: `Delete ${name}` }));
			const dialog = await screen.findByRole("alertdialog");
			fireEvent.click(within(dialog).getByRole("button", { name: "Delete" }));
		};

		await confirmDelete("Slow model");
		await waitFor(() => expect(slowDeleteCalls).toBe(1));
		await confirmDelete("Fast model");
		await waitFor(() => expect(screen.queryByText("Fast model")).not.toBeNull());

		const slowRowDelete = screen.getByRole<HTMLButtonElement>("button", {
			name: "Delete Slow model",
		});
		expect(slowRowDelete.disabled).toBe(true);

		releaseSlowDelete?.();
		await waitFor(() => expect(slowDeleteCalls).toBe(1));
	});

	it("confirms before irreversibly disconnecting a provider", async () => {
		let deleted = false;
		server.use(
			http.get("*/workspaces/demo/llm/connections", () => HttpResponse.json([connections[0]])),
			http.get("*/workspaces/demo/llm/models", () => HttpResponse.json([])),
			http.delete("*/workspaces/demo/llm/connections/1", () => {
				deleted = true;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderPanel();
		fireEvent.click(await screen.findByRole("button", { name: "Disconnect OpenAI production" }));
		expect(deleted).toBe(false);
		const dialog = screen.getByRole("alertdialog");
		fireEvent.click(within(dialog).getByRole("button", { name: "Disconnect provider" }));
		await waitFor(() => expect(deleted).toBe(true));
	});

	it("explains the instance policy and hides registration when own-provider connections are disabled", async () => {
		server.use(http.get("*/workspaces/demo/llm/connections", () => HttpResponse.json([])));
		renderPanel(false);
		await screen.findByText("New workspace providers and models are disabled");
		expect(screen.queryByRole("button", { name: "Connect provider" })).toBeNull();
	});

	it("does not present a failed model request as an empty catalog", async () => {
		server.use(
			http.get("*/workspaces/demo/llm/connections", () => HttpResponse.json([connections[0]])),
			http.get("*/workspaces/demo/llm/models", () =>
				HttpResponse.json({ title: "Unavailable" }, { status: 503 }),
			),
		);
		renderPanel();

		await screen.findByText("Could not load your provider models");
		expect(screen.queryByText("No models yet")).toBeNull();
	});

	it("hides new providers and models while keeping existing entries manageable", async () => {
		server.use(
			http.get("*/workspaces/demo/llm/connections", () => HttpResponse.json([connections[0]])),
			http.get("*/workspaces/demo/llm/models", () =>
				HttpResponse.json([model(10, 1, "Existing model")]),
			),
		);
		renderPanel(false);

		await screen.findByText("Existing model");
		expect(screen.queryByRole("button", { name: "Add provider" })).toBeNull();
		expect(screen.queryByRole("button", { name: "Add model" })).toBeNull();
		screen.getByRole("button", { name: "Edit OpenAI production" });
		screen.getByRole("button", { name: "Edit Existing model" });
		screen.getByRole("button", { name: "Delete Existing model" });
	});
});
