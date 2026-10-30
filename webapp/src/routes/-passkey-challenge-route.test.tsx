import type { QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse, type PathParams } from "msw";
import { expect, it, onTestFinished, vi } from "vitest";

import { client } from "@/api/client.gen";
import { getIntegrationCatalog, requestReview } from "@/api/sdk.gen";
import type { WorkspaceOnboarding } from "@/api/types.gen";
import { currentUser } from "@/mocks/fixtures/auth";
import { workspaceOnboarding } from "@/mocks/fixtures/onboarding";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { authClient } from "@/runtime/auth/auth-client";
import { installPasskeyChallenge } from "@/runtime/auth/passkey-challenge";
import { deferred, nextFrame } from "@/test/async";
import { renderRouteAtWithRouter, ROUTE_RENDER_WAIT } from "@/test/router-harness";

// Mounting the real route pulls in the whole layout and its lazy modules.
vi.setConfig({ testTimeout: 30_000 });

/** The member chose no AI, so the Hephaestus panel stays away and the page makes no Hephaestus reads. */
const NO_CONVERSATION: WorkspaceOnboarding = {
	...workspaceOnboarding(),
	aiChoice: "NO_AI",
	mentorRefusal: "NO_AI",
};

/**
 * An administrator of `acme` and `tutors` whose account also has the instance's administrator role,
 * and who has not verified a passkey: member reads answer, and every administration read is refused
 * as the server refuses it. Refusals in `held.workspaceSlug` wait for `held.until`. Returns each
 * refused read as it arrives, such as "acme catalog".
 */
function serveUnverifiedAdministrator(held?: { workspaceSlug: string; until: Promise<unknown> }) {
	const refused: string[] = [];
	const refuse =
		(read: string) =>
		async ({ params }: { params: PathParams }) => {
			const workspaceSlug = String(params.workspaceSlug);
			refused.push(`${workspaceSlug} ${read}`);
			if (held?.workspaceSlug === workspaceSlug) {
				await held.until;
			}
			return HttpResponse.json(
				{ status: 403, code: "passkey_required", title: "Passkey required" },
				{ status: 403 },
			);
		};
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([
				workspaceListItem("acme", { practicesEnabled: true }),
				workspaceListItem("tutors", { id: 2, practicesEnabled: true }),
			]),
		),
		http.get("*/user/features", () => HttpResponse.json({})),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/onboarding/me", () => HttpResponse.json(NO_CONVERSATION)),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", refuse("catalog")),
		http.get("*/workspaces/:workspaceSlug/practices/reviews/feedback", refuse("feedback")),
	);
	return refused;
}

/** The route at `path`, with the app's handling of passkey refusals on the generated client. */
function renderAt(path: string) {
	const rendered = renderRouteAtWithRouter(path);
	onTestFinished(installPasskeyChallenge(rendered.router));
	return rendered;
}

/** Wait for the mounted chrome's requests, including a refused request, to finish. */
async function settleChrome(queryClient: QueryClient) {
	await screen.findByText("Administration", undefined, ROUTE_RENDER_WAIT);
	await waitFor(() => expect(queryClient.isFetching()).toBe(0));
}

const ACME = { path: { workspaceSlug: "acme" } };

