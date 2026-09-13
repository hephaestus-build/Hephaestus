import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import { listAgentsQueryKey } from "@/api/@tanstack/react-query.gen";
import type { AgentBinding } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
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
		dataHandlingTier: "IN_HOUSE",
	},
	{
		id: 21,
		scope: "SHARED",
		displayName: "GPT Other",
		connectionDisplayName: "Shared OpenAI",
		supportsReasoning: false,
		pricingMode: "NO_CHARGE",
		dataHandlingTier: "PROVIDER_NOT_KEPT",
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

/** The undeclared row takes any model, so most cases bind there and read the row by its title. */
function binding(
	purpose: AgentBinding["purpose"],
	instanceModelId: number,
	dataHandlingTier: AgentBinding["dataHandlingTier"] = "UNDECLARED",
): AgentBinding {
	return {
		dataHandlingTier,
		purpose,
		instanceModelId,
		enabled: true,
		ready: true,
		timeoutSeconds: 600,
		maxConcurrentJobs: 3,
		allowInternet: false,
	};
}

function mockModelsRoute(bindings: () => AgentBinding[], aiChoiceRequired = false) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/onboarding/settings", () =>
			HttpResponse.json({
				aiChoiceRequired,
				enabled: true,
				requiredConnectionIds: [],
				revision: 1,
			}),
		),
		// The admin's own state says the choice is required for *them*; the page must not read it.
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				aiChoiceRequired: true,
				aiChoice: "ANY_DECLARED",
			}),
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

async function renderModelsRoute(bindings: () => AgentBinding[], aiChoiceRequired = false) {
	mockModelsRoute(bindings, aiChoiceRequired);
	const queryClient = renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("heading", { name: "AI models" }, ROUTE_RENDER_WAIT);
	await screen.findByRole("region", { name: "Practice reviews" }, ROUTE_RENDER_WAIT);
	return queryClient;
}

type PurposeTitle = "Practice reviews" | "Heph";
const UNCHOSEN = "Members who haven't chosen";

function row(purpose: PurposeTitle, title: string = UNCHOSEN): HTMLElement {
	return within(screen.getByRole("region", { name: purpose })).getByRole("group", {
		name: title,
	});
}

const previewTerm = (purpose: PurposeTitle, term: string) =>
	within(screen.getByRole("region", { name: purpose })).queryByText(term, { selector: "dt" });

const saveButton = (purpose: PurposeTitle, title: string = UNCHOSEN) =>
	within(row(purpose, title)).getByRole<HTMLButtonElement>("button", { name: /^Save assignment/ });
const clearButton = (scope: HTMLElement) =>
	within(scope).getByRole("button", { name: /^Clear assignment/ });
const advancedButton = (scope: HTMLElement) =>
	within(scope).getByRole("button", { name: /^Advanced/ });
const timeoutInput = (scope: HTMLElement) =>
	within(scope).getByLabelText<HTMLInputElement>(/^Timeout \(seconds\)/);
/** One picker per row, so the row is the disambiguation. */
const pickerOf = (scope: HTMLElement) => within(scope).getByRole("combobox");

