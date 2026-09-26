import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { deferred } from "@/test/async";

import {
	getMemberOnboardingQueryKey,
	getMemberOnboardingSettingsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding, WorkspaceOnboardingLink } from "@/api/types.gen";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { authClient } from "@/runtime/auth/auth-client";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 30_000 });

function mockWorkspace(data: WorkspaceOnboarding) {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("other")]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "MEMBER", userId: 20, userLogin: "ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", ({ params }) =>
			HttpResponse.json({
				...data,
				workspaceName: params.workspaceSlug === "acme" ? "Acme" : "Other workspace",
			}),
		),
	);
}
const firstVisit = {
	...workspaceOnboarding(),
	enabled: true,
	aiChoiceRequired: true,
	needsSetup: true,
} satisfies WorkspaceOnboarding;
const aiOptions = [
	{ choice: "IN_HOUSE_ONLY", practiceReviewsReady: true, mentorReady: true, models: [] },
	{ choice: "CLOUD", practiceReviewsReady: true, mentorReady: true, models: [] },
] satisfies WorkspaceOnboarding["aiOptions"];
const IN_HOUSE = /^In-house /u;
const CLOUD = /^Cloud /u;
const slack = {
	connectionId: 9,
	providerType: "SLACK",
	displayName: "Slack",
	registrationId: "slack",
	required: true,
	available: true,
	linked: false,
} satisfies WorkspaceOnboardingLink;

const checked = (name: string | RegExp, role: "radio" | "switch" = "radio") =>
	role === "radio"
		? screen.getByRole<HTMLInputElement>(role, { name }).checked
		: screen.getByRole(role, { name }).getAttribute("aria-checked") === "true";
const checkedBox = (name: string) =>
	screen.getByRole("checkbox", { name }).getAttribute("aria-checked") === "true";
const ASK_ON_FIRST_VISIT = "Ask members to set up on their first visit";
const disabled = (name: string) => screen.getByRole<HTMLButtonElement>("button", { name }).disabled;