it("keeps an administrator on an ordinary page without requesting protected sidebar data", async () => {
	const refused = serveUnverifiedAdministrator();
	const { router, queryClient } = renderAt("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	await settleChrome(queryClient);
	expect(refused).toStrictEqual([]);
	expect(router.state.location.pathname).toBe("/w/acme/teams");
});

it("does not request protected sidebar data from global user settings", async () => {
	const refused = serveUnverifiedAdministrator();
	const { router, queryClient } = renderAt("/settings");
	await screen.findByRole("heading", { name: "User settings" }, ROUTE_RENDER_WAIT);
	await settleChrome(queryClient);
	expect(refused).toStrictEqual([]);
	expect(router.state.location.pathname).toBe("/settings");
});

/** All administration reads use the same accepted factor, as they do on the server. */
function serveAdministrationReads(accepted: { verifications: number }) {
	const catalog = { refused: 0, served: 0 };
	server.use(
		http.get("*/workspaces/acme/connections/catalog", () => {
			if (accepted.verifications === 0) {
				catalog.refused += 1;
				return HttpResponse.json({ status: 403, code: "passkey_required" }, { status: 403 });
			}
			catalog.served += 1;
			return HttpResponse.json([]);
		}),
		http.get("*/workspaces/acme/practices/reviews/feedback", () =>
			accepted.verifications === 0
				? HttpResponse.json({ status: 403, code: "passkey_required" }, { status: 403 })
				: HttpResponse.json({ content: [], number: 0, size: 5, totalElements: 0, totalPages: 0 }),
		),
	);
	return catalog;
}

it("takes an administrator from a refused administration page to confirm, then back to its reads", async () => {
	serveUnverifiedAdministrator();
	const accepted = servePasskeys("one");
	stubPasskeyPrompt();
	const catalog = serveAdministrationReads(accepted);
	const { router, queryClient } = renderAt("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	await settleChrome(queryClient);
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/admin/integrations",
			params: { workspaceSlug: "acme" },
		});
	});

	await screen.findByRole("heading", { name: "Confirm with your passkey" }, ROUTE_RENDER_WAIT);
	expect(router.state.location.pathname).toBe("/verify-passkey");
	expect(router.state.location.search.returnTo).toBe("/w/acme/admin/integrations");
	expect(catalog.refused).toBeGreaterThan(0);
	await userEvent.click(screen.getByRole("button", { name: "Confirm with passkey" }));

	await waitFor(
		() => expect(router.state.location.pathname).toBe("/w/acme/admin/integrations"),
		ROUTE_RENDER_WAIT,
	);
	await waitFor(() => expect(catalog.served).toBeGreaterThan(0), ROUTE_RENDER_WAIT);
	// Reading the page again keeps working: the confirmation is not a single use.
	const { served, refused } = catalog;
	await act(async () => {
		await queryClient.refetchQueries({ type: "active" });
	});
	expect(catalog.served).toBeGreaterThan(served);
	expect(catalog.refused).toBe(refused);
	expect(router.state.location.pathname).toBe("/w/acme/admin/integrations");
});

const challengeId = "ab265a5b-b502-4a23-b20b-c77e304a58c5";
const assertion = {
	challenge: "Y2hhbGxlbmdl",
	rpId: "localhost",
	allowCredentials: [{ id: "Y3JlZGVudGlhbA", type: "public-key" }],
	userVerification: "required",
};
const creation = {
	challenge: "Y2hhbGxlbmdl",
	rp: { id: "localhost", name: "Hephaestus" },
	user: { id: "dXNlcg", name: "account", displayName: "Account" },
	pubKeyCredParams: [{ type: "public-key", alg: -7 }],
	authenticatorSelection: { residentKey: "required", userVerification: "required" },
};
class BrowserCredential {
	readonly id = "Y3JlZGVudGlhbA";
	static parseCreationOptionsFromJSON(options: unknown) {
		return options;
	}
	static parseRequestOptionsFromJSON(options: unknown) {
		return options;
	}
	toJSON() {
		return { id: this.id, type: "public-key", response: {} };
	}
}

