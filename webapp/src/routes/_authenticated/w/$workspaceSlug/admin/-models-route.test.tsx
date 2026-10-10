import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";
import { deferred } from "@/test/async";

import { getMemberOnboardingQueryKey, listAgentsQueryKey } from "@/api/@tanstack/react-query.gen";
import type {
	AgentBinding,
	AvailableLlmModel,
	PracticePrecomputeSummary,
	WorkspaceLlmConnection,
	WorkspaceLlmModel,
} from "@/api/types.gen";
import { SERVED_ALONE } from "@/components/admin/workspace-llm/fixtures";
import type { Wire } from "@/lib/dates";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { server } from "@/mocks/server";
import { levelsOpenedBy } from "@/test/detail-stack";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

const MODELS: Wire<AvailableLlmModel>[] = [
	{
		id: 20,
		scope: "SHARED",
		displayName: "GPT Test",
		connectionDisplayName: "Shared OpenAI",
		pricingMode: "NO_CHARGE",
		dataHandlingTier: "IN_HOUSE",
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
	},
	{
		id: 21,
		scope: "SHARED",
		displayName: "GPT Other",
		connectionDisplayName: "Shared OpenAI",
		pricingMode: "NO_CHARGE",
		dataHandlingTier: "CLOUD",
		purposes: ["PRACTICE_REVIEW", "MENTOR"],
	},
];

const WORKSPACE = {
	id: 1,
	slug: "acme",
	displayName: "Acme",
	practicesEnabled: true,
};

const USAGE = {
	month: "2026-07",
	instanceTotalCostUsd: 0,
	ownProviderTotalCostUsd: 0,
	instanceBudgetVerdict: "WITHIN",
	ownProviderBudgetVerdict: "WITHIN",
	instancePaused: false,
	ownProviderPaused: false,
	ownProviderInUse: false,
	unpricedEventCount: 0,
	byJobType: [],
	byDay: [],
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
		servedTiers: [...SERVED_ALONE[dataHandlingTier]],
	};
}

function mockModelsRoute(
	bindings: () => AgentBinding[],
	aiChoiceRequired = false,
	needs: Wire<PracticePrecomputeSummary>[] = [],
) {
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
				aiChoice: "CLOUD",
			}),
		),
		http.get("*/workspaces/:workspaceSlug/agents", () => HttpResponse.json(bindings())),
		http.get("*/workspaces/:workspaceSlug/llm/available-models", () => HttpResponse.json(MODELS)),
		http.get("*/workspaces/:workspaceSlug/llm/settings", () =>
			HttpResponse.json({ ownProviderAllowed: false }),
		),
		http.get("*/workspaces/:workspaceSlug/llm/connections", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/precompute", () => HttpResponse.json(needs)),
		http.get("*/workspaces/:workspaceSlug/llm/usage", () => HttpResponse.json(USAGE)),
		http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(WORKSPACE)),
		http.get("*/workspaces", () => HttpResponse.json([WORKSPACE_LIST_ITEM])),
	);
}

/** Holds every later read of the bindings until released, and says whether one was asked for. */
function deferredBindingsRefetch(bindings: () => AgentBinding[]) {
	const { promise: pending, resolve: release } = deferred();
	let asked = false;
	return {
		handler: http.get("*/workspaces/:workspaceSlug/agents", async () => {
			asked = true;
			await pending;
			return HttpResponse.json(bindings());
		}),
		asked: () => asked,
		release,
	};
}

async function renderModelsRoute(bindings: () => AgentBinding[], aiChoiceRequired = false) {
	mockModelsRoute(bindings, aiChoiceRequired);
	const queryClient = renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("heading", { name: "AI models" }, ROUTE_RENDER_WAIT);
	await screen.findByRole("button", { name: "Practice reviews" }, ROUTE_RENDER_WAIT);
	return queryClient;
}

type PurposeTitle = "Practice reviews" | "Heph";
const UNCHOSEN = "Members who have not chosen";