describe("workspace AI models route", () => {
	it("offers a concurrency limit only for queued practice reviews, not Heph turns", async () => {
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20), binding("MENTOR", 20)]);
		const reviews = row("Practice reviews");
		const mentor = row("Heph");
		fireEvent.click(advancedButton(reviews));
		fireEvent.click(advancedButton(mentor));
		expect(
			within(reviews).getByRole<HTMLInputElement>("spinbutton", { name: /^Max concurrent runs/ })
				.value,
		).toBe("3");
		expect(within(mentor).queryByRole("spinbutton", { name: /^Max concurrent runs/ })).toBeNull();
		expect(timeoutInput(mentor).value).toBe("600");
	});

	it("keeps each purpose's row pending independently when two saves run at once", async () => {
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

		fireEvent.click(saveButton("Practice reviews"));
		await waitFor(() => expect(detectionSaves).toBe(1));
		fireEvent.click(saveButton("Heph"));

		await waitFor(() => expect(saveButton("Heph").disabled).toBe(false));
		expect(saveButton("Practice reviews").disabled).toBe(true);

		releaseSlowSave?.();
		await waitFor(() => expect(saveButton("Practice reviews").disabled).toBe(false));
		expect(detectionSaves).toBe(1);
	});

	it("keeps unsaved run limits when another admin repoints the same row", async () => {
		let bindings = [binding("PRACTICE_REVIEW", 20)];
		const queryClient = await renderModelsRoute(() => bindings);

		const detection = row("Practice reviews");
		fireEvent.click(advancedButton(detection));
		const timeout = timeoutInput(detection);
		fireEvent.change(timeout, { target: { value: "900" } });
		expect(timeout.value).toBe("900");

		bindings = [{ ...binding("PRACTICE_REVIEW", 21), ready: false }];
		await queryClient.invalidateQueries({
			queryKey: listAgentsQueryKey({ path: { workspaceSlug: "acme" } }),
		});
		await screen.findByText("Not ready");

		expect(timeoutInput(row("Practice reviews")).value).toBe("900");
	});

	it("reads back what was just saved, not what the row was showing before", async () => {
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

		const detection = row("Practice reviews");
		fireEvent.click(advancedButton(detection));
		fireEvent.change(timeoutInput(detection), { target: { value: "900" } });

		fireEvent.click(saveButton("Practice reviews"));
		await screen.findByText("Not ready");

		const saved = row("Practice reviews");
		fireEvent.click(advancedButton(saved));
		expect(timeoutInput(saved).value).toBe("900");
		await act(async () => {
			await queryClient.cancelQueries({ queryKey: AGENTS_QUERY_KEY });
			refetch.release();
		});
	});

	it("reseeds the row to its defaults when the assignment is cleared", async () => {
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

		const detection = row("Practice reviews");
		fireEvent.click(advancedButton(detection));
		expect(timeoutInput(detection).value).toBe("900");

		fireEvent.click(clearButton(detection));

		await waitFor(() =>
			expect(
				within(row("Practice reviews")).queryByRole("button", { name: /^Clear assignment/ }),
			).toBeNull(),
		);
		const reset = row("Practice reviews");
		fireEvent.click(advancedButton(reset));
		expect(timeoutInput(reset).value).toBe("10800");
		await act(async () => {
			await queryClient.cancelQueries({ queryKey: AGENTS_QUERY_KEY });
			refetch.release();
		});
	});

	it("reads whether the choice is required from the workspace, not from the admin's own state", async () => {
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
		expect(previewTerm("Practice reviews", UNCHOSEN)?.nextElementSibling?.textContent).toBe(
			"→ Not declared: GPT Test",
		);
		expect(within(row("Practice reviews")).queryByText(/serves no one now/)).toBeNull();
	});

	it("drops the unchosen preview row once the workspace requires the choice", async () => {
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20)], true);
		expect(previewTerm("Practice reviews", UNCHOSEN)).toBeNull();
		within(row("Practice reviews")).getByText(/serves no one now/);
	});

	it("keeps one row pending while its sibling of the same purpose stays editable", async () => {
		let releaseSlowSave: (() => void) | undefined;
		const slowSave = new Promise<void>((resolve) => {
			releaseSlowSave = resolve;
		});
		server.use(
			http.put("*/workspaces/:workspaceSlug/agents/PRACTICE_REVIEW", async () => {
				await slowSave;
				return HttpResponse.json(binding("PRACTICE_REVIEW", 20, "IN_HOUSE"));
			}),
		);
		await renderModelsRoute(() => [
			binding("PRACTICE_REVIEW", 20, "IN_HOUSE"),
			binding("PRACTICE_REVIEW", 21, "PROVIDER_NOT_KEPT"),
		]);

		fireEvent.click(saveButton("Practice reviews", "Stays in-house"));

		await waitFor(() =>
			expect(saveButton("Practice reviews", "Stays in-house").disabled).toBe(true),
		);
		expect(saveButton("Practice reviews", "Provider, nothing kept").disabled).toBe(false);
		expect(
			pickerOf(row("Practice reviews", "Provider, nothing kept")).hasAttribute("disabled"),
		).toBe(false);

		releaseSlowSave?.();
		await waitFor(() =>
			expect(saveButton("Practice reviews", "Stays in-house").disabled).toBe(false),
		);
	});

	it("words the server's slot refusal from the registry and keeps it on the row that sent it", async () => {
		server.use(
			http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", () =>
				HttpResponse.json(
					{
						type: "about:blank",
						title: "Conflict",
						status: 409,
						detail: "This model is declared as a different tier; assign it to that row.",
						declaredTier: "PROVIDER_NOT_KEPT",
					},
					{ status: 409 },
				),
			),
		);
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20, "IN_HOUSE")]);

		fireEvent.click(saveButton("Practice reviews", "Stays in-house"));

		const inHouse = row("Practice reviews", "Stays in-house");
		await within(inHouse).findByText(
			"This model is declared as Provider, nothing kept; assign it to that row.",
		);
		expect(pickerOf(inHouse).getAttribute("aria-invalid")).toBe("true");
		expect(within(row("Practice reviews")).queryByRole("alert")).toBeNull();
		expect(screen.queryByRole("status")).toBeNull();
	});
});