/** The browser's passkey prompt, answered by the developer unless a test says otherwise. */
function stubPasskeyPrompt() {
	const get = vi.fn(async () => new BrowserCredential());
	const create = vi.fn(async () => new BrowserCredential());
	const publicKeyCredential = Object.getOwnPropertyDescriptor(globalThis, "PublicKeyCredential");
	const credentials = Object.getOwnPropertyDescriptor(navigator, "credentials");
	Object.defineProperty(globalThis, "PublicKeyCredential", {
		configurable: true,
		value: BrowserCredential,
	});
	Object.defineProperty(navigator, "credentials", { configurable: true, value: { get, create } });
	onTestFinished(() => {
		if (publicKeyCredential === undefined) {
			Reflect.deleteProperty(globalThis, "PublicKeyCredential");
		} else {
			Object.defineProperty(globalThis, "PublicKeyCredential", publicKeyCredential);
		}
		if (credentials === undefined) {
			Reflect.deleteProperty(navigator, "credentials");
		} else {
			Object.defineProperty(navigator, "credentials", credentials);
		}
	});
	return { get, create };
}

/**
 * The account's passkeys as the server reports them, and what the server accepted. A verification
 * that arrives waits for `held` before the server accepts it.
 */
function servePasskeys(initial: "none" | "one", held?: Promise<unknown>) {
	const accepted = { registrations: 0, verificationsStarted: 0, verifications: 0 };
	let credentials = initial === "one" ? [{ id: "one", label: "Laptop" }] : [];
	server.use(
		http.get("*/user/passkeys", () =>
			HttpResponse.json({
				assuranceEnforced: true,
				protectionEnabled: false,
				recoveryRequired: false,
				instanceAdminRequired: true,
				workspaceAdminRequired: false,
				verified: accepted.verifications > 0,
				credentials,
			}),
		),
		http.post("*/user/passkeys/registration-options", () =>
			HttpResponse.json({ challengeId, optionsJson: JSON.stringify(creation) }),
		),
		http.post("*/user/passkeys", () => {
			accepted.registrations += 1;
			credentials = [{ id: "one", label: "Passkey added today" }];
			return new HttpResponse(null, { status: 204 });
		}),
		http.post("*/user/passkeys/verification-options", () =>
			HttpResponse.json({ challengeId, optionsJson: JSON.stringify(assertion) }),
		),
		http.post("*/user/passkeys/verification", async () => {
			accepted.verificationsStarted += 1;
			if (held !== undefined) {
				await held;
			}
			accepted.verifications += 1;
			return new HttpResponse(null, { status: 204 });
		}),
	);
	return accepted;
}

/**
 * On `path`, a request of that page that the server refuses until a passkey confirms the session:
 * by default an administration read.
 */
async function refusedOn(
	path: string,
	refusedRequest: () => Promise<unknown> = async () => getIntegrationCatalog(ACME),
) {
	const rendered = renderAt(path);
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	await settleChrome(rendered.queryClient);
	await refusedRequest();
	await waitFor(
		() => expect(rendered.router.state.location.pathname).toBe("/verify-passkey"),
		ROUTE_RENDER_WAIT,
	);
	return rendered;
}

it("returns to the exact page, search and fragment without sending the refused change again", async () => {
	serveUnverifiedAdministrator();
	const accepted = servePasskeys("one");
	const prompt = stubPasskeyPrompt();
	let reviewRequests = 0;
	server.use(
		http.post("*/workspaces/:workspaceSlug/practices/review-requests", () => {
			reviewRequests += 1;
			return HttpResponse.json({ status: 403, code: "passkey_required" }, { status: 403 });
		}),
	);
	const { router } = await refusedOn("/w/acme/teams?view=all#members", async () =>
		requestReview({ ...ACME, body: { itemId: "1" } }),
	);
	expect(reviewRequests).toBe(1);

	await userEvent.click(
		await screen.findByRole("button", { name: "Confirm with passkey" }, ROUTE_RENDER_WAIT),
	);

	await waitFor(
		() => expect(router.state.location.href).toBe("/w/acme/teams?view=all#members"),
		ROUTE_RENDER_WAIT,
	);
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	expect(prompt.get).toHaveBeenCalledOnce();
	expect(accepted.verifications).toBe(1);
	// The developer makes the change again themselves; nothing resends the refused one.
	expect(reviewRequests).toBe(1);
	// The challenge replaced the refused entry: Back does not lead into the challenge again.
	expect(router.history).toHaveLength(1);
});