/** A purpose's row, opened if it is closed: a closed row holds no forms. */
function purposeRegion(purpose: string): HTMLElement {
	const trigger = screen.getByRole("button", { name: purpose });
	if (trigger.getAttribute("aria-expanded") !== "true") {
		fireEvent.click(trigger);
	}
	return screen.getByRole("region", { name: purpose });
}

function row(purpose: PurposeTitle, title: string = UNCHOSEN): HTMLElement {
	return within(purposeRegion(purpose)).getByRole("group", { name: title });
}

/** What a purpose's closed row says, as a screen reader hears it. */
const rowSummary = (purpose: PurposeTitle) =>
	(screen.getByRole("button", { name: purpose }).getAttribute("aria-describedby") ?? "")
		.split(" ")
		.map((id) => document.getElementById(id)?.textContent ?? "")
		.join(" ");

/** Named "Saving…" while its own save is in flight. */
const saveButton = (purpose: PurposeTitle, title: string = UNCHOSEN) =>
	within(row(purpose, title)).getByRole<HTMLButtonElement>("button", {
		name: /^(?:Save assignment|Saving…)/u,
	});
const clearButton = (scope: HTMLElement) =>
	within(scope).getByRole("button", { name: /^Clear assignment/u });
const advancedButton = (scope: HTMLElement) =>
	within(scope).getByRole("button", { name: /^Advanced/u });
