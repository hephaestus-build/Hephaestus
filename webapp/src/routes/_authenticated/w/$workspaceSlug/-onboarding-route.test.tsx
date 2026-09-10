import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";

import {
	getMemberOnboardingQueryKey,
	getMemberOnboardingSettingsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { authClient } from "@/integrations/auth/auth-client";
import { currentUser } from "@/mocks/fixtures/auth";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
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
	needsWelcome: true,
} satisfies WorkspaceOnboarding;

describe("workspace member onboarding route", () => {
	it("keeps saved setup and its accounts step when refresh fails, then retries current links", async () => {
		const links = [
			{
				connectionId: 9,
				providerType: "SLACK",
				displayName: "Slack",
				required: true,
				available: true,
				linked: false,
				registrationId: "slack",
			},
		];
		mockWorkspace({ ...firstVisit, links });
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () =>
				HttpResponse.json({ ...firstVisit, aiChoice: "NO_AI", links }),
			),
		);
		renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI preference" }));
		await screen.findByRole("heading", { name: "Connect your workspace accounts" });
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({ status: 503 }, { status: 503 }),
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Refresh connections" }));
		await screen.findByText("Couldn't refresh workspace setup");
		screen.getByRole("heading", { name: "Connect your workspace accounts" });
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Continue to workspace" }).disabled,
		).toBe(true);
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({
					...firstVisit,
					aiChoice: "NO_AI",
					links: links.map((link) => ({ ...link, linked: true })),
				}),
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByText("Connected");
		expect(screen.queryByText("Couldn't refresh workspace setup")).toBeNull();
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Continue to workspace" }).disabled,
		).toBe(false);
	});

	it("updates an untouched saved preference on refetch without overwriting an explicit draft", async () => {
		const aiOptions = [
			{ choice: "ON_PREMISES", practiceReviewsReady: true, mentorReady: true },
			{ choice: "PRIVATE_CLOUD", practiceReviewsReady: true, mentorReady: true },
		] satisfies WorkspaceOnboarding["aiOptions"];
		mockWorkspace({ ...firstVisit, aiChoice: "ON_PREMISES", aiOptions });
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("radio", { name: "On-premises" }).getAttribute("aria-checked")).toBe(
			"true",
		);
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
		await waitFor(() =>
			expect(screen.getByRole("radio", { name: "No AI" }).getAttribute("aria-checked")).toBe(
				"true",
			),
		);
		fireEvent.click(screen.getByRole("radio", { name: "Private cloud" }));
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({ ...firstVisit, aiChoice: "ON_PREMISES", aiOptions }),
			),
		);
		await act(async () => {
			await queryClient.invalidateQueries({
				queryKey: getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			});
		});
		expect(screen.getByRole("radio", { name: "Private cloud" }).getAttribute("aria-checked")).toBe(
			"true",
		);
		expect(
			screen.getByRole<HTMLButtonElement>("button", { name: "Save AI preference" }).disabled,
		).toBe(false);
	});

	it("shows a failed Not now action even when setup could not initially load", async () => {
		mockWorkspace(firstVisit);
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () =>
				HttpResponse.json({ status: 503 }, { status: 503 }),
			),
			http.put("*/workspaces/acme/onboarding/me/dismissal", () =>
				HttpResponse.json(
					{ detail: "Your setup could not be dismissed. Try again." },
					{ status: 503 },
				),
			),
		);
		renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByText("Couldn't load workspace setup", undefined, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Not now" }));
		await screen.findByText("Your setup could not be dismissed. Try again.");
		screen.getByText("Couldn't load workspace setup");
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Not now" }).disabled).toBe(false);
	});

	it.each([
		{ operation: "completion", button: "Continue to workspace", completed: true },
		{ operation: "dismissal", button: "Not now", completed: false },
	])(
		"returns to the original destination after $operation",
		async ({ operation, button, completed }) => {
			let current: WorkspaceOnboarding = { ...firstVisit, aiChoice: "NO_AI" };
			mockWorkspace(current);
			server.use(
				http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
				http.put(`*/workspaces/acme/onboarding/me/${operation}`, () => {
					current = { ...current, needsWelcome: false, completed };
					return HttpResponse.json(current);
				}),
			);
			const destination = "/w/acme/teams?view=mine#feedback";
			const { router } = renderRouteAtWithRouter(
				`/w/acme/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
			);
			await screen.findByRole("heading", { name: "You're ready" }, ROUTE_RENDER_WAIT);
			fireEvent.click(
				await screen.findByRole("button", {
					name: button,
				}),
			);
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
				current = { ...current, needsWelcome: false };
				return HttpResponse.json(current);
			}),
		);
		const { router } = renderRouteAtWithRouter(
			`/w/acme/onboarding?${new URLSearchParams({ returnTo })}`,
		);
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Not now" }));
		// With leaderboards disabled, workspace home opens the signed-in developer’s page.
		await waitFor(
			() => expect(router.state.location.pathname).toBe(`/w/acme/user/${currentUser.username}`),
			ROUTE_RENDER_WAIT,
		);
	});

	it("refreshes changed completion requirements without repeating the write", async () => {
		let current: WorkspaceOnboarding = { ...firstVisit, aiChoice: "NO_AI", revision: 1 };
		mockWorkspace(current);
		const revisions: unknown[] = [];
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
			http.put(
				"*/workspaces/acme/onboarding/me/completion",
				async ({ request }) => {
					revisions.push(await request.json());

					current = { ...current, revision: 2 };
					return HttpResponse.json(
						{
							status: 409,
							detail:
								"Workspace onboarding changed. Review the current requirements and try again.",
						},
						{ status: 409 },
					);
				},
				{ once: true },
			),
			http.put("*/workspaces/acme/onboarding/me/completion", async ({ request }) => {
				revisions.push(await request.json());
				current = { ...current, needsWelcome: false, completed: true };
				return HttpResponse.json(current);
			}),
		);
		const { queryClient, router } = renderRouteAtWithRouter(
			"/w/acme/onboarding?step=accounts&returnTo=%2Fw%2Facme%2Fteams",
		);
		await screen.findByRole("heading", { name: "You're ready" }, ROUTE_RENDER_WAIT);
		fireEvent.click(await screen.findByRole("button", { name: "Continue to workspace" }));
		await screen.findByText(
			"Workspace onboarding changed. Review the current requirements and try again.",
		);
		expect(revisions).toStrictEqual([{ revision: 1 }]);
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			)?.revision,
		).toBe(2);
		fireEvent.click(screen.getByRole("button", { name: "Continue to workspace" }));
		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/teams"),
			ROUTE_RENDER_WAIT,
		);
		expect(revisions).toStrictEqual([{ revision: 1 }, { revision: 2 }]);
	});

	it("preserves the destination and accounts step through provider linking", async () => {
		mockWorkspace({
			...firstVisit,
			aiChoice: "NO_AI",
			links: [
				{
					connectionId: 9,
					providerType: "SLACK",
					displayName: "Slack",
					registrationId: "slack",
					required: true,
					available: true,
					linked: false,
				},
			],
		});
		const link = vi.spyOn(authClient, "linkAccount").mockImplementation(() => {});
		const destination = "/w/acme/teams?view=mine#feedback";
		renderRouteAtWithRouter(
			`/w/acme/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
		);
		await screen.findByRole(
			"heading",
			{ name: "Connect your workspace accounts" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(await screen.findByRole("button", { name: "Connect Slack" }));
		expect(link).toHaveBeenCalledWith(
			"slack",
			`/w/acme/onboarding?${new URLSearchParams({ returnTo: destination, step: "accounts" })}`,
		);
		link.mockRestore();
	});

	it("saves No AI to only the current workspace before required links are completed", async () => {
		const data = {
			...firstVisit,
			links: [
				{
					connectionId: 9,
					providerType: "SLACK",
					displayName: "Slack",
					required: true,
					available: false,
					linked: false,
				},
			],
		};
		mockWorkspace(data);
		let savedBody: unknown;
		let savedWorkspace: unknown;
		server.use(
			http.put(
				"*/workspaces/:workspaceSlug/onboarding/me/ai-choice",
				async ({ request, params }) => {
					savedWorkspace = params.workspaceSlug;
					savedBody = await request.json();
					return HttpResponse.json({ ...data, aiChoice: "NO_AI" });
				},
			),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI preference" }));
		await waitFor(() => expect(savedBody).toStrictEqual({ choice: "NO_AI" }));
		expect(savedWorkspace).toBe("acme");
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.aiChoice,
			).toBe("NO_AI"),
		);
		expect(
			(await screen.findByRole<HTMLButtonElement>("button", { name: "Continue to workspace" }))
				.disabled,
		).toBe(true);
	});
	it("does not send an AI choice when a member chooses Not now", async () => {
		mockWorkspace(firstVisit);
		let dismissals = 0;
		let choices = 0;
		let current: WorkspaceOnboarding = firstVisit;
		server.use(
			http.get("*/workspaces/acme/onboarding/me", () => HttpResponse.json(current)),
			http.put("*/workspaces/acme/onboarding/me/dismissal", () => {
				dismissals++;
				current = { ...firstVisit, needsWelcome: false };
				return HttpResponse.json(current);
			}),
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () => {
				choices++;
				return HttpResponse.json(firstVisit);
			}),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Not now" }));
		await waitFor(() => expect(dismissals).toBe(1));
		expect(choices).toBe(0);
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.needsWelcome,
			).toBe(false),
		);
	});
	it("discards an unsaved choice when navigating to another workspace", async () => {
		mockWorkspace(firstVisit);
		const { router } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		expect(screen.getByRole("radio", { name: "No AI" }).getAttribute("aria-checked")).toBe("true");
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByRole("heading", { name: "Welcome to Other workspace" }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("radio", { name: "No AI" }).getAttribute("aria-checked")).toBe("false");
	});
	it("keeps the current preference when the server refuses a save", async () => {
		mockWorkspace({ ...firstVisit, aiChoice: "ON_PREMISES" });
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", () =>
				HttpResponse.json(
					{ detail: "Only the account owner can choose their AI preference." },
					{ status: 403 },
				),
			),
		);
		const { queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI preference" }));
		await screen.findByRole("alert", undefined, ROUTE_RENDER_WAIT);
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
			)?.aiChoice,
		).toBe("ON_PREMISES");
	});
});