it("leaves a challenge for the page before the refused one, in the workspace the developer was in", async () => {
	serveUnverifiedAdministrator();
	servePasskeys("one");
	const { router, queryClient } = renderAt("/w/tutors/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	await settleChrome(queryClient);
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/admin/integrations",
			params: { workspaceSlug: "tutors" },
		});
	});
	await screen.findByRole("heading", { name: "Confirm with your passkey" }, ROUTE_RENDER_WAIT);

	await userEvent.click(screen.getByRole("button", { name: "Not now" }));

	// Not the refused administration page, and not the first workspace: the page before it.
	await waitFor(
		() => expect(router.state.location.pathname).toBe("/w/tutors/teams"),
		ROUTE_RENDER_WAIT,
	);
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
});

it("leaves a challenge with no earlier page for the home of the workspace it was asked from", async () => {
	serveUnverifiedAdministrator();
	servePasskeys("one");
	const { router } = renderAt(
		`/verify-passkey?returnTo=${encodeURIComponent("/w/tutors/admin/integrations?tab=slack")}`,
	);

	await userEvent.click(await screen.findByRole("button", { name: "Not now" }, ROUTE_RENDER_WAIT));

	await waitFor(
		() => expect(router.state.location.pathname).toBe("/w/tutors/practice-profile"),
		ROUTE_RENDER_WAIT,
	);
});

it("brings a recent sign-in before a first passkey back to the exact challenge, adding nothing by itself", async () => {
	serveUnverifiedAdministrator();
	const accepted = servePasskeys("none");
	const prompt = stubPasskeyPrompt();
	let registrationOptions = 0;
	server.use(
		http.post("*/user/passkeys/registration-options", () => {
			registrationOptions += 1;
			return HttpResponse.json(
				{ status: 403, code: "step_up_required", maxAgeSeconds: 300 },
				{ status: 403 },
			);
		}),
		http.get("*/identity-providers", () =>
			HttpResponse.json([
				{
					registrationId: "github",
					displayName: "GitHub",
					providerType: "GITHUB",
					baseUrl: "https://github.com",
				},
			]),
		),
		http.get("*/user", () =>
			HttpResponse.json({
				...currentUser,
				linkedProviders: [{ type: "GITHUB", serverUrl: "https://github.com" }],
			}),
		),
	);
	const login = vi.spyOn(authClient, "login").mockReturnValue(undefined);
	onTestFinished(() => login.mockRestore());
	const { router } = renderAt(
		`/verify-passkey?returnTo=${encodeURIComponent("/w/acme/admin/integrations?tab=slack")}`,
	);

	await userEvent.click(
		await screen.findByRole("button", { name: "Add passkey" }, ROUTE_RENDER_WAIT),
	);
	await userEvent.click(
		await screen.findByRole("button", { name: "Continue with GitHub" }, ROUTE_RENDER_WAIT),
	);

	expect(login).toHaveBeenCalledExactlyOnceWith("github", router.state.location.href);
	const signedInTo = new URL(String(login.mock.calls[0]?.[1]), "http://app.test");
	expect(signedInTo.pathname).toBe("/verify-passkey");
	expect(signedInTo.searchParams.get("returnTo")).toBe("/w/acme/admin/integrations?tab=slack");
	// The refused enrollment waits for the developer: no browser prompt, and nothing resent.
	expect(registrationOptions).toBe(1);
	expect(prompt.create).not.toHaveBeenCalled();
	expect(accepted.registrations).toBe(0);
});