const timeoutInput = (scope: HTMLElement) =>
	within(scope).getByLabelText<HTMLInputElement>(/^Timeout \(seconds\)/u);
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
			within(reviews).getByRole<HTMLInputElement>("spinbutton", { name: /^Max concurrent runs/u })
				.value,
		).toBe("3");
		expect(within(mentor).queryByRole("spinbutton", { name: /^Max concurrent runs/u })).toBeNull();
		expect(timeoutInput(mentor).value).toBe("600");
	});

	it("keeps each purpose's row pending independently when two saves run at once", async () => {
		const { promise: slowSave, resolve: releaseSlowSave } = deferred();
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

		releaseSlowSave();
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

	it("reseeds a saved row from the bindings read back after the save, not before", async () => {
		let bindings = [binding("PRACTICE_REVIEW", 20)];
		await renderModelsRoute(() => bindings);
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
		await waitFor(() => expect(refetch.asked()).toBe(true));
		// Until the bindings are back, the row waits on its save and claims nothing new.
		expect(saveButton("Practice reviews").textContent).toMatch(/^Saving…/u);
		expect(screen.queryByText("Not ready")).toBeNull();

		refetch.release();
		await screen.findByText("Not ready");
		const saved = row("Practice reviews");
		fireEvent.click(advancedButton(saved));
		expect(timeoutInput(saved).value).toBe("900");
	});

	it("reseeds the row to its defaults when the assignment is cleared", async () => {
		let bindings = [{ ...binding("PRACTICE_REVIEW", 20), timeoutSeconds: 900 }];
		await renderModelsRoute(() => bindings);
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
		await waitFor(() => expect(refetch.asked()).toBe(true));
		within(row("Practice reviews")).getByRole("button", { name: /^Clearing…/u });

		refetch.release();
		await waitFor(() =>
			expect(
				within(row("Practice reviews")).queryByRole("button", { name: /^Clear assignment/u }),
			).toBeNull(),
		);
		const reset = row("Practice reviews");
		fireEvent.click(advancedButton(reset));
		expect(timeoutInput(reset).value).toBe("10800");
	});

	it("shows every tier of a purpose as the server routes it once a save lands", async () => {
		const user = userEvent.setup();
		// Each tier has its own ready review model, so each serves only its own members.
		let bindings: AgentBinding[] = [
			{ ...binding("PRACTICE_REVIEW", 20, "IN_HOUSE"), servedTiers: ["IN_HOUSE"] },
			binding("PRACTICE_REVIEW", 21, "CLOUD"),
		];
		await renderModelsRoute(() => bindings);
		expect(rowSummary("Practice reviews")).toContain("Cloud: GPT Other");
		const refetch = deferredBindingsRefetch(() => bindings);
		server.use(
			refetch.handler,
			http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", () => {
				// Turned off, the Cloud model serves no one, and the In-house one takes Cloud members.
				const cloud = {
					...binding("PRACTICE_REVIEW", 21, "CLOUD"),
					enabled: false,
					servedTiers: [],
				};
				bindings = [binding("PRACTICE_REVIEW", 20, "IN_HOUSE"), cloud];
				return HttpResponse.json(cloud);
			}),
		);

		const cloud = row("Practice reviews", "Cloud");
		await user.click(within(cloud).getByRole("switch", { name: /^Use this model/u }));
		await user.click(saveButton("Practice reviews", "Cloud"));
		await waitFor(() => expect(refetch.asked()).toBe(true));
		// The saved row alone would leave Cloud members unserved beside a sibling that still says
		// it serves only In-house members.
		expect(rowSummary("Practice reviews")).toContain("Cloud: GPT Other");

		refetch.release();
		await waitFor(() =>
			expect(rowSummary("Practice reviews")).toContain("Cloud: GPT TestIn-house"),
		);
	});

	it("reads whether the choice is required from the workspace, not from the admin's own state", async () => {
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
		expect(rowSummary("Practice reviews")).toContain("Not chosen: GPT Test");
		expect(within(row("Practice reviews")).queryByText(/no one uses it now/u)).toBeNull();
	});

	it("drops the unchosen column once the workspace requires the choice", async () => {
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20)], true);
		expect(rowSummary("Practice reviews")).not.toContain("Not chosen");
		within(row("Practice reviews")).getByText(/no one uses it now/u);
	});

	it("keeps one row pending while its sibling of the same purpose stays editable", async () => {
		const { promise: slowSave, resolve: releaseSlowSave } = deferred();
		server.use(
			http.put("*/workspaces/:workspaceSlug/agents/PRACTICE_REVIEW", async () => {
				await slowSave;
				return HttpResponse.json(binding("PRACTICE_REVIEW", 20, "IN_HOUSE"));
			}),
		);
		await renderModelsRoute(() => [
			binding("PRACTICE_REVIEW", 20, "IN_HOUSE"),
			binding("PRACTICE_REVIEW", 21, "CLOUD"),
		]);

		fireEvent.click(saveButton("Practice reviews", "In-house"));

		await waitFor(() => expect(saveButton("Practice reviews", "In-house").disabled).toBe(true));
		expect(saveButton("Practice reviews", "Cloud").disabled).toBe(false);
		expect(pickerOf(row("Practice reviews", "Cloud")).hasAttribute("disabled")).toBe(false);

		releaseSlowSave();
		await waitFor(() => expect(saveButton("Practice reviews", "In-house").disabled).toBe(false));
	});

	it("names the write a row waits on: a clear reads Clearing…, never Saving…", async () => {
		const { promise: slowClear, resolve: releaseSlowClear } = deferred();
		server.use(
			http.delete("*/workspaces/:workspaceSlug/agents/PRACTICE_REVIEW", async () => {
				await slowClear;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);

		fireEvent.click(clearButton(row("Practice reviews")));

		const clearing = await within(row("Practice reviews")).findByRole("button", {
			name: /^Clearing…/u,
		});
		expect(clearing.hasAttribute("disabled")).toBe(true);
		expect(within(row("Practice reviews")).queryByRole("button", { name: /^Saving…/u })).toBeNull();

		releaseSlowClear();
		await waitFor(() =>
			expect(
				within(row("Practice reviews")).queryByRole("button", { name: /^Clearing…/u }),
			).toBeNull(),
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
						detail: "This model is declared as a different tier. Assign it under that tier.",
						declaredTier: "CLOUD",
					},
					{ status: 409 },
				),
			),
		);
		await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20, "IN_HOUSE")]);

		fireEvent.click(saveButton("Practice reviews", "In-house"));

		const inHouse = row("Practice reviews", "In-house");
		await within(inHouse).findByText("This model is declared as Cloud. Assign it under Cloud.");
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
					detail: "This model's data handling is not declared yet.",
				},
				{ status: 409 },
			),
		),
	);
	await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20, "IN_HOUSE")]);

	fireEvent.click(saveButton("Practice reviews", "In-house"));

	await within(row("Practice reviews", "In-house")).findByText(
		"This model's data handling is not declared yet.",
	);
});