describe("workspace member onboarding route", () => {
	it("saves No AI before linking required accounts without completing or leaving setup", async () => {
		let current: WorkspaceOnboarding = { ...firstVisit, aiOptions, links: [slack] };
		mockWorkspace(current);
		const choices: unknown[] = [];
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
			http.put("*/workspaces/acme/onboarding/me/ai-choice", async ({ request }) => {
				choices.push(await request.json());
				current = { ...current, aiChoice: "NO_AI" };
				return HttpResponse.json(current);
			}),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: /^No AI /u }));
		expect(choices).toStrictEqual([]);
		fireEvent.click(screen.getByRole("button", { name: "Save AI choice" }));
		await waitFor(() => expect(choices).toStrictEqual([{ choice: "NO_AI" }]));
		await screen.findByText(
			"Your AI choice is set and holds in all your workspaces. Connect Slack and you're in.",
		);
		expect(router.state.location.pathname).toBe("/w/acme/onboarding");
		expect(checked(/^No AI /u)).toBe(true);
		expect(disabled("Continue")).toBe(true);
	});

	it("keeps the loaded setup when a refetch fails, then retries it", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "NO_AI", links: [slack] });
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		expect(disabled("Continue")).toBe(true);
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({ status: 503 }, { status: 503 }),
			),
		);
		await act(async () => {
			await queryClient.invalidateQueries({
				queryKey: getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			});
		});
		await screen.findByText("Couldn't refresh your setup");
		screen.getByRole("heading", { name: "Your AI choice" });
		expect(disabled("Continue")).toBe(true);
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({
					...firstVisit,
					aiChoice: "NO_AI",
					links: [{ ...slack, linked: true }],
				}),
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByText("Connected");
		expect(screen.queryByText("Couldn't refresh your setup")).toBeNull();
		expect(disabled("Continue")).toBe(false);
	});

	it("updates an untouched saved choice on refetch without overwriting an explicit draft", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "IN_HOUSE_ONLY", aiOptions });
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		expect(checked(IN_HOUSE)).toBe(true);
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({ ...firstVisit, aiChoice: "NO_AI", aiOptions }),
			),
		);
		await act(async () => {
			await queryClient.invalidateQueries({
				queryKey: getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			});
		});
		await waitFor(() => expect(checked(/^No AI /u)).toBe(true));
		fireEvent.click(screen.getByRole("radio", { name: CLOUD }));
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({ ...firstVisit, aiChoice: "IN_HOUSE_ONLY", aiOptions }),
			),
		);
		await act(async () => {
			await queryClient.invalidateQueries({
				queryKey: getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			});
		});
		expect(checked(CLOUD)).toBe(true);
		expect(disabled("Continue")).toBe(false);
	});

	it("leaves without a write when setup could not load", async () => {
		mockWorkspace(firstVisit);
		let dismissals = 0;
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({ status: 503 }, { status: 503 }),
			),
			http.put("*/workspaces/acme/onboarding/me/dismissal", () => {
				dismissals += 1;
				return HttpResponse.json(firstVisit);
			}),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/onboarding?returnTo=%2Fw%2Facme%2Fteams");
		await screen.findByText("Couldn't load your setup", undefined, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Back to workspace" }));
		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/teams"),
			ROUTE_RENDER_WAIT,
		);
		expect(dismissals).toBe(0);
	});

	it.each([
		{
			operation: "ai-choice",
			button: "Continue",
			saved: undefined,
			// Once an answer is saved with nothing else owed, the server reports setup as done.
			prepare: () => fireEvent.click(screen.getByRole("radio", { name: /^No AI /u })),
		},
		{ operation: "dismissal", button: "Skip for now", saved: "NO_AI" as const, prepare: undefined },
	])(
		"returns to the original destination after $operation",
		async ({ operation, button, saved, prepare }) => {
			let current: WorkspaceOnboarding = { ...firstVisit, aiChoice: saved };
			mockWorkspace(current);
			server.use(
				http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
				http.put(`*/workspaces/acme/onboarding/me/${operation}`, () => {
					current = { ...current, aiChoice: "NO_AI", needsSetup: false };
					return HttpResponse.json(current);
				}),
			);
			const destination = "/w/acme/teams?view=mine#feedback";
			const { router } = renderRouteAtWithRouter(
				`/w/acme/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
			);
			await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
			prepare?.();
			fireEvent.click(await screen.findByRole("button", { name: button }));
			await waitFor(
				() => expect(router.state.location.pathname).toBe("/w/acme/teams"),
				ROUTE_RENDER_WAIT,
			);
			expect(router.state.location.search).toMatchObject({ view: "mine" });
			expect(router.state.location.hash).toBe("feedback");
		},
	);

	it.each([
		"https://attacker.example/w/acme/teams",
		"//attacker.example/w/acme/teams",
		"/w/other/practices",
		"/w/acme/../other/practices",
		"/w/acme/%2e%2e/other/practices",
		"/w/acme/%252e%252e/other/practices",
		"/w/acme/%6fnboarding",
		"/w/acme/onboarding?returnTo=/w/acme/onboarding",
	])("rejects an unsafe setup destination: %s", async (returnTo) => {
		let current: WorkspaceOnboarding = firstVisit;
		mockWorkspace(current);
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
			http.put("*/workspaces/acme/onboarding/me/dismissal", () => {
				current = { ...current, needsSetup: false };
				return HttpResponse.json(current);
			}),
		);
		const { router } = renderRouteAtWithRouter(
			`/w/acme/onboarding?${new URLSearchParams({ returnTo })}`,
		);
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Skip for now" }));
		// With leaderboards disabled, workspace home opens the member’s own page.
		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/user/ada"),
			ROUTE_RENDER_WAIT,
		);
	});

	it("preserves the destination and accounts step through provider linking", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "NO_AI", links: [slack] });
		const link = vi.spyOn(authClient, "linkAccount").mockImplementation(vi.fn());
		const destination = "/w/acme/teams?view=mine#feedback";
		renderRouteAtWithRouter(
			`/w/acme/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
		);
		await screen.findByRole("heading", { name: "Connect your accounts" }, ROUTE_RENDER_WAIT);
		fireEvent.click(await screen.findByRole("button", { name: "Connect Slack" }));
		expect(link).toHaveBeenCalledWith(
			"slack",
			`/w/acme/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
		);
		link.mockRestore();
	});

	it("saves an unsaved choice before redirecting to the provider", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "IN_HOUSE_ONLY", aiOptions, links: [slack] });
		const order: string[] = [];
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", async ({ request }) => {
				order.push(`ai-choice ${JSON.stringify(await request.json())}`);
				return HttpResponse.json({
					...firstVisit,
					aiChoice: "CLOUD",
					aiOptions,
					links: [slack],
				});
			}),
		);
		const link = vi.spyOn(authClient, "linkAccount").mockImplementation(() => {
			order.push("linkAccount");
		});
		renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: CLOUD }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI choice and connect Slack" }));
		await waitFor(() => expect(link).toHaveBeenCalledOnce());
		expect(order).toStrictEqual(['ai-choice {"choice":"CLOUD"}', "linkAccount"]);
		link.mockRestore();
	});

	it("stops the provider redirect when the draft cannot be saved", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "IN_HOUSE_ONLY", aiOptions, links: [slack] });
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () =>
				HttpResponse.json({ detail: "Your choice could not be saved." }, { status: 503 }),
			),
		);
		const link = vi.spyOn(authClient, "linkAccount").mockImplementation(vi.fn());
		renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: CLOUD }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI choice and connect Slack" }));
		await screen.findByText("Your choice could not be saved.");
		expect(link).not.toHaveBeenCalled();
		link.mockRestore();
	});

	it("saves the choice to only the current workspace, then finishes setup past a broken required link", async () => {
		let current: WorkspaceOnboarding = { ...firstVisit, links: [{ ...slack, available: false }] };
		mockWorkspace(current);
		const writes: string[] = [];
		let savedWorkspace: unknown;
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
			http.put(
				"*/workspaces/:workspaceSlug/onboarding/me/ai-choice",
				async ({ request, params }) => {
					savedWorkspace = params.workspaceSlug;
					writes.push(`ai-choice ${JSON.stringify(await request.json())}`);
					// An unavailable required link is the owner's to repair: nothing more is owed here.
					current = { ...current, aiChoice: "NO_AI", needsSetup: false };
					return HttpResponse.json(current);
				},
			),
		);
		const { queryClient, router } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		expect(disabled("Connect Slack")).toBe(true);
		fireEvent.click(screen.getByRole("radio", { name: /^No AI /u }));
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));
		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/user/ada"),
			ROUTE_RENDER_WAIT,
		);
		expect(savedWorkspace).toBe("acme");
		expect(writes).toStrictEqual(['ai-choice {"choice":"NO_AI"}']);
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			)?.needsSetup,
		).toBe(false);
	});

	it("does not send an AI choice when a member chooses Skip for now", async () => {
		mockWorkspace(firstVisit);
		let dismissals = 0;
		let choices = 0;
		let current: WorkspaceOnboarding = firstVisit;
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
			http.put("*/workspaces/acme/onboarding/me/dismissal", () => {
				dismissals += 1;
				current = { ...firstVisit, needsSetup: false };
				return HttpResponse.json(current);
			}),
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () => {
				choices += 1;
				return HttpResponse.json(firstVisit);
			}),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Skip for now" }));
		await waitFor(() => expect(dismissals).toBe(1));
		expect(choices).toBe(0);
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.needsSetup,
			).toBe(false),
		);
	});

	it("leaves a return visit without a write", async () => {
		mockWorkspace({ ...firstVisit, needsSetup: false, aiChoice: "NO_AI" });
		let writes = 0;
		server.use(
			http.put("*/workspaces/acme/onboarding/me/*", () => {
				writes += 1;
				return HttpResponse.json(firstVisit);
			}),
		);
		const { router } = renderRouteAtWithRouter("/w/acme/onboarding?returnTo=%2Fw%2Facme%2Fteams");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Back to workspace" }));
		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/teams"),
			ROUTE_RENDER_WAIT,
		);
		expect(writes).toBe(0);
	});

	it("discards an unsaved choice when navigating to another workspace", async () => {
		mockWorkspace(firstVisit);
		const { router } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: /^No AI /u }));
		expect(checked(/^No AI /u)).toBe(true);
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByText(/start in Other workspace/u, undefined, ROUTE_RENDER_WAIT);
		expect(checked(/^No AI /u)).toBe(false);
	});

	it("keeps the current choice when the server refuses a save", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "IN_HOUSE_ONLY", aiOptions });
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () =>
				HttpResponse.json(
					{ detail: "Only the account owner can make their AI choice." },
					{ status: 403 },
				),
			),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: /^No AI /u }));
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));
		const alert = await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		expect(alert.textContent).toContain("Couldn't save your AI choice");
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			)?.aiChoice,
		).toBe("IN_HOUSE_ONLY");
		expect(checked(/^No AI /u)).toBe(true);
	});
});

describe("onboarding mutations across workspace navigation", () => {
	it("keeps a delayed AI choice in its original workspace", async () => {
		mockWorkspace(firstVisit);
		const { promise: response, resolve: release } = deferred();
		let started = false;
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", async () => {
				started = true;
				await response;
				return HttpResponse.json({ ...firstVisit, workspaceName: "Acme", aiChoice: "NO_AI" });
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: /^No AI /u }));
		fireEvent.click(screen.getByRole("button", { name: "Continue" }));
		await waitFor(() => expect(started).toBe(true));
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByText(/start in Other workspace/u, undefined, ROUTE_RENDER_WAIT);
		await act(async () => {
			release();
		});
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.aiChoice,
			).toBe("NO_AI"),
		);
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "other" } }),
			)?.aiChoice,
		).toBeUndefined();
		expect(checked(/^No AI /u)).toBe(false);
		expect(router.state.location.pathname).toBe("/w/other/onboarding");
	});

	it("does not navigate away when a previous workspace's dismissal finishes", async () => {
		mockWorkspace(firstVisit);
		const { promise: response, resolve: release } = deferred();
		let started = false;
		server.use(
			http.put("*/workspaces/acme/onboarding/me/dismissal", async () => {
				started = true;
				await response;
				return HttpResponse.json({ ...firstVisit, workspaceName: "Acme", needsSetup: false });
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("radio", { name: /^In-house /u }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Skip for now" }));
		await waitFor(() => expect(started).toBe(true));
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByText(/start in Other workspace/u, undefined, ROUTE_RENDER_WAIT);
		await act(async () => {
			release();
		});
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.needsSetup,
			).toBe(false),
		);
		expect(router.state.location.pathname).toBe("/w/other/onboarding");
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "other" } }),
			)?.needsSetup,
		).toBe(true);
	});

	it("keeps delayed owner settings in their original workspace", async () => {
		mockWorkspace(firstVisit);
		const { promise: response, resolve: release } = deferred();
		let started = false;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "OWNER", userId: 20, userLogin: "ada" }),
			),
			http.get("*/workspaces/acme/onboarding/settings", () =>
				HttpResponse.json({
					enabled: false,
					aiChoiceRequired: false,
					revision: 0,
					requiredConnectionIds: [],
				}),
			),
			http.get("*/workspaces/other/onboarding/settings", () =>
				HttpResponse.json({
					enabled: false,
					aiChoiceRequired: false,
					revision: 0,
					requiredConnectionIds: [slack.connectionId],
				}),
			),
			http.get("*/workspaces/:workspaceSlug/onboarding/settings/links", () =>
				HttpResponse.json([slack]),
			),
			http.put("*/workspaces/acme/onboarding/settings", async () => {
				started = true;
				await response;
				return HttpResponse.json({
					enabled: true,
					aiChoiceRequired: true,
					revision: 1,
					requiredConnectionIds: [],
				});
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter("/w/acme/admin/onboarding");
		await screen.findByRole("switch", { name: ASK_ON_FIRST_VISIT }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("switch", { name: ASK_ON_FIRST_VISIT }));
		fireEvent.click(screen.getByRole("button", { name: "Save onboarding settings" }));
		await waitFor(() => expect(started).toBe(true));
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/admin/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await waitFor(() => expect(checkedBox("Slack")).toBe(true));
		await act(async () => {
			release();
		});
		await waitFor(() =>
			expect(
				queryClient.getQueryData(
					getMemberOnboardingSettingsQueryKey({ path: { workspaceSlug: "acme" } }),
				),
			).toMatchObject({ enabled: true, revision: 1 }),
		);
		expect(checkedBox("Slack")).toBe(true);
		expect(checked(ASK_ON_FIRST_VISIT, "switch")).toBe(false);
		expect(
			queryClient.getQueryData(
				getMemberOnboardingSettingsQueryKey({ path: { workspaceSlug: "other" } }),
			),
		).toMatchObject({ enabled: false, requiredConnectionIds: [slack.connectionId] });
	});
});