it("leaves the developer where they went when a confirmation they left finishes later", async () => {
	const held = deferred();
	serveUnverifiedAdministrator();
	const accepted = servePasskeys("one", held.promise);
	stubPasskeyPrompt();
	const { router, queryClient } = await refusedOn("/w/tutors/teams");
	await userEvent.click(
		await screen.findByRole("button", { name: "Confirm with passkey" }, ROUTE_RENDER_WAIT),
	);
	await waitFor(() => expect(accepted.verificationsStarted).toBe(1), ROUTE_RENDER_WAIT);

	await userEvent.click(screen.getByRole("button", { name: "Not now" }));
	await waitFor(
		() => expect(router.state.location.pathname).toBe("/w/tutors/practice-profile"),
		ROUTE_RENDER_WAIT,
	);
	held.resolve();
	await waitFor(() => expect(accepted.verifications).toBe(1), ROUTE_RENDER_WAIT);
	await waitFor(() => expect(queryClient.isMutating()).toBe(0), ROUTE_RENDER_WAIT);
	await act(async () => {
		await nextFrame();
	});

	// The late acceptance stands, but it does not take the developer back to the page they left.
	expect(router.state.location.pathname).toBe("/w/tutors/practice-profile");
});

it("does not continue an earlier confirmation to the destination of a newer challenge", async () => {
	const held = deferred();
	serveUnverifiedAdministrator();
	const accepted = servePasskeys("one", held.promise);
	stubPasskeyPrompt();
	const { router, queryClient } = await refusedOn("/w/acme/teams");
	await userEvent.click(
		await screen.findByRole("button", { name: "Confirm with passkey" }, ROUTE_RENDER_WAIT),
	);
	await waitFor(() => expect(accepted.verificationsStarted).toBe(1), ROUTE_RENDER_WAIT);

	await act(async () => {
		await router.navigate({ to: "/verify-passkey", search: { returnTo: "/w/tutors/teams" } });
	});
	held.resolve();
	await waitFor(() => expect(accepted.verifications).toBe(1), ROUTE_RENDER_WAIT);
	await waitFor(() => expect(queryClient.isMutating()).toBe(0), ROUTE_RENDER_WAIT);
	await act(async () => {
		await nextFrame();
	});

	expect(router.state.location.pathname).toBe("/verify-passkey");
	expect(router.state.location.search.returnTo).toBe("/w/tutors/teams");
});

it("adds a first passkey without a name, then asks for the confirmation it still needs", async () => {
	serveUnverifiedAdministrator();
	const accepted = servePasskeys("none");
	const prompt = stubPasskeyPrompt();
	const { router } = await refusedOn("/w/acme/teams");
	await screen.findByRole("heading", { name: "Add a passkey to continue" }, ROUTE_RENDER_WAIT);

	await userEvent.click(screen.getByRole("button", { name: "Add passkey" }));

	await screen.findByText(
		"Your passkey is saved. Confirm with it once to continue.",
		undefined,
		ROUTE_RENDER_WAIT,
	);
	expect(accepted.registrations).toBe(1);
	// Saving a passkey confirms nothing: the developer stays until they confirm with it.
	expect(prompt.get).not.toHaveBeenCalled();
	expect(router.state.location.pathname).toBe("/verify-passkey");

	await userEvent.click(screen.getByRole("button", { name: "Confirm with passkey" }));
	await waitFor(
		() => expect(router.state.location.pathname).toBe("/w/acme/teams"),
		ROUTE_RENDER_WAIT,
	);
});

it("keeps the developer on the challenge with a retry when the passkey prompt does not finish", async () => {
	serveUnverifiedAdministrator();
	const accepted = servePasskeys("one");
	const prompt = stubPasskeyPrompt();
	prompt.get.mockRejectedValueOnce(new DOMException("Canceled", "NotAllowedError"));
	const { router } = await refusedOn("/w/acme/teams");

	await userEvent.click(
		await screen.findByRole("button", { name: "Confirm with passkey" }, ROUTE_RENDER_WAIT),
	);

	const message = await screen.findByText(
		"The passkey check did not finish. Try again when you’re ready.",
		undefined,
		ROUTE_RENDER_WAIT,
	);
	expect(message.closest("[aria-live]")?.getAttribute("aria-live")).toBe("assertive");
	expect(accepted.verifications).toBe(0);
	expect(router.state.location.pathname).toBe("/verify-passkey");
	expect(
		screen.getByRole<HTMLButtonElement>("button", { name: "Confirm with passkey" }).disabled,
	).toBe(false);
});