it("saves and clears only the selected tier and lists only that tier's models", async () => {
	const user = userEvent.setup();
	let bindings: AgentBinding[] = [
		binding("PRACTICE_REVIEW", 20, "IN_HOUSE"),
		binding("PRACTICE_REVIEW", 21, "CLOUD"),
	];
	mockModelsRoute(() => bindings);
	let savedTier: string | null = null;
	let deletedTier: string | null = null;
	server.use(
		http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", ({ request }) => {
			savedTier = new URL(request.url).searchParams.get("dataHandlingTier");
			return HttpResponse.json(binding("PRACTICE_REVIEW", 21, "CLOUD"));
		}),
		http.delete("*/workspaces/acme/agents/PRACTICE_REVIEW", ({ request }) => {
			deletedTier = new URL(request.url).searchParams.get("dataHandlingTier");
			bindings = bindings.filter((entry) => entry.dataHandlingTier !== deletedTier);
			return new HttpResponse(null, { status: 204 });
		}),
	);
	const queryClient = renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("button", { name: "Practice reviews" }, ROUTE_RENDER_WAIT);
	const reviews = within(purposeRegion("Practice reviews"));

	await user.click(reviews.getByRole("combobox", { name: /Cloud/u }));
	const compatible = await screen.findByRole("option", { name: /GPT Other/u });
	expect(screen.queryByRole("option", { name: /GPT Test/u })).toBeNull();
	await user.click(compatible);
	await user.click(saveButton("Practice reviews", "Cloud"));
	await waitFor(() => expect(savedTier).toBe("CLOUD"));
	await waitFor(() => expect(saveButton("Practice reviews", "Cloud").disabled).toBe(false));

	await user.click(clearButton(row("Practice reviews", "Cloud")));
	await waitFor(() => expect(deletedTier).toBe("CLOUD"));
	await waitFor(() =>
		expect(queryClient.getQueryData<AgentBinding[]>(AGENTS_QUERY_KEY)).toStrictEqual([
			binding("PRACTICE_REVIEW", 20, "IN_HOUSE"),
		]),
	);
	expect(pickerOf(row("Practice reviews", "In-house")).textContent).toContain("GPT Test");
	expect(pickerOf(row("Practice reviews", "Cloud")).textContent).toContain("Select a model");
});

it("lists Heph in the navigation once a Heph model is assigned, and drops it when the assignment is cleared", async () => {
	const user = userEvent.setup();
	let bindings: AgentBinding[] = [binding("PRACTICE_REVIEW", 20)];
	mockModelsRoute(() => bindings);
	server.use(
		http.get("*/workspaces/acme/onboarding/me", () =>
			HttpResponse.json({
				...workspaceOnboarding(),
				aiOptions: [
					{
						choice: "CLOUD",
						mentorReady: bindings.some((entry) => entry.purpose === "MENTOR"),
						practiceReviewsReady: true,
						models: [],
					},
				],
			}),
		),
		http.put("*/workspaces/acme/agents/MENTOR", () => {
			bindings = [...bindings, binding("MENTOR", 20)];
			return HttpResponse.json(binding("MENTOR", 20));
		}),
		http.delete("*/workspaces/acme/agents/MENTOR", () => {
			bindings = bindings.filter((entry) => entry.purpose !== "MENTOR");
			return new HttpResponse(null, { status: 204 });
		}),
	);
	const queryClient = renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("button", { name: "Heph" }, ROUTE_RENDER_WAIT);
	await waitFor(() =>
		expect(
			queryClient.getQueryState(getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }))
				?.status,
		).toBe("success"),
	);
	expect(screen.queryByRole("link", { name: /AI mentor/u })).toBeNull();

	await user.click(pickerOf(row("Heph")));
	await user.click(await screen.findByRole("option", { name: /GPT Test/u }));
	await user.click(saveButton("Heph"));
	await screen.findByRole("link", { name: /AI mentor/u }, ROUTE_RENDER_WAIT);

	await user.click(clearButton(row("Heph")));
	await waitFor(
		() => expect(screen.queryByRole("link", { name: /AI mentor/u })).toBeNull(),
		ROUTE_RENDER_WAIT,
	);
});

