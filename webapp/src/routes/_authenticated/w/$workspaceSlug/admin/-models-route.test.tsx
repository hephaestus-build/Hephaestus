import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import { listAgentsQueryKey } from "@/api/@tanstack/react-query.gen";
import type { AgentBinding } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

const MODELS = [
	{
		id: 20,
		scope: "SHARED",
		displayName: "GPT Test",
		connectionDisplayName: "Shared OpenAI",
		supportsReasoning: false,
		pricingMode: "NO_CHARGE",
	},
	{
		id: 21,
		scope: "SHARED",
		displayName: "GPT Other",
		connectionDisplayName: "Shared OpenAI",
		supportsReasoning: false,
		pricingMode: "NO_CHARGE",
	},
];

const WORKSPACE = {
	id: 1,
	slug: "acme",
	displayName: "Acme",
	practicesEnabled: true,
	mentorEnabled: true,
};

const WORKSPACE_LIST_ITEM = { ...WORKSPACE, workspaceSlug: "acme", accountLogin: "acme" };
const AGENTS_QUERY_KEY = listAgentsQueryKey({ path: { workspaceSlug: "acme" } });

function binding(purpose: AgentBinding["purpose"], instanceModelId: number): AgentBinding {
	return {
		processingLocation: "UNCLASSIFIED",
		purpose,
		instanceModelId,
		enabled: true,
		ready: true,
		timeoutSeconds: 600,
		maxConcurrentJobs: 3,
		allowInternet: false,
	};
}

function mockModelsRoute(bindings: () => AgentBinding[]) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/agents", () => HttpResponse.json(bindings())),
		http.get("*/workspaces/:workspaceSlug/llm/available-models", () => HttpResponse.json(MODELS)),
		http.get("*/workspaces/:workspaceSlug/llm/settings", () =>
			HttpResponse.json({ ownProviderAllowed: false }),
		),
		http.get("*/workspaces/:workspaceSlug/llm/connections", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/llm/usage", () =>
			HttpResponse.json({
				month: "2026-07",
				instanceTotalCostUsd: 0,
				ownProviderTotalCostUsd: 0,
				instanceBudgetVerdict: "WITHIN",
				ownProviderBudgetVerdict: "WITHIN",
				instancePaused: false,
				ownProviderPaused: false,
				unpricedEventCount: 0,
				byJobType: [],
				byDay: [],
			}),
		),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(WORKSPACE)),
		http.get("*/workspaces", () => HttpResponse.json([WORKSPACE_LIST_ITEM])),
	);
}

function deferredBindingsRefetch(bindings: () => AgentBinding[]) {
	let release = () => {};
	const pending = new Promise<void>((resolve) => {
		release = resolve;
	});
	return {
		handler: http.get("*/workspaces/:workspaceSlug/agents", async () => {
			await pending;
			return HttpResponse.json(bindings());
		}),
		release,
	};
}

async function renderModelsRoute(bindings: () => AgentBinding[]) {
	mockModelsRoute(bindings);
	const queryClient = renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("heading", { name: "AI models" }, ROUTE_RENDER_WAIT);
	await screen.findByLabelText("Practice reviews model", undefined, ROUTE_RENDER_WAIT);
	return queryClient;
}

function card(purposeLabel: string): HTMLElement {
	const field = screen.getByLabelText(purposeLabel);
	const cardElement = field.closest("[data-slot='card']");
	if (!(cardElement instanceof HTMLElement)) throw new Error(`No card for ${purposeLabel}`);
	return cardElement;
}

const saveButton = (purposeLabel: string) =>
	within(card(purposeLabel)).getByRole<HTMLButtonElement>("button", { name: "Save assignment" });