describe("onboarding mutations across workspace navigation", () => {
	it("keeps a delayed AI choice in its original workspace", async () => {
		mockWorkspace(firstVisit);
		let release = () => {};
		const response = new Promise<void>((resolve) => {
			release = resolve;
		});
		let started = false;
		server.use(
			http.put("*/workspaces/acme/onboarding/me/ai-choice", async () => {
				started = true;
				await response;
				return HttpResponse.json({ ...firstVisit, workspaceName: "Acme", aiChoice: "NO_AI" });
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("radio", { name: "No AI" }));
		fireEvent.click(screen.getByRole("button", { name: "Save AI preference" }));
		await waitFor(() => expect(started).toBe(true));
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByRole("heading", { name: "Welcome to Other workspace" }, ROUTE_RENDER_WAIT);
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
		expect(screen.getByRole("radio", { name: "No AI" }).getAttribute("aria-checked")).toBe("false");
	});

	it("does not navigate away when a previous workspace's dismissal finishes", async () => {
		mockWorkspace(firstVisit);
		let release = () => {};
		const response = new Promise<void>((resolve) => {
			release = resolve;
		});
		let started = false;
		server.use(
			http.put("*/workspaces/acme/onboarding/me/dismissal", async () => {
				started = true;
				await response;
				return HttpResponse.json({ ...firstVisit, workspaceName: "Acme", needsWelcome: false });
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter("/w/acme/onboarding");
		await screen.findByRole("heading", { name: "Welcome to Acme" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Not now" }));
		await waitFor(() => expect(started).toBe(true));
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await screen.findByRole("heading", { name: "Welcome to Other workspace" }, ROUTE_RENDER_WAIT);
		await act(async () => {
			release();
		});
		await waitFor(() =>
			expect(
				queryClient.getQueryData<WorkspaceOnboarding>(
					getMemberOnboardingQueryKey({ path: { workspaceSlug: "acme" } }),
				)?.needsWelcome,
			).toBe(false),
		);
		expect(router.state.location.pathname).toBe("/w/other/onboarding");
		expect(
			queryClient.getQueryData<WorkspaceOnboarding>(
				getMemberOnboardingQueryKey({ path: { workspaceSlug: "other" } }),
			)?.needsWelcome,
		).toBe(true);
	});

	it("keeps delayed owner settings in their original workspace", async () => {
		mockWorkspace(firstVisit);
		let release = () => {};
		const response = new Promise<void>((resolve) => {
			release = resolve;
		});
		let started = false;
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "OWNER", userId: 20, userLogin: "ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/onboarding/settings", () =>
				HttpResponse.json({
					enabled: false,
					revision: 0,
					welcomeMarkdown: "Other guidance",
					requiredConnectionIds: [],
				}),
			),
			http.get("*/workspaces/:workspaceSlug/onboarding/settings/links", () =>
				HttpResponse.json([]),
			),
			http.put("*/workspaces/acme/onboarding/settings", async () => {
				started = true;
				await response;
				return HttpResponse.json({
					enabled: true,
					revision: 1,
					welcomeMarkdown: "Acme guidance",
					requiredConnectionIds: [],
				});
			}),
		);
		const { router, queryClient } = renderRouteAtWithRouter("/w/acme/admin/onboarding");
		await screen.findByRole("switch", { name: "Show a welcome on first visit" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("switch", { name: "Show a welcome on first visit" }));
		fireEvent.click(screen.getByRole("button", { name: "Save onboarding settings" }));
		await waitFor(() => expect(started).toBe(true));
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/admin/onboarding",
				params: { workspaceSlug: "other" },
			});
		});
		await waitFor(() =>
			expect(
				screen.getByRole<HTMLTextAreaElement>("textbox", { name: "Welcome from your team" }).value,
			).toBe("Other guidance"),
		);
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
		expect(
			screen.getByRole<HTMLTextAreaElement>("textbox", { name: "Welcome from your team" }).value,
		).toBe("Other guidance");
		expect(
			queryClient.getQueryData(
				getMemberOnboardingSettingsQueryKey({ path: { workspaceSlug: "other" } }),
			),
		).toMatchObject({ enabled: false, welcomeMarkdown: "Other guidance" });
	});
});