it("resets model drafts on workspace navigation while a previous workspace save completes", async () => {
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
	let savedAcme = binding("PRACTICE_REVIEW", 20);
	const { promise: response, resolve: release } = deferred();
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
	await screen.findByRole("button", { name: "Practice reviews" }, ROUTE_RENDER_WAIT);
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
	// The save lands on the workspace it was made in: its bindings are read again on the next visit.
	await waitFor(() =>
		expect(queryClient.getQueryState(AGENTS_QUERY_KEY)?.isInvalidated).toBe(true),
	);
	expect(
		queryClient.getQueryData<AgentBinding[]>(
			listAgentsQueryKey({ path: { workspaceSlug: "other" } }),
		)?.[0],
	).toMatchObject({ instanceModelId: 21, timeoutSeconds: 1200 });
	expect(timeoutInput(row("Practice reviews")).value).toBe("1200");
});

it("opens the purpose a link names, and drops the name from the address when the reader closes it", async () => {
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
	const { router } = renderRouteAtWithRouter("/w/acme/admin/models?purpose=PRACTICE_RERANKING");
	const reranking = await screen.findByRole(
		"button",
		{ name: "Reranking model" },
		ROUTE_RENDER_WAIT,
	);
	expect(reranking.getAttribute("aria-expanded")).toBe("true");
	expect(screen.getByRole("button", { name: "Decision model" }).getAttribute("aria-expanded")).toBe(
		"false",
	);

	fireEvent.click(reranking);

	await waitFor(() => expect(router.state.location.searchStr).toBe(""));
	expect(reranking.getAttribute("aria-expanded")).toBe("false");
});

it("ignores a purpose the page does not have", async () => {
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
	const { router } = renderRouteAtWithRouter("/w/acme/admin/models?purpose=SOMETHING_ELSE");
	await screen.findByRole("button", { name: "Reranking model" }, ROUTE_RENDER_WAIT);
	expect(router.state.location.search.purpose).toBeUndefined();
	expect(screen.queryAllByRole("button", { expanded: true })).toStrictEqual([]);
});

it("counts the practices whose scripts use a model, from the workspace's precompute needs", async () => {
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)], true, [
		{
			practiceSlug: "comment-quality",
			practiceName: "Comment quality",
			asOf: { jobId: "job-1", finishedAt: "2026-10-03T09:00:00Z" },
			scriptChanged: false,
			needs: [{ purpose: "PRACTICE_DECISION", need: "REQUIRED", unmetTiers: ["IN_HOUSE"] }],
		},
	]);
	renderRouteAt("/w/acme/admin/models");
	const decision = await screen.findByRole("button", { name: "Decision model" }, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(decision.getAttribute("aria-expanded")).toBe("true"));
	const region = within(screen.getByRole("region", { name: "Decision model" }));
	expect(levelsOpenedBy(region.getByRole("link", { name: "Comment quality" }))).toStrictEqual([
		"practice:comment-quality",
	]);
	region.getByText(/^1 practice needs a decision model for In-house members\./u);
	expect(
		within(screen.getByRole("button", { name: "Embedding model" })).queryByText(/Used by/u),
	).toBeNull();
});

type UnmetTiers = PracticePrecomputeSummary["needs"][number]["unmetTiers"];

const commentQualityNeeds = (unmetTiers: UnmetTiers): Wire<PracticePrecomputeSummary> => ({
	practiceSlug: "comment-quality",
	practiceName: "Comment quality",
	asOf: { jobId: "job-1", finishedAt: "2026-10-03T09:00:00Z" },
	scriptChanged: false,
	needs: [{ purpose: "PRACTICE_DECISION", need: "REQUIRED", unmetTiers }],
});

