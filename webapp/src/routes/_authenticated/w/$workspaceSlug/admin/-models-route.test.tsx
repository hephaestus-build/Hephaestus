import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import { listAgentsQueryKey } from "@/api/@tanstack/react-query.gen";
import type { AgentBinding } from "@/api/types.gen";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

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
	const field = screen.getByRole("combobox", { name: purposeLabel });
	const cardElement = field.closest("[data-slot='card']");
	if (!(cardElement instanceof HTMLElement)) throw new Error(`No card for ${purposeLabel}`);
	return cardElement;
}

const saveButton = (purposeLabel: string) =>
	within(card(purposeLabel)).getByRole<HTMLButtonElement>("button", { name: "Save assignment" });

describe("workspace AI models route", () => {
	it("offers a concurrency limit only for queued practice reviews, not Heph turns", async () => {
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20), binding("MENTOR", 20)]);
		const reviews = within(card("Practice reviews model"));
		const mentor = within(card("Heph model"));
		fireEvent.click(reviews.getByRole("button", { name: /Advanced/ }));
		fireEvent.click(mentor.getByRole("button", { name: /Advanced/ }));
		expect(
			reviews.getByRole<HTMLInputElement>("spinbutton", { name: "Max concurrent runs" }).value,
		).toBe("3");
		expect(mentor.queryByRole("spinbutton", { name: "Max concurrent runs" })).toBeNull();
		expect(
			mentor.getByRole<HTMLInputElement>("spinbutton", { name: "Timeout (seconds)" }).value,
		).toBe("600");
	});

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

it("saves and clears only the selected processing location and hides incompatible models", async () => {
	const user = userEvent.setup();
	let bindings: AgentBinding[] = [
		{ ...binding("PRACTICE_REVIEW", 20), processingLocation: "ON_PREMISES" },
		{ ...binding("PRACTICE_REVIEW", 21), processingLocation: "PRIVATE_CLOUD" },
	];
	mockModelsRoute(() => bindings);
	let savedLocation: string | null = null;
	let deletedLocation: string | null = null;
	server.use(
		http.get("*/workspaces/:workspaceSlug/llm/available-models", () =>
			HttpResponse.json([
				{ ...MODELS[0], processingLocation: "ON_PREMISES" },
				{ ...MODELS[1], processingLocation: "PRIVATE_CLOUD" },
			]),
		),
		http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", ({ request }) => {
			savedLocation = new URL(request.url).searchParams.get("processingLocation");
			return HttpResponse.json({
				...binding("PRACTICE_REVIEW", 20),
				processingLocation: "ON_PREMISES",
			});
		}),
		http.delete("*/workspaces/acme/agents/PRACTICE_REVIEW", ({ request }) => {
			deletedLocation = new URL(request.url).searchParams.get("processingLocation");
			bindings = bindings.filter((entry) => entry.processingLocation !== deletedLocation);
			return new HttpResponse(null, { status: 204 });
		}),
	);
	const queryClient = renderRouteAt("/w/acme/admin/models");
	await screen.findByLabelText("Processing location", undefined, ROUTE_RENDER_WAIT);
	await user.click(screen.getByRole("combobox", { name: "Processing location" }));
	await user.click(await screen.findByRole("option", { name: "On-premises" }));
	await user.click(screen.getByRole("combobox", { name: "Practice reviews model" }));
	const compatible = await screen.findByRole("option", { name: /GPT Test/ });
	expect(screen.queryByRole("option", { name: /GPT Other/ })).toBeNull();
	await user.click(compatible);
	await user.click(saveButton("Practice reviews model"));
	await waitFor(() => expect(savedLocation).toBe("ON_PREMISES"));
	await waitFor(() => expect(saveButton("Practice reviews model").disabled).toBe(false));
	await user.click(
		within(card("Practice reviews model")).getByRole("button", { name: "Clear assignment" }),
	);
	await waitFor(() => expect(deletedLocation).toBe("ON_PREMISES"));
	await waitFor(() =>
		expect(queryClient.getQueryData<AgentBinding[]>(AGENTS_QUERY_KEY)).toStrictEqual([
			{ ...binding("PRACTICE_REVIEW", 21), processingLocation: "PRIVATE_CLOUD" },
		]),
	);
	await user.click(screen.getByRole("combobox", { name: "Processing location" }));
	await user.click(await screen.findByRole("option", { name: "Private cloud" }));
	expect(
		within(card("Practice reviews model")).getByRole("combobox", { name: "Practice reviews model" })
			.textContent,
	).toContain("GPT Other");
});

it("resets model drafts on workspace navigation while a previous workspace save completes", async () => {
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
	let savedAcme = binding("PRACTICE_REVIEW", 20);
	let release = () => {};
	const response = new Promise<void>((resolve) => {
		release = resolve;
	});
	let started = false;
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([
				WORKSPACE_LIST_ITEM,
				{
					...WORKSPACE_LIST_ITEM,
					id: 2,
					slug: "other",
					workspaceSlug: "other",
					displayName: "Other workspace",
				},
			]),
		),
		http.get("*/workspaces/acme/agents", () => HttpResponse.json([savedAcme])),
		http.get("*/workspaces/other/agents", () =>
			HttpResponse.json([{ ...binding("PRACTICE_REVIEW", 21), timeoutSeconds: 1200 }]),
		),
		http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", async () => {
			started = true;
			await response;
			savedAcme = { ...binding("PRACTICE_REVIEW", 20), timeoutSeconds: 900 };
			return HttpResponse.json(savedAcme);
		}),
	);
	const { router, queryClient } = renderRouteAtWithRouter("/w/acme/admin/models");
	await screen.findByRole("combobox", { name: "Practice reviews model" }, ROUTE_RENDER_WAIT);
	fireEvent.click(within(card("Practice reviews model")).getByRole("button", { name: "Advanced" }));
	fireEvent.change(within(card("Practice reviews model")).getByLabelText("Timeout (seconds)"), {
		target: { value: "900" },
	});
	fireEvent.click(saveButton("Practice reviews model"));
	await waitFor(() => expect(started).toBe(true));
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/admin/models",
			params: { workspaceSlug: "other" },
		});
	});
	await waitFor(() =>
		expect(screen.getByRole("combobox", { name: "Practice reviews model" }).textContent).toContain(
			"GPT Other",
		),
	);
	fireEvent.click(within(card("Practice reviews model")).getByRole("button", { name: "Advanced" }));
	expect(
		within(card("Practice reviews model")).getByLabelText<HTMLInputElement>("Timeout (seconds)")
			.value,
	).toBe("1200");
	await act(async () => {
		release();
	});
	await waitFor(() =>
		expect(queryClient.getQueryData<AgentBinding[]>(AGENTS_QUERY_KEY)?.[0]?.timeoutSeconds).toBe(
			900,
		),
	);
	expect(
		queryClient.getQueryData<AgentBinding[]>(
			listAgentsQueryKey({ path: { workspaceSlug: "other" } }),
		)?.[0],
	).toMatchObject({ instanceModelId: 21, timeoutSeconds: 1200 });
	expect(
		within(card("Practice reviews model")).getByLabelText<HTMLInputElement>("Timeout (seconds)")
			.value,
	).toBe("1200");
});
