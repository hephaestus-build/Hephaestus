import type { QueryClient } from "@tanstack/react-query";
import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { adminListLoginProvidersQueryKey } from "@/api/@tanstack/react-query.gen";
import type { LoginProviderView } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { problemType } from "@/lib/problem-detail";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

function provider(registrationId: string, displayName: string): LoginProviderView {
	return {
		registrationId,
		displayName,
		type: "oidc",
		baseUrl: `https://${registrationId}.example.test`,
		redirectUri: `https://app.example.test/login/oauth2/code/${registrationId}`,
		scopes: "openid profile",
		enabled: true,
		createdAt: new Date("2026-07-01T00:00:00Z"),
		updatedAt: new Date("2026-07-01T00:00:00Z"),
	};
}

function renderLoginProvidersRoute() {
	renderRouteAt("/admin/login-providers");
}

describe("instance login providers route", () => {
	it("keeps each provider's toggle pending independently when two run at once", async () => {
		const slowToggle = deferred();
		let slowToggleCalls = 0;
		const providers = [provider("github", "GitHub"), provider("gitlab", "GitLab")];
		server.use(
			http.get("*/admin/login-providers", () => HttpResponse.json(providers)),
			http.patch("*/admin/login-providers/github", async () => {
				slowToggleCalls += 1;
				await slowToggle.promise;
				return HttpResponse.json({ ...providers[0], enabled: false });
			}),
			http.patch("*/admin/login-providers/gitlab", () =>
				HttpResponse.json({ ...providers[1], enabled: false }),
			),
		);

		renderLoginProvidersRoute();

		fireEvent.click(
			await screen.findByRole("switch", { name: "Disable GitHub" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(slowToggleCalls).toBe(1));
		fireEvent.click(screen.getByRole("switch", { name: "Disable GitLab" }));
		await waitFor(() =>
			expect(screen.getByRole("switch", { name: "Disable GitLab" }).getAttribute("aria-busy")).toBe(
				"false",
			),
		);

		expect(screen.getByRole("switch", { name: "Disable GitHub" }).getAttribute("aria-busy")).toBe(
			"true",
		);
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Delete GitHub" }).disabled).toBe(
			true,
		);
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Delete GitLab" }).disabled).toBe(
			false,
		);

		slowToggle.resolve();
		await waitFor(() =>
			expect(screen.getByRole("switch", { name: "Disable GitHub" }).getAttribute("aria-busy")).toBe(
				"false",
			),
		);
	});

	it("asks for a fresh sign-in when a provider change is refused, and recovers its own load failure", async () => {
		let changes = 0;
		server.use(
			http.get("*/admin/login-providers", () =>
				HttpResponse.json([provider("gitlab", "Team GitLab")]),
			),
			http.patch("*/admin/login-providers/gitlab", () => {
				changes += 1;
				return HttpResponse.json(
					{ status: 403, type: problemType("step-up-required"), maxAgeSeconds: 300 },
					{ status: 403 },
				);
			}),
			http.get("*/identity-providers", () => HttpResponse.json({}, { status: 503 })),
		);

		renderLoginProvidersRoute();
		fireEvent.click(
			await screen.findByRole("switch", { name: "Disable Team GitLab" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByRole("dialog", { name: "Confirm access" });
		const alert = await screen.findByRole("alert");
		expect(alert.textContent).toContain("We could not load the sign-in options");

		server.use(
			http.get("*/identity-providers", () =>
				HttpResponse.json([
					{
						registrationId: "gitlab",
						providerType: "GITLAB",
						displayName: "Team GitLab",
						baseUrl: "https://gitlab.lrz.de",
					},
				]),
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Retry" }));
		await expect(
			screen.findByRole("button", { name: "Continue with Team GitLab" }),
		).resolves.not.toBeNull();

		fireEvent.click(screen.getByRole("button", { name: "Close" }));
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull(),
		);
		// The refused change is abandoned, not replayed, and the row still shows the server's state.
		expect(changes).toBe(1);
		expect(
			screen.getByRole("switch", { name: "Disable Team GitLab" }).getAttribute("aria-checked"),
		).toBe("true");
	});
});

const github = {
	registrationId: "github",
	type: "GITHUB",
	displayName: "GitHub",
	baseUrl: "https://github.com",
	redirectUri: "https://hephaestus.example.test/api/login/oauth2/code/github",
	scopes: "read:user user:email",
	enabled: true,
	seededFromEnv: false,
	createdAt: "2026-07-01T00:00:00Z",
	updatedAt: "2026-07-01T00:00:00Z",
} satisfies Wire<LoginProviderView>;

const acmeGitLab = {
	registrationId: "gitlab-acme",
	type: "GITLAB",
	displayName: "ACME GitLab",
	baseUrl: "https://gitlab.acme.test",
	redirectUri: "https://hephaestus.example.test/api/login/oauth2/code/gitlab-acme",
	scopes: "read_user",
	enabled: true,
	seededFromEnv: false,
	createdAt: "2026-07-01T00:00:00Z",
	updatedAt: "2026-07-01T00:00:00Z",
} satisfies Wire<LoginProviderView>;

/** The server's own refusal body for a change that needs a recent sign-in. */
const STEP_UP_REQUIRED = {
	title: "Confirm access",
	status: 403,
	detail: "This action requires a recent sign-in. Sign in again to confirm access.",
	type: problemType("step-up-required"),
	maxAgeSeconds: 300,
};

interface ProviderWrite {
	method: string;
	path: string;
	body?: unknown;
}

async function recordWrite(writes: ProviderWrite[], request: Request) {
	const path = new URL(request.url).pathname.replace(/^.*(?=\/admin\/)/u, "");
	const body: unknown = request.method === "DELETE" ? undefined : await request.json();
	writes.push(
		body === undefined ? { method: request.method, path } : { method: request.method, path, body },
	);
}

/** Serves the provider list from whatever `answer` returns now, and records each status read. */
function serveProviderList(answer: () => Response) {
	const reads: number[] = [];
	server.use(
		http.get("*/admin/login-providers", () => {
			const response = answer();
			reads.push(response.status);
			return response;
		}),
	);
	return reads;
}

function refusal(status: number) {
	return HttpResponse.json(
		{ status, detail: "Login providers are not available to you." },
		{ status },
	);
}

async function rereadProviders(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({
			queryKey: adminListLoginProvidersQueryKey(),
			exact: true,
		});
	});
}

function fillCreateForm(form: HTMLElement, registrationId: string) {
	fireEvent.change(within(form).getByLabelText("Registration ID"), {
		target: { value: registrationId },
	});
	fireEvent.change(within(form).getByLabelText("Instance base URL"), {
		target: { value: `https://${registrationId}.example.test` },
	});
	fireEvent.change(within(form).getByLabelText("Client ID"), {
		target: { value: `${registrationId}-client` },
	});
	fireEvent.change(within(form).getByLabelText("Client secret"), {
		target: { value: `${registrationId}-secret` },
	});
}

/** A closing dialog stays mounted for its exit transition, so open is Base UI's `data-open` state. */
function isOpen(name: string) {
	return screen.queryByRole("dialog", { name })?.dataset.open !== undefined;
}

async function openCreateForm() {
	fireEvent.click(screen.getByRole("button", { name: "Add provider" }));
	return screen.findByRole("dialog", { name: "Add login provider" });
}

async function dismissForm(form: HTMLElement, name: string) {
	fireEvent.click(within(form).getByRole("button", { name: "Cancel" }));
	await waitFor(() => expect(screen.queryByRole("dialog", { name })).toBeNull());
}

const answerBothProviders = () => HttpResponse.json([github, acmeGitLab]);

describe("instance login provider dialogs", () => {
	it("keeps another provider's open edit when a row toggle settles, and closes it on its own save", async () => {
		let githubRow: Wire<LoginProviderView> = github;
		let acmeRow: Wire<LoginProviderView> = acmeGitLab;
		let toggle = deferred();
		const writes: ProviderWrite[] = [];
		serveProviderList(() => HttpResponse.json([githubRow, acmeRow]));
		server.use(
			http.patch("*/admin/login-providers/github", async ({ request }) => {
				await recordWrite(writes, request);
				await toggle.promise;
				githubRow = { ...githubRow, enabled: githubRow.enabled !== true };
				return HttpResponse.json(githubRow);
			}),
			http.patch("*/admin/login-providers/gitlab-acme", async ({ request }) => {
				await recordWrite(writes, request);
				acmeRow = { ...acmeRow, displayName: "ACME GitLab EU" };
				return HttpResponse.json(acmeRow);
			}),
		);

		renderLoginProvidersRoute();
		fireEvent.click(
			await screen.findByRole("switch", { name: "Disable GitHub" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() => expect(writes).toHaveLength(1));
		fireEvent.click(screen.getByRole("button", { name: "Edit ACME GitLab" }));
		const form = await screen.findByRole("dialog", { name: "Edit login provider" });
		fireEvent.change(within(form).getByLabelText("Display name"), {
			target: { value: "ACME GitLab EU" },
		});

		toggle.resolve();
		await screen.findByRole("switch", { name: "Enable GitHub", hidden: true });
		expect(
			isOpen("Edit login provider"),
			"The settled toggle leaves the other provider's form open",
		).toBe(true);
		expect(within(form).getByLabelText<HTMLInputElement>("Display name").value).toBe(
			"ACME GitLab EU",
		);
		expect(writes).toStrictEqual([
			{ method: "PATCH", path: "/admin/login-providers/github", body: { enabled: false } },
		]);

		fireEvent.click(within(form).getByRole("button", { name: "Save changes" }));
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Edit login provider" })).toBeNull(),
		);
		await screen.findByRole("button", { name: "Edit ACME GitLab EU" });
		expect(writes.at(-1)).toStrictEqual({
			method: "PATCH",
			path: "/admin/login-providers/gitlab-acme",
			body: {
				displayName: "ACME GitLab EU",
				baseUrl: "https://gitlab.acme.test",
				scopes: "read_user",
			},
		});

		// A toggle in flight on one row does not hold another provider's form.
		toggle = deferred();
		fireEvent.click(screen.getByRole("switch", { name: "Enable GitHub" }));
		await waitFor(() => expect(writes).toHaveLength(3));
		fireEvent.click(screen.getByRole("button", { name: "Edit ACME GitLab EU" }));
		const next = await screen.findByRole("dialog", { name: "Edit login provider" });
		expect(
			within(next).getByRole<HTMLButtonElement>("button", { name: "Save changes" }).disabled,
		).toBe(false);
		await dismissForm(next, "Edit login provider");
		toggle.resolve();
		await screen.findByRole("switch", { name: "Disable GitHub" });
		expect(writes.map(({ path }) => path)).toStrictEqual([
			"/admin/login-providers/github",
			"/admin/login-providers/gitlab-acme",
			"/admin/login-providers/github",
		]);
	});

	it("settles a save against the form that sent it, never a form opened since", async () => {
		let listed: Wire<LoginProviderView>[] = [github];
		const answers = [
			deferred<Response>(),
			deferred<Response>(),
			deferred<Response>(),
			deferred<Response>(),
		] as const;
		const writes: ProviderWrite[] = [];
		serveProviderList(() => HttpResponse.json(listed));
		// Each create is answered, in order, by its own held response.
		server.use(
			...answers.map((answer) =>
				http.post(
					"*/admin/login-providers",
					async ({ request }) => {
						await recordWrite(writes, request);
						return answer.promise;
					},
					{ once: true },
				),
			),
		);

		renderLoginProvidersRoute();
		await screen.findByRole("switch", { name: "Disable GitHub" }, ROUTE_RENDER_WAIT);

		// The first form is sent, then dismissed while its save is out (ADR 0027).
		const first = await openCreateForm();
		fillCreateForm(first, "gitlab-acme");
		fireEvent.click(within(first).getByRole("button", { name: "Add provider" }));
		await waitFor(() => expect(writes).toHaveLength(1));
		await dismissForm(first, "Add login provider");

		// The form opened next saves on its own while the dismissed save is still out, and closes.
		const second = await openCreateForm();
		fillCreateForm(second, "outline-acme");
		const save = within(second).getByRole<HTMLButtonElement>("button", { name: "Add provider" });
		expect(save.disabled, "A dismissed save does not hold the form opened since").toBe(false);
		fireEvent.click(save);
		await waitFor(() => expect(writes).toHaveLength(2));
		await waitFor(() => expect(save.disabled, "A form's own save holds it").toBe(true));
		const outlineRow = {
			...acmeGitLab,
			registrationId: "outline-acme",
			displayName: "ACME Outline",
		};
		listed = [github, outlineRow];
		answers[1].resolve(HttpResponse.json(outlineRow, { status: 201 }));
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Add login provider" })).toBeNull(),
		);
		await screen.findByRole("switch", { name: "Disable ACME Outline" });

		// The dismissed save settling later leaves the draft opened since.
		const third = await openCreateForm();
		fireEvent.change(within(third).getByLabelText("Registration ID"), {
			target: { value: "gitlab-lab" },
		});
		listed = [github, acmeGitLab, outlineRow];
		answers[0].resolve(HttpResponse.json(acmeGitLab, { status: 201 }));
		await screen.findByRole("switch", { name: "Disable ACME GitLab", hidden: true });
		expect(isOpen("Add login provider"), "The first save leaves the form opened since").toBe(true);
		expect(within(third).getByLabelText<HTMLInputElement>("Registration ID").value).toBe(
			"gitlab-lab",
		);

		// A dismissed save that needs a fresh sign-in does not take the place of the form on screen.
		fillCreateForm(third, "gitlab-lab");
		fireEvent.click(within(third).getByRole("button", { name: "Add provider" }));
		await waitFor(() => expect(writes).toHaveLength(3));
		await dismissForm(third, "Add login provider");
		const fourth = await openCreateForm();
		fireEvent.change(within(fourth).getByLabelText("Registration ID"), {
			target: { value: "gitlab-dev" },
		});
		answers[2].resolve(HttpResponse.json(STEP_UP_REQUIRED, { status: 403 }));
		await screen.findByText(STEP_UP_REQUIRED.detail);
		expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull();
		expect(isOpen("Add login provider")).toBe(true);
		expect(within(fourth).getByLabelText<HTMLInputElement>("Registration ID").value).toBe(
			"gitlab-dev",
		);

		// The form's own refusal replaces it with the ask, and the refused save is not sent again.
		fillCreateForm(fourth, "gitlab-dev");
		fireEvent.click(within(fourth).getByRole("button", { name: "Add provider" }));
		await waitFor(() => expect(writes).toHaveLength(4));
		answers[3].resolve(HttpResponse.json(STEP_UP_REQUIRED, { status: 403 }));
		const ask = await screen.findByRole("dialog", { name: "Confirm access" });
		expect(screen.queryByRole("dialog", { name: "Add login provider" })).toBeNull();
		fireEvent.click(within(ask).getByRole("button", { name: "Close" }));
		await waitFor(() =>
			expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull(),
		);
		expect(writes).toStrictEqual(
			["gitlab-acme", "outline-acme", "gitlab-lab", "gitlab-dev"].map((registrationId) => ({
				method: "POST",
				path: "/admin/login-providers",
				body: {
					registrationId,
					type: "GITLAB",
					baseUrl: `https://${registrationId}.example.test`,
					clientId: `${registrationId}-client`,
					clientSecret: `${registrationId}-secret`,
				},
			})),
		);
	});

	it("withdraws an open edit when the provider list is refused, and does not bring it back", async () => {
		let answer = answerBothProviders;
		const reads = serveProviderList(() => answer());
		const writes: ProviderWrite[] = [];
		server.use(
			http.all("*/admin/login-providers/:registrationId", async ({ request }) => {
				await recordWrite(writes, request);
				return HttpResponse.json(acmeGitLab);
			}),
		);

		const queryClient = renderRouteAt("/admin/login-providers");
		fireEvent.click(
			await screen.findByRole("button", { name: "Edit ACME GitLab" }, ROUTE_RENDER_WAIT),
		);
		const form = await screen.findByRole("dialog", { name: "Edit login provider" });
		fireEvent.change(within(form).getByLabelText("Display name"), {
			target: { value: "ACME GitLab EU" },
		});

		answer = () => refusal(403);
		await rereadProviders(queryClient);
		await screen.findByText("You do not have access to login providers");
		await waitFor(() =>
			expect(
				screen.queryByRole("dialog", { name: "Edit login provider" }),
				"A refused list takes back the open edit",
			).toBeNull(),
		);

		answer = () => refusal(503);
		await rereadProviders(queryClient);
		await screen.findByText("We could not load login providers");
		expect(screen.queryByText("ACME GitLab")).toBeNull();
		expect(screen.queryByRole("dialog")).toBeNull();

		answer = () => HttpResponse.json([github, acmeGitLab]);
		await rereadProviders(queryClient);
		await screen.findByRole("button", { name: "Edit ACME GitLab" });
		expect(screen.queryByRole("dialog")).toBeNull();

		// A provider the admin is still writing is their own draft, not data the refused read took back.
		const draft = await openCreateForm();
		fireEvent.change(within(draft).getByLabelText("Registration ID"), {
			target: { value: "outline-acme" },
		});
		answer = () => refusal(403);
		await rereadProviders(queryClient);
		await screen.findByText("You do not have access to login providers");
		expect(isOpen("Add login provider")).toBe(true);
		expect(within(draft).getByLabelText<HTMLInputElement>("Registration ID").value).toBe(
			"outline-acme",
		);

		expect(reads).toStrictEqual([200, 403, 503, 200, 403]);
		expect(writes).toStrictEqual([]);
	});

	it("drops a refused provider's delete confirm, and keeps an open one on the current row", async () => {
		let answer = () => HttpResponse.json([github, acmeGitLab]);
		const reads = serveProviderList(() => answer());
		const writes: ProviderWrite[] = [];
		server.use(
			http.all("*/admin/login-providers/:registrationId", async ({ request }) => {
				await recordWrite(writes, request);
				answer = () => HttpResponse.json([github]);
				return new HttpResponse(null, { status: 204 });
			}),
		);

		const queryClient = renderRouteAt("/admin/login-providers");
		fireEvent.click(
			await screen.findByRole("button", { name: "Delete ACME GitLab" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByRole("alertdialog", { name: "Delete login provider “ACME GitLab”?" });

		answer = () => refusal(403);
		await rereadProviders(queryClient);
		await screen.findByText("You do not have access to login providers");
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

		answer = () => refusal(503);
		await rereadProviders(queryClient);
		await screen.findByText("We could not load login providers");
		expect(screen.queryByText("ACME GitLab")).toBeNull();

		const renamed = { ...acmeGitLab, displayName: "ACME GitLab EU" };
		answer = () => HttpResponse.json([github, renamed]);
		await rereadProviders(queryClient);
		await screen.findByRole("button", { name: "Delete ACME GitLab EU", hidden: true });
		expect(
			screen.queryByRole("alertdialog"),
			"A later read does not reopen the refused delete confirm",
		).toBeNull();

		fireEvent.click(screen.getByRole("button", { name: "Delete ACME GitLab EU" }));
		await screen.findByRole("alertdialog", { name: "Delete login provider “ACME GitLab EU”?" });
		answer = () => HttpResponse.json([github, { ...acmeGitLab, displayName: "ACME GitLab West" }]);
		await rereadProviders(queryClient);
		const confirm = await screen.findByRole("alertdialog", {
			name: "Delete login provider “ACME GitLab West”?",
		});
		fireEvent.click(within(confirm).getByRole("button", { name: "Delete provider" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		await waitFor(() => expect(screen.queryByText("ACME GitLab West")).toBeNull());

		expect(reads.slice(0, 5)).toStrictEqual([200, 403, 503, 200, 200]);
		expect(writes).toStrictEqual([
			{ method: "DELETE", path: "/admin/login-providers/gitlab-acme" },
		]);
	});
});