it("keeps the page usable when the precompute needs fail, and says so in their section only", async () => {
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/precompute", () =>
			HttpResponse.json({ status: 500, title: "Internal Server Error" }, { status: 500 }),
		),
	);
	renderRouteAt("/w/acme/admin/models");
	await screen.findByText(
		"We could not load which practices use these models",
		undefined,
		ROUTE_RENDER_WAIT,
	);
	expect(screen.queryByText("We could not load AI models")).toBeNull();
	expect(saveButton("Practice reviews").disabled).toBe(false);
	// Nothing claims a practice uses a precompute model, or that none does.
	expect(
		within(screen.getByRole("button", { name: "Decision model" })).queryByText(/practice/u),
	).toBeNull();
});

it("asks again which practices miss a model once a binding is saved", async () => {
	// The server judges the needs against the bindings, so after the save it reports none unmet.
	let unmetTiers: UnmetTiers = ["IN_HOUSE"];
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)], true);
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/precompute", () =>
			HttpResponse.json([commentQualityNeeds(unmetTiers)]),
		),
		http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", () => {
			unmetTiers = [];
			return HttpResponse.json(binding("PRACTICE_REVIEW", 20));
		}),
	);
	renderRouteAt("/w/acme/admin/models");
	const decision = await screen.findByRole("button", { name: "Decision model" }, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(decision.getAttribute("aria-expanded")).toBe("true"));
	within(screen.getByRole("region", { name: "Decision model" })).getByRole("note");

	fireEvent.click(saveButton("Practice reviews"));

	await waitFor(() =>
		expect(
			within(screen.getByRole("region", { name: "Decision model" })).queryByRole("note"),
		).toBeNull(),
	);
});

it("keeps a refusal of the chosen model's kind on the row that sent it", async () => {
	server.use(
		http.put("*/workspaces/acme/agents/PRACTICE_REVIEW", () =>
			HttpResponse.json(
				{
					type: "about:blank",
					title: "Bad Request",
					status: 400,
					detail: "This is an embedding model. Choose a chat model here.",
				},
				{ status: 400 },
			),
		),
	);
	await renderModelsRoute(() => [binding("PRACTICE_REVIEW", 20, "IN_HOUSE")]);

	fireEvent.click(saveButton("Practice reviews", "In-house"));

	const inHouse = row("Practice reviews", "In-house");
	await within(inHouse).findByText("This is an embedding model. Choose a chat model here.");
	expect(pickerOf(inHouse).getAttribute("aria-invalid")).toBe("true");
});

const OWN_CONNECTION = {
	id: 7,
	slug: "own",
	displayName: "Own OpenAI",
	authMode: "BEARER",
	apiProtocol: "openai-embeddings",
	purposes: ["PRACTICE_EMBEDDING"],
	baseUrl: "https://embed.example.test/v1",
	enabled: true,
	hasApiKey: true,
	createdAt: "2026-07-01T00:00:00Z",
} satisfies Wire<WorkspaceLlmConnection>;

function ownModel(id: number, displayName: string): Wire<WorkspaceLlmModel> {
	return {
		dataHandlingTier: "IN_HOUSE",
		id,
		connectionId: OWN_CONNECTION.id,
		connectionDisplayName: OWN_CONNECTION.displayName,
		slug: `own-${id}`,
		displayName,
		upstreamModelId: `upstream-${id}`,
		enabled: true,
		pricingMode: "NO_CHARGE",
		currency: "USD",
		createdAt: "2026-07-01T00:00:00Z",
	};
}

/** The route with the workspace's own providers on: one connection, these models on it. */
function mockOwnProviders(models: () => Wire<WorkspaceLlmModel>[]) {
	mockModelsRoute(() => [binding("PRACTICE_REVIEW", 20)]);
	server.use(
		http.get("*/workspaces/:workspaceSlug/llm/settings", () =>
			HttpResponse.json({ ownProviderAllowed: true }),
		),
		http.get("*/workspaces/:workspaceSlug/llm/connections", () =>
			HttpResponse.json([OWN_CONNECTION]),
		),
		http.get("*/workspaces/:workspaceSlug/llm/models", () => HttpResponse.json(models())),
	);
}