it("goes home instead of to another site after a confirmation", async () => {
	serveUnverifiedAdministrator();
	servePasskeys("one");
	stubPasskeyPrompt();
	const { router } = renderAt(
		`/verify-passkey?returnTo=${encodeURIComponent("//evil.example/w/acme")}`,
	);

	await userEvent.click(
		await screen.findByRole("button", { name: "Confirm with passkey" }, ROUTE_RENDER_WAIT),
	);

	// Home sends a member to their first workspace’s practice profile.
	await waitFor(
		() => expect(router.state.location.pathname).toBe("/w/acme/practice-profile"),
		ROUTE_RENDER_WAIT,
	);
});

it("leaves the developer on the workspace they switched to when a refusal of the page they left arrives late", async () => {
	const held = deferred();
	const refused = serveUnverifiedAdministrator({ workspaceSlug: "acme", until: held.promise });
	const { router, queryClient } = renderAt("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	await settleChrome(queryClient);
	expect(refused).toStrictEqual([]);
	const reads = refused.length;

	// A request the page itself makes, such as an administration action, that the server refuses.
	// Its arrival at the server means the app saw it leave this page.
	const request = getIntegrationCatalog(ACME);
	await waitFor(() => expect(refused).toHaveLength(reads + 1));
	await act(async () => {
		await router.navigate({
			to: "/w/$workspaceSlug/teams",
			params: { workspaceSlug: "tutors" },
		});
	});
	held.resolve();
	const { response } = await request;

	expect(response?.status).toBe(403);
	expect(router.state.location.pathname).toBe("/w/tutors/teams");
	await screen.findByRole("heading", { name: "Teams" });
});

it("leaves the current workspace intact when the old page changes while its response body is arriving", async () => {
	serveUnverifiedAdministrator();
	const headers = deferred();
	const finishBody = deferred();
	onTestFinished(() => finishBody.resolve());
	server.use(
		http.get("*/workspaces/acme/connections/catalog", () => {
			const body = new ReadableStream<Uint8Array>({
				start(controller) {
					void finishBody.promise.then(() => {
						controller.enqueue(
							new TextEncoder().encode(JSON.stringify({ code: "passkey_required" })),
						);
						controller.close();
					});
				},
			});
			return new HttpResponse(body, {
				status: 403,
				headers: { "Content-Type": "application/problem+json", "X-Test-Delayed-Body": "passkey" },
			});
		}),
	);
	const { router, queryClient } = renderRouteAtWithRouter("/w/acme/teams");
	await screen.findByRole("heading", { name: "Teams" }, ROUTE_RENDER_WAIT);
	await settleChrome(queryClient);
	// Only the request below remains: observe its headers before the passkey interceptor reads its body.
	const observing = client.interceptors.response.use((response) => {
		headers.resolve();
		return response;
	});
	onTestFinished(() => client.interceptors.response.eject(observing));
	onTestFinished(installPasskeyChallenge(router));
	const request = getIntegrationCatalog(ACME);
	await headers.promise;
	// Flush handling of the received headers while the real streamed JSON body stays open.
	await act(async () => {
		await Promise.resolve();
	});
	await act(async () => {
		await router.navigate({ to: "/w/$workspaceSlug/teams", params: { workspaceSlug: "tutors" } });
	});
	finishBody.resolve();
	const { response } = await request;
	expect(response?.status).toBe(403);
	expect(router.state.location.pathname).toBe("/w/tutors/teams");
	await screen.findByRole("heading", { name: "Teams" });
});