it("shows a slot refusal that names no tier as the server phrased it", async () => {
	server.use(
		http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", () =>
			HttpResponse.json(
				{
					type: "about:blank",
					title: "Conflict",
					status: 409,
					detail: "This model's data handling isn't declared yet.",
				},
				{ status: 409 },
			),
		),
	);
	await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20, "IN_HOUSE")]);

	fireEvent.click(saveButton("Practice reviews", "Stays in-house"));

	await within(row("Practice reviews", "Stays in-house")).findByText(
		"This model's data handling isn't declared yet.",
	);
});

it("saves and clears only the selected tier and lists only that tier's models", async () => {
	const user = userEvent.setup();
	let bindings: AgentBinding[] = [
		binding("PRACTICE_REVIEW", 20, "IN_HOUSE"),
		binding("PRACTICE_REVIEW", 21, "PROVIDER_NOT_KEPT"),
	];
	mockModelsRoute(() => bindings);
	let savedTier: string | null = null;
	let deletedTier: string | null = null;
	server.use(
		http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", ({ request }) => {
			savedTier = new URL(request.url).searchParams.get("dataHandlingTier");
			return HttpResponse.json(binding("PRACTICE_REVIEW", 21, "PROVIDER_NOT_KEPT"));
		}),
		http.delete("*/workspaces/acme/agents/PRACTICE_REVIEW", ({ request }) => {
			deletedTier = new URL(request.url).searchParams.get("dataHandlingTier");
			bindings = bindings.filter((entry) => entry.dataHandlingTier !== deletedTier);
			return new HttpResponse(null, { status: 204 });
		}),
	);
	const queryClient = renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("region", { name: "Practice reviews" }, ROUTE_RENDER_WAIT);
	const reviews = within(screen.getByRole("region", { name: "Practice reviews" }));

	await user.click(reviews.getByRole("combobox", { name: /Provider, nothing kept/ }));
	const compatible = await screen.findByRole("option", { name: /GPT Other/ });
	expect(screen.queryByRole("option", { name: /GPT Test/ })).toBeNull();
	await user.click(compatible);
	await user.click(saveButton("Practice reviews", "Provider, nothing kept"));
	await waitFor(() => expect(savedTier).toBe("PROVIDER_NOT_KEPT"));
	await waitFor(() =>
		expect(saveButton("Practice reviews", "Provider, nothing kept").disabled).toBe(false),
	);

	await user.click(clearButton(row("Practice reviews", "Provider, nothing kept")));
	await waitFor(() => expect(deletedTier).toBe("PROVIDER_NOT_KEPT"));
	await waitFor(() =>
		expect(queryClient.getQueryData<AgentBinding[]>(AGENTS_QUERY_KEY)).toStrictEqual([
			binding("PRACTICE_REVIEW", 20, "IN_HOUSE"),
		]),
	);
	expect(pickerOf(row("Practice reviews", "Stays in-house")).textContent).toContain("GPT Test");
	expect(pickerOf(row("Practice reviews", "Provider, nothing kept")).textContent).toContain(
		"Select a model",
	);
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
	await screen.findByRole("region", { name: "Practice reviews" }, ROUTE_RENDER_WAIT);
	fireEvent.click(advancedButton(row("Practice reviews")));
	fireEvent.change(timeoutInput(row("Practice reviews")), { target: { value: "900" } });
	fireEvent.click(saveButton("Practice reviews"));
	await waitFor(() => expect(started).toBe(true));
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/admin/models",
			params: { workspaceSlug: "other" },
		});
	});
	await waitFor(() => expect(pickerOf(row("Practice reviews")).textContent).toContain("GPT Other"));
	fireEvent.click(advancedButton(row("Practice reviews")));
	expect(timeoutInput(row("Practice reviews")).value).toBe("1200");
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
	expect(timeoutInput(row("Practice reviews")).value).toBe("1200");
});