it("offers a model added under the workspace's own provider in the pickers above, without a reload", async () => {
	const user = userEvent.setup();
	let added: Wire<WorkspaceLlmModel>[] = [];
	mockOwnProviders(() => added);
	server.use(
		http.get("*/workspaces/:workspaceSlug/llm/available-models", () =>
			HttpResponse.json([
				...MODELS,
				...added.map((model) => ({
					id: model.id,
					scope: "WORKSPACE",
					displayName: model.displayName,
					connectionDisplayName: model.connectionDisplayName,
					pricingMode: model.pricingMode,
					dataHandlingTier: model.dataHandlingTier,
					purposes: ["PRACTICE_EMBEDDING"],
				})),
			]),
		),
		http.post("*/workspaces/acme/llm/connections/7/models", () => {
			added = [ownModel(40, "Own embeddings")];
			return HttpResponse.json(added[0]);
		}),
	);
	renderRouteAt("/w/acme/admin/models");
	const card = within(await screen.findByRole("region", { name: "Own OpenAI" }, ROUTE_RENDER_WAIT));
	const inHousePicker = () =>
		within(
			within(purposeRegion("Embedding model")).getByRole("group", { name: "In-house" }),
		).getByRole("combobox");
	expect(inHousePicker().hasAttribute("disabled")).toBe(true);

	await user.click(card.getByRole("button", { name: "Add model to Own OpenAI" }));
	const dialog = within(await screen.findByRole("dialog"));
	await user.type(dialog.getByLabelText("Display name"), "Own embeddings");
	await user.type(dialog.getByLabelText("Upstream model ID"), "own-embed");
	await user.click(dialog.getByRole("button", { name: "Add inactive model" }));

	await waitFor(() => expect(inHousePicker().hasAttribute("disabled")).toBe(false));
	await user.click(inHousePicker());
	await screen.findByRole("option", { name: /Own embeddings/u });
});

/** Turns one of the workspace's own models off through its edit dialog. */
async function turnOffModel(name: string) {
	const user = userEvent.setup();
	await user.click(await screen.findByRole("button", { name: `Edit ${name}` }, ROUTE_RENDER_WAIT));
	const dialog = within(await screen.findByRole("dialog"));
	await user.click(dialog.getByRole("switch", { name: /^Active/u }));
	await user.click(dialog.getByRole("button", { name: "Save changes" }));
	await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
}

/** The route with one of the workspace's own models assigned to Cloud members' reviews. */
function mockOwnModelAssigned() {
	const own: Wire<WorkspaceLlmModel> = {
		...ownModel(40, "Own chat"),
		dataHandlingTier: "CLOUD",
		operatedBy: "PROVIDER",
		priceNote: "Covered by our provider contract.",
	};
	let modelEnabled = true;
	const ownBinding = (): AgentBinding => ({
		...binding("PRACTICE_REVIEW", 0, "CLOUD"),
		instanceModelId: undefined,
		workspaceModelId: own.id,
		ready: modelEnabled,
		servedTiers: modelEnabled ? ["CLOUD"] : [],
	});
	let usageReads = 0;
	mockOwnProviders(() => [{ ...own, enabled: modelEnabled }]);
	server.use(
		http.get("*/workspaces/:workspaceSlug/agents", () =>
			HttpResponse.json([
				{
					...binding("PRACTICE_REVIEW", 20, "IN_HOUSE"),
					servedTiers: modelEnabled ? ["IN_HOUSE"] : ["IN_HOUSE", "CLOUD"],
				},
				ownBinding(),
			]),
		),
		http.get("*/workspaces/:workspaceSlug/llm/available-models", () =>
			HttpResponse.json([
				...MODELS,
				...(modelEnabled
					? [
							{
								id: own.id,
								scope: "WORKSPACE",
								displayName: own.displayName,
								connectionDisplayName: own.connectionDisplayName,
								pricingMode: own.pricingMode,
								dataHandlingTier: "CLOUD",
								purposes: ["PRACTICE_REVIEW"],
							},
						]
					: []),
			]),
		),
		http.get("*/workspaces/:workspaceSlug/llm/usage", () => {
			usageReads += 1;
			return HttpResponse.json(USAGE);
		}),
		http.patch("*/workspaces/acme/llm/models/40", () => {
			modelEnabled = false;
			return HttpResponse.json({ ...own, enabled: false });
		}),
	);
	return { usageReads: () => usageReads };
}