describe("workspace AI models route", () => {
	it("keeps each purpose's card pending independently when two saves run at once", async () => {
		let releaseSlowSave: (() => void) | undefined;
		const slowSave = new Promise<void>((resolve) => {
			releaseSlowSave = resolve;
		});
		let detectionSaves = 0;
		server.use(
			http.put("*/workspaces/:workspaceSlug/agents/PRACTICE_REVIEW", async () => {
				detectionSaves += 1;
				await slowSave;
				return HttpResponse.json(binding("PRACTICE_REVIEW", 20));
			}),
			http.put("*/workspaces/:workspaceSlug/agents/MENTOR", () =>
				HttpResponse.json(binding("MENTOR", 20)),
			),
		);

		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20), binding("MENTOR", 20)]);

		fireEvent.click(saveButton("Practice reviews model"));
		await waitFor(() => expect(detectionSaves).toBe(1));
		fireEvent.click(saveButton("Heph model"));

		await waitFor(() => expect(saveButton("Heph model").disabled).toBe(false));
		expect(saveButton("Practice reviews model").disabled).toBe(true);

		releaseSlowSave?.();
		await waitFor(() => expect(saveButton("Practice reviews model").disabled).toBe(false));
		expect(detectionSaves).toBe(1);
	});

	it("keeps unsaved run limits when another admin repoints the same purpose", async () => {
		let bindings = [binding("PRACTICE_REVIEW", 20)];
		const queryClient = await renderModelsRoute(() => bindings);

		const detection = card("Practice reviews model");
		fireEvent.click(within(detection).getByRole("button", { name: "Advanced" }));
		const timeout = within(detection).getByLabelText<HTMLInputElement>("Timeout (seconds)");
		fireEvent.change(timeout, { target: { value: "900" } });
		expect(timeout.value).toBe("900");

		bindings = [{ ...binding("PRACTICE_REVIEW", 21), ready: false }];
		await queryClient.invalidateQueries({
			queryKey: listAgentsQueryKey({ path: { workspaceSlug: "acme" } }),
		});
		await screen.findByText("Not ready");

		expect(
			within(card("Practice reviews model")).getByLabelText<HTMLInputElement>("Timeout (seconds)")
				.value,
		).toBe("900");
	});

	it("reads back what was just saved, not what the card was showing before", async () => {
		let bindings = [binding("PRACTICE_REVIEW", 20)];
		const queryClient = await renderModelsRoute(() => bindings);
		const refetch = deferredBindingsRefetch(() => bindings);
		server.use(
			refetch.handler,
			http.put<PathParams, { instanceModelId: number; timeoutSeconds: number }>(
				"*/workspaces/:workspaceSlug/agents/PRACTICE_REVIEW",
				async ({ request }) => {
					const body = await request.json();
					const saved: AgentBinding = {
						...binding("PRACTICE_REVIEW", body.instanceModelId),
						timeoutSeconds: body.timeoutSeconds,
						ready: false,
					};
					bindings = [saved];
					return HttpResponse.json(saved);
				},
			),
		);

		const detection = card("Practice reviews model");
		fireEvent.click(within(detection).getByRole("button", { name: "Advanced" }));
		fireEvent.change(within(detection).getByLabelText("Timeout (seconds)"), {
			target: { value: "900" },
		});

		fireEvent.click(saveButton("Practice reviews model"));
		await screen.findByText("Not ready");

		const saved = card("Practice reviews model");
		fireEvent.click(within(saved).getByRole("button", { name: "Advanced" }));
		expect(within(saved).getByLabelText<HTMLInputElement>("Timeout (seconds)").value).toBe("900");
		await act(async () => {
			await queryClient.cancelQueries({ queryKey: AGENTS_QUERY_KEY });
			refetch.release();
		});
	});

	it("reseeds the card to its defaults when the purpose is turned off", async () => {
		let bindings = [{ ...binding("PRACTICE_REVIEW", 20), timeoutSeconds: 900 }];
		const queryClient = await renderModelsRoute(() => bindings);
		const refetch = deferredBindingsRefetch(() => bindings);
		server.use(
			refetch.handler,
			http.delete("*/workspaces/:workspaceSlug/agents/PRACTICE_REVIEW", () => {
				bindings = [];
				return new HttpResponse(null, { status: 204 });
			}),
		);

		const detection = card("Practice reviews model");
		fireEvent.click(within(detection).getByRole("button", { name: "Advanced" }));
		expect(within(detection).getByLabelText<HTMLInputElement>("Timeout (seconds)").value).toBe(
			"900",
		);

		fireEvent.click(within(detection).getByRole("button", { name: "Clear assignment" }));

		await waitFor(() =>
			expect(
				within(card("Practice reviews model")).queryByRole("button", { name: "Clear assignment" }),
			).toBeNull(),
		);
		const reset = card("Practice reviews model");
		fireEvent.click(within(reset).getByRole("button", { name: "Advanced" }));
		expect(within(reset).getByLabelText<HTMLInputElement>("Timeout (seconds)").value).toBe("10800");
		await act(async () => {
			await queryClient.cancelQueries({ queryKey: AGENTS_QUERY_KEY });
			refetch.release();
		});
	});
});