it("shows whom each assignment serves again after a write to the workspace's own models", async () => {
	mockOwnModelAssigned();
	renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("region", { name: "Own OpenAI" }, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(rowSummary("Practice reviews")).toContain("Cloud: Own chat"));

	await turnOffModel("Own chat");

	// The server now routes Cloud members to the In-house model.
	await waitFor(() => expect(rowSummary("Practice reviews")).toContain("Cloud: GPT TestIn-house"));
});

it("reads this month's usage again after a write to the workspace's own models", async () => {
	const { usageReads } = mockOwnModelAssigned();
	renderRouteAt("/w/acme/admin/models");
	await screen.findByRole("region", { name: "Own OpenAI" }, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(usageReads()).toBe(1));

	await turnOffModel("Own chat");

	await waitFor(() => expect(usageReads()).toBe(2));
});

it("keeps each provider's test pending on its own card when two run at once", async () => {
	// A single "which card is testing" flag would be cleared by the fast one and put the slow card
	// back to idle mid-flight.
	const slowProbe = deferred();
	const second = { ...OWN_CONNECTION, id: 8, displayName: "Second provider" };
	mockOwnProviders(() => []);
	server.use(
		http.get("*/workspaces/:workspaceSlug/llm/connections", () =>
			HttpResponse.json([OWN_CONNECTION, second]),
		),
		http.post("*/workspaces/acme/llm/connections/7/probe", async () => {
			await slowProbe.promise;
			return HttpResponse.json({ reachable: true, modelCount: 3 });
		}),
		http.post("*/workspaces/acme/llm/connections/8/probe", () =>
			HttpResponse.json({ reachable: true, modelCount: 1 }),
		),
	);
	renderRouteAt("/w/acme/admin/models");
	const slowCard = within(
		await screen.findByRole("region", { name: "Own OpenAI" }, ROUTE_RENDER_WAIT),
	);
	const fastCard = within(screen.getByRole("region", { name: "Second provider" }));

	fireEvent.click(slowCard.getByRole("button", { name: "Test connection to Own OpenAI" }));
	fireEvent.click(fastCard.getByRole("button", { name: "Test connection to Second provider" }));

	await fastCard.findByText(/1 model available/u);
	expect(
		slowCard.getByRole<HTMLButtonElement>("button", { name: "Testing… Own OpenAI" }).disabled,
	).toBe(true);

	slowProbe.resolve();
	await slowCard.findByText(/3 models available/u);
});

async function confirmDelete(name: string) {
	fireEvent.click(await screen.findByRole("button", { name: `Delete ${name}` }, ROUTE_RENDER_WAIT));
	const dialog = await screen.findByRole("alertdialog");
	fireEvent.click(within(dialog).getByRole("button", { name: "Delete model" }));
	await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
}

it("keeps each model's delete pending on its own row when two run at once", async () => {
	// A single "which model is busy" id would be cleared by the fast one, re-enabling the slow row's
	// Delete mid-flight: a second DELETE, and a failure toast for a model that was deleted.
	const slowDelete = deferred();
	let slowDeleteCalls = 0;
	mockOwnProviders(() => [ownModel(10, "Slow model"), ownModel(20, "Fast model")]);
	server.use(
		http.delete("*/workspaces/acme/llm/models/10", async () => {
			slowDeleteCalls += 1;
			await slowDelete.promise;
			return new HttpResponse(null, { status: 204 });
		}),
		http.delete("*/workspaces/acme/llm/models/20", () => new HttpResponse(null, { status: 204 })),
	);
	renderRouteAt("/w/acme/admin/models");

	await confirmDelete("Slow model");
	await waitFor(() => expect(slowDeleteCalls).toBe(1));
	await confirmDelete("Fast model");

	expect(
		screen.getByRole<HTMLButtonElement>("button", { name: "Delete Slow model" }).disabled,
	).toBe(true);
	slowDelete.resolve();
	await waitFor(() =>
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Delete Slow model" }).disabled,
		).toBe(false),
	);
	expect(slowDeleteCalls).toBe(1);
});
