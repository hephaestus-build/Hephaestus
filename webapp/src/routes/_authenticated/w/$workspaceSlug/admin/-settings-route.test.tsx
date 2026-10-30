import type { QueryClient, QueryKey } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type JsonBodyType, type PathParams } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
	getPublicActivityHiddenCountQueryKey,
	getWorkspaceQueryKey,
	getPublicActivityPolicyQueryKey,
	listWorkspacesQueryKey,
	getWorkspacePublicActivitySettingsQueryKey,
	listHiddenContributorsQueryKey,
} from "@/api/@tanstack/react-query.gen";
import type { UpdateWorkspacePublicActivityRequest } from "@/api/types.gen";
import { currentUser } from "@/mocks/fixtures/auth";
import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import {
	ROUTE_RENDER_WAIT,
	renderRouteAt,
	renderRouteAtWithRouter,
	testQueryClient,
} from "@/test/router-harness";
import { switchWorkspace } from "@/test/workspace-switcher";

// Mounting the real route pulls in the whole admin layout and its lazy modules; the timeout is a
// deadlock backstop, not a budget these renders were meant to fit inside.
vi.setConfig({ testTimeout: 15_000 });

function renderSettingsRoute(practicesEnabled: boolean) {
	server.use(
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { practicesEnabled })]),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
	);
	return renderRouteAt("/w/acme/admin/settings", testQueryClient());
}

const ACME = { path: { workspaceSlug: "acme" } };

type ReadAnswer<TBody> = { status: 200; body: TBody } | { status: 403 | 503 };

/** Answers one GET with the answer the test last chose, held until `hold` settles, counting each read. */
function serveRead<TBody extends JsonBodyType>(url: string, initial: ReadAnswer<TBody>) {
	const read: { count: number; answer: ReadAnswer<TBody>; hold: Promise<unknown> } = {
		count: 0,
		answer: initial,
		hold: Promise.resolve(),
	};
	server.use(
		http.get(url, async () => {
			read.count += 1;
			await read.hold;
			const { answer } = read;
			return answer.status === 200
				? HttpResponse.json(answer.body)
				: HttpResponse.json(
						{ status: answer.status, title: "Request failed" },
						{ status: answer.status },
					);
		}),
	);
	return read;
}

/** Reads one query again through its own generated key, as a write or a returning admin does. */
async function reread(queryClient: QueryClient, queryKey: QueryKey) {
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey });
	});
}

/** Every Show again the server receives. */
function recordShowAgain() {
	const shown: string[] = [];
	server.use(
		http.patch("*/workspaces/acme/activity/people/:userId/public-visibility", ({ params }) => {
			shown.push(String(params.userId));
			return new HttpResponse(null, { status: 204 });
		}),
	);
	return shown;
}

describe("workspace settings route", () => {
	it("says practice reviews are on from the workspace list and offers no feature switches", async () => {
		let policyReads = 0;
		server.use(
			http.get("*/admin/settings/public-activity", () => {
				policyReads += 1;
				return HttpResponse.json({ allowed: true });
			}),
		);
		renderSettingsRoute(true);

		await screen.findByRole("heading", { name: "Capabilities" }, ROUTE_RENDER_WAIT);
		screen.getByText(/^On\./u);
		expect(
			within(screen.getByRole("region", { name: "Capabilities" })).queryByRole("switch"),
		).toBeNull();
		expect(screen.getByRole("link", { name: /Review settings/u }).getAttribute("href")).toBe(
			"/w/acme/admin/practices/review",
		);
		expect(policyReads).toBe(0);
	});

	it("says practice reviews are off when the workspace has not turned them on", async () => {
		renderSettingsRoute(false);

		await screen.findByText(/^Off\./u, undefined, ROUTE_RENDER_WAIT);
	});
});

describe("the public activity page setting", () => {
	let settings = { enabled: false, allowSearchEngines: false };
	let changes: unknown[] = [];

	beforeEach(() => {
		settings = { enabled: false, allowSearchEngines: false };
		changes = [];
		server.use(
			http.get("*/workspaces/acme/public-activity", () => HttpResponse.json(settings)),
			http.patch<PathParams, UpdateWorkspacePublicActivityRequest>(
				"*/workspaces/acme/public-activity",
				async ({ request }) => {
					const body = await request.json();
					changes.push(body);
					settings = {
						enabled: body.publicActivityEnabled,
						allowSearchEngines: body.allowSearchEngines,
					};
					return HttpResponse.json(settings);
				},
			),
			http.get("*/workspaces/acme/activity/public-hidden-count", () =>
				HttpResponse.json({ hiddenPeople: 3 }),
			),
		);
	});

	it("asks first, and turns the page on only once the admin has read what becomes public", async () => {
		const user = userEvent.setup();
		const listed = deferred<Response>();
		renderSettingsRoute(false);

		await user.click(
			await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/^When the page is public, anyone can see it without signing in\./u);
		expect(changes).toStrictEqual([]);
		// The workspace list, which says whether the page is public, is read again after the save.
		server.use(http.get("*/workspaces", async () => listed.promise));
		await user.click(within(dialog).getByRole("button", { name: "Make public" }));

		await waitFor(() =>
			expect(changes).toStrictEqual([{ publicActivityEnabled: true, allowSearchEngines: false }]),
		);
		try {
			// The save is acknowledged at once; the address waits for the list to confirm the page.
			await screen.findByText("Public activity settings saved");
			screen.getByText("In workspace acme.");
			const page = within(screen.getByRole("region", { name: "Public activity page" }));
			page.getByText("On for this workspace.");
			expect(page.queryByText(/Anyone can see it/u)).toBeNull();
			// Search engines stay off until the admin turns them on, and the count of hidden people shows.
			await screen.findByRole("switch", { name: "Allow search engines" });
			await screen.findByText("3");

			listed.resolve(
				HttpResponse.json([workspaceListItem("acme", { publishesPublicActivity: true })]),
			);
			await page.findByText("On. Anyone can see it at https://hephaestus.build/w/acme.");
			expect(page.queryByText(/Public availability is not confirmed/u)).toBeNull();
		} finally {
			listed.resolve(HttpResponse.json([workspaceListItem("acme")]));
		}
	});

	it("turns the page off at once, and publishing again starts with search engines off", async () => {
		const user = userEvent.setup();
		settings = { enabled: true, allowSearchEngines: true };
		renderSettingsRoute(false);

		await user.click(
			await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT),
		);
		await waitFor(() =>
			expect(changes).toStrictEqual([{ publicActivityEnabled: false, allowSearchEngines: false }]),
		);
		expect(screen.queryByRole("alertdialog")).toBeNull();

		await user.click(screen.getByRole("switch", { name: "Publish the page" }));
		const dialog = await screen.findByRole("alertdialog");
		within(dialog).getByText(/Search engines are asked not to list it until you allow that/u);
		await user.click(within(dialog).getByRole("button", { name: "Make public" }));

		await waitFor(() => expect(changes).toHaveLength(2));
		expect(changes[1]).toStrictEqual({ publicActivityEnabled: true, allowSearchEngines: false });
		const searchEngines = await screen.findByRole("switch", { name: "Allow search engines" });
		expect(searchEngines.getAttribute("aria-checked")).toBe("false");
	});

	it("lets search engines in without turning the page off", async () => {
		const user = userEvent.setup();
		settings = { enabled: true, allowSearchEngines: false };
		renderSettingsRoute(false);

		await user.click(
			await screen.findByRole("switch", { name: "Allow search engines" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() =>
			expect(changes).toStrictEqual([{ publicActivityEnabled: true, allowSearchEngines: true }]),
		);
	});

	it.each([false, true])(
		"does not claim public availability from a saved On preference (search allowed: %s)",
		async (allowSearchEngines) => {
			settings = { enabled: true, allowSearchEngines };
			renderSettingsRoute(false);
			const page = within(
				await screen.findByRole("region", { name: "Public activity page" }, ROUTE_RENDER_WAIT),
			);
			const publish = await page.findByRole("switch", { name: "Publish the page" });
			await waitFor(() => expect(publish.getAttribute("aria-checked")).toBe("true"));
			expect(page.queryByText(/^On\. Anyone can see it at/u)).toBeNull();
			expect(page.queryByText(/An instance administrator has not allowed/u)).toBeNull();
			await page.findByText(/Public availability is not confirmed/u);
			expect(
				page.getByRole("switch", { name: "Allow search engines" }).getAttribute("aria-checked"),
			).toBe(String(allowSearchEngines));
		},
	);

	it("waits for hidden people without saying there are none, and reads them again on retry", async () => {
		const user = userEvent.setup();
		settings = { enabled: true, allowSearchEngines: false };
		const first = deferred<Response>();
		const retry = deferred<Response>();
		let reads = 0;
		server.use(
			http.get("*/workspaces/acme/activity/hidden-contributors", async () => {
				reads += 1;
				return first.promise;
			}),
		);
		renderSettingsRoute(false);
		const control = await screen.findByRole(
			"switch",
			{ name: "Publish the page" },
			ROUTE_RENDER_WAIT,
		);
		const page = screen.getByRole("region", { name: "Hidden people" });
		await waitFor(() => expect(reads).toBe(1));
		expect(page.querySelector("[data-slot='skeleton']")).not.toBeNull();
		expect(within(page).queryByRole("button", { name: "Show again" })).toBeNull();
		expect(within(page).queryByText("No person without a membership is hidden.")).toBeNull();

		first.resolve(HttpResponse.json({ status: 500 }, { status: 500 }));
		await within(page).findByText("We could not load hidden people");
		expect(control.getAttribute("aria-checked")).toBe("true");
		await user.click(screen.getByRole("switch", { name: "Allow search engines" }));
		await waitFor(() =>
			expect(changes).toStrictEqual([{ publicActivityEnabled: true, allowSearchEngines: true }]),
		);
		within(page).getByText("We could not load hidden people");
		server.use(
			http.get("*/workspaces/acme/activity/hidden-contributors", async () => {
				reads += 1;
				return retry.promise;
			}),
		);
		await user.click(within(page).getByRole("button", { name: /Retry/u }));

		await waitFor(() => expect(reads).toBe(2));
		expect(within(page).queryByRole("button", { name: /Retry/u })).toBeNull();
		expect(page.querySelector("[aria-busy='true']")).not.toBeNull();
		retry.resolve(
			HttpResponse.json([
				{ id: 11, login: "ada", name: "Ada Lovelace", avatarUrl: "", htmlUrl: "", email: "" },
			]),
		);
		await within(page).findByRole("button", { name: "Show again" });
		expect(within(page).queryByText("We could not load hidden people")).toBeNull();
		expect(reads).toBe(2);
	});

	it("shows a person an admin hid without a membership again, which no other screen can", async () => {
		const user = userEvent.setup();
		const shown: string[] = [];
		server.use(
			http.get("*/workspaces/acme/activity/hidden-contributors", () =>
				HttpResponse.json([
					{ id: 11, login: "ada", name: "Ada Lovelace", avatarUrl: "", htmlUrl: "", email: "" },
				]),
			),
			http.patch("*/workspaces/acme/activity/people/11/public-visibility", ({ request }) => {
				shown.push(new URL(request.url).search);
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderSettingsRoute(false);

		await user.click(await screen.findByRole("button", { name: "Show again" }, ROUTE_RENDER_WAIT));

		await waitFor(() => expect(shown).toStrictEqual(["?hidden=false"]));
	});

	it("hides refused hidden people, keeps them hidden through an outage, and shows only a fresh read", async () => {
		settings = { enabled: true, allowSearchEngines: false };
		const contributors = serveRead("*/workspaces/acme/activity/hidden-contributors", {
			status: 200,
			body: [HIDDEN_ADA],
		});
		const shown = recordShowAgain();
		const user = userEvent.setup();
		const queryClient = renderSettingsRoute(false);
		const page = within(
			await screen.findByRole("region", { name: "Hidden people" }, ROUTE_RENDER_WAIT),
		);
		await page.findByText("Ada Lovelace");

		contributors.answer = { status: 403 };
		await reread(queryClient, listHiddenContributorsQueryKey(ACME));
		expect(contributors.count).toBe(2);
		await page.findByText("You do not have access to hidden people");
		expect(page.queryByText("Ada Lovelace")).toBeNull();
		expect(page.queryByRole("button", { name: "Show again" })).toBeNull();
		expect(page.queryByRole("button", { name: "Retry" })).toBeNull();
		screen.getByRole("switch", { name: "Publish the page" });

		contributors.answer = { status: 503 };
		await reread(queryClient, listHiddenContributorsQueryKey(ACME));
		expect(contributors.count).toBe(3);
		await page.findByText("We could not load hidden people");
		expect(page.queryByText("Ada Lovelace")).toBeNull();
		expect(page.queryByText("Hidden people may be out of date")).toBeNull();

		contributors.answer = { status: 200, body: [HIDDEN_GRACE] };
		await user.click(page.getByRole("button", { name: "Retry" }));
		await page.findByText("Grace Hopper");
		expect(contributors.count).toBe(4);
		expect(page.queryByText("Ada Lovelace")).toBeNull();
		expect(shown).toStrictEqual([]);
	});

	it("keeps hidden people through an outage with a notice, and reads them again", async () => {
		settings = { enabled: true, allowSearchEngines: false };
		const contributors = serveRead("*/workspaces/acme/activity/hidden-contributors", {
			status: 200,
			body: [HIDDEN_ADA],
		});
		const user = userEvent.setup();
		const queryClient = renderSettingsRoute(false);
		const page = within(
			await screen.findByRole("region", { name: "Hidden people" }, ROUTE_RENDER_WAIT),
		);
		await page.findByText("Ada Lovelace");

		contributors.answer = { status: 503 };
		await reread(queryClient, listHiddenContributorsQueryKey(ACME));
		expect(contributors.count).toBe(2);
		await page.findByText("Hidden people may be out of date");
		page.getByText("Ada Lovelace");
		page.getByRole("button", { name: "Show again" });

		const fresh = deferred();
		contributors.answer = { status: 200, body: [HIDDEN_ADA, HIDDEN_GRACE] };
		contributors.hold = fresh.promise;
		try {
			await user.click(page.getByRole("button", { name: "Retry" }));
			const retrying = await page.findByRole("button", { name: "Retrying…" });
			expect(retrying.hasAttribute("disabled")).toBe(true);
		} finally {
			fresh.resolve();
		}
		await page.findByText("Grace Hopper");
		expect(contributors.count).toBe(3);
		expect(page.queryByText("Hidden people may be out of date")).toBeNull();
	});

	it("closes a refused page's publishing confirmation and does not reopen it once the settings read again", async () => {
		const settingsRead = serveRead("*/workspaces/acme/public-activity", {
			status: 200,
			body: { enabled: false, allowSearchEngines: false },
		});
		const user = userEvent.setup();
		const queryClient = renderSettingsRoute(false);
		await user.click(
			await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByRole("alertdialog");

		settingsRead.answer = { status: 403 };
		await reread(queryClient, getWorkspacePublicActivitySettingsQueryKey(ACME));
		expect(settingsRead.count).toBe(2);
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		const page = within(screen.getByRole("region", { name: "Public activity page" }));
		await page.findByText("You do not have access to the public activity page settings");
		expect(page.queryByRole("switch")).toBeNull();
		expect(page.queryByRole("button", { name: "Retry" })).toBeNull();

		settingsRead.answer = { status: 503 };
		await reread(queryClient, getWorkspacePublicActivitySettingsQueryKey(ACME));
		expect(settingsRead.count).toBe(3);
		await page.findByText("We could not load the public activity page settings");
		expect(page.queryByRole("switch")).toBeNull();
		expect(screen.queryByRole("alertdialog")).toBeNull();

		settingsRead.answer = { status: 200, body: { enabled: false, allowSearchEngines: false } };
		await user.click(page.getByRole("button", { name: "Retry" }));
		await page.findByRole("switch", { name: "Publish the page" });
		expect(settingsRead.count).toBe(4);
		expect(screen.queryByRole("alertdialog")).toBeNull();
		expect(changes).toStrictEqual([]);
	});

	it("keeps refused settings refused when an earlier change is accepted, and shows only a fresh read", async () => {
		const settingsRead = serveRead("*/workspaces/acme/public-activity", {
			status: 200,
			body: { enabled: true, allowSearchEngines: false },
		});
		const accepted = deferred();
		const writes: unknown[] = [];
		server.use(
			http.patch("*/workspaces/acme/public-activity", async ({ request }) => {
				writes.push(await request.json());
				await accepted.promise;
				return HttpResponse.json({ enabled: true, allowSearchEngines: true });
			}),
		);
		const user = userEvent.setup();
		const queryClient = renderSettingsRoute(false);
		try {
			await user.click(
				await screen.findByRole("switch", { name: "Allow search engines" }, ROUTE_RENDER_WAIT),
			);
			await waitFor(() =>
				expect(writes).toStrictEqual([{ publicActivityEnabled: true, allowSearchEngines: true }]),
			);

			settingsRead.answer = { status: 403 };
			await reread(queryClient, getWorkspacePublicActivitySettingsQueryKey(ACME));
			const page = within(screen.getByRole("region", { name: "Public activity page" }));
			await page.findByText("You do not have access to the public activity page settings");

			accepted.resolve();
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			settingsRead.answer = { status: 503 };
			await reread(queryClient, getWorkspacePublicActivitySettingsQueryKey(ACME));
			await page.findByText("We could not load the public activity page settings");
			expect(page.queryByRole("switch")).toBeNull();
			expect(page.queryByText("These settings may be out of date")).toBeNull();

			settingsRead.answer = { status: 200, body: { enabled: true, allowSearchEngines: true } };
			await user.click(page.getByRole("button", { name: "Retry" }));
			const searchEngines = await page.findByRole("switch", { name: "Allow search engines" });
			expect(searchEngines.getAttribute("aria-checked")).toBe("true");
			expect(settingsRead.count).toBe(5);
			expect(writes).toHaveLength(1);
			expect(screen.queryByRole("alertdialog")).toBeNull();
		} finally {
			accepted.resolve();
		}
	});

	it("leaves out a refused hidden-people count, also through a later outage", async () => {
		settings = { enabled: true, allowSearchEngines: false };
		const count = serveRead("*/workspaces/acme/activity/public-hidden-count", {
			status: 200,
			body: { hiddenPeople: 3 },
		});
		const queryClient = renderSettingsRoute(false);
		const page = within(
			await screen.findByRole("region", { name: "Public activity page" }, ROUTE_RENDER_WAIT),
		);
		await page.findByText("People left out of the page");

		count.answer = { status: 403 };
		await reread(queryClient, getPublicActivityHiddenCountQueryKey(ACME));
		expect(count.count).toBe(2);
		await waitFor(() => expect(page.queryByText("People left out of the page")).toBeNull());

		count.answer = { status: 503 };
		await reread(queryClient, getPublicActivityHiddenCountQueryKey(ACME));
		expect(count.count).toBe(3);
		expect(page.queryByText("People left out of the page")).toBeNull();

		count.answer = { status: 200, body: { hiddenPeople: 5 } };
		await reread(queryClient, getPublicActivityHiddenCountQueryKey(ACME));
		await page.findByText("5");
		expect(count.count).toBe(4);
	});
});

const HIDDEN_GRACE = {
	id: 12,
	login: "grace",
	name: "Grace Hopper",
	avatarUrl: "",
	htmlUrl: "",
	email: "",
};

/** One hidden contributor, as the settings list them. */
const HIDDEN_ADA = {
	id: 11,
	login: "ada",
	name: "Ada Lovelace",
	avatarUrl: "",
	htmlUrl: "",
	email: "",
};

/**
 * Two workspaces, acme and beta, that an admin moves between with the switcher. Each case adds the
 * reads and writes it holds.
 */
function renderTwoWorkspaces() {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme"), workspaceListItem("beta", { id: 2 })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("ADMIN")),
		),
		http.get("*/workspaces/:workspaceSlug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/activity/public-hidden-count", () =>
			HttpResponse.json({ hiddenPeople: 0 }),
		),
	);
	return renderRouteAtWithRouter("/w/acme/admin/settings");
}

function publishSwitch(): HTMLElement {
	return screen.getByRole("switch", { name: "Publish the page" });
}

describe("workspace settings lifetime", () => {
	it("keeps a completed public-page write with its workspace after switching to another", async () => {
		const user = userEvent.setup();
		const response = deferred<Response>();
		const requests: string[] = [];
		const betaSettings = { enabled: true, allowSearchEngines: true };
		let acmeSettings = { enabled: true, allowSearchEngines: false };
		server.use(
			http.get("*/workspaces/acme/public-activity", () => HttpResponse.json(acmeSettings)),
			http.get("*/workspaces/beta/public-activity", () => HttpResponse.json(betaSettings)),
			http.get("*/workspaces/:workspaceSlug/activity/hidden-contributors", () =>
				HttpResponse.json([]),
			),
			http.patch("*/workspaces/acme/public-activity", async ({ request }) => {
				requests.push(new URL(request.url).pathname);
				return response.promise;
			}),
		);
		const { router, queryClient } = renderTwoWorkspaces();
		try {
			await user.click(
				await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT),
			);
			await waitFor(() => expect(requests).toStrictEqual(["/workspaces/acme/public-activity"]));
			// The workspace switcher keeps this portable settings route.
			await switchWorkspace(user, "acme", "beta");
			await waitFor(() => expect(router.state.location.pathname).toBe("/w/beta/admin/settings"));
			await screen.findByRole("switch", { name: "Allow search engines" }, ROUTE_RENDER_WAIT);
			expect(publishSwitch().getAttribute("aria-checked")).toBe("true");
			// The server accepts acme's change, so acme reads it from now on.
			acmeSettings = { enabled: false, allowSearchEngines: false };
			await act(async () => {
				response.resolve(HttpResponse.json(acmeSettings));
			});
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			// Seen on beta, the notification names the workspace whose page it turned off.
			await screen.findByText("The public activity page is off");
			screen.getByText(
				"In workspace acme. Visitors see the change when they load the page again, at most about a minute later.",
			);
			expect(screen.queryByText(/In workspace beta/u)).toBeNull();
			expect(router.state.location.pathname).toBe("/w/beta/admin/settings");
			expect(publishSwitch().getAttribute("aria-checked")).toBe("true");
			expect(
				queryClient.getQueryData(
					getWorkspacePublicActivitySettingsQueryKey({ path: { workspaceSlug: "beta" } }),
				),
			).toStrictEqual(betaSettings);
			await switchWorkspace(user, "beta", "acme");
			await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/admin/settings"));
			await waitFor(() => expect(publishSwitch().getAttribute("aria-checked")).toBe("false"));
			expect(requests).toStrictEqual(["/workspaces/acme/public-activity"]);
		} finally {
			response.resolve(HttpResponse.json({ enabled: false, allowSearchEngines: false }));
		}
	});

	it("says a failed public-page write once, and leaves the next workspace's switches free", async () => {
		const user = userEvent.setup();
		const response = deferred<Response>();
		const betaSettings = { enabled: false, allowSearchEngines: false };
		server.use(
			http.get("*/workspaces/acme/public-activity", () =>
				HttpResponse.json({ enabled: true, allowSearchEngines: false }),
			),
			http.get("*/workspaces/beta/public-activity", () => HttpResponse.json(betaSettings)),
			http.get("*/workspaces/:workspaceSlug/activity/hidden-contributors", () =>
				HttpResponse.json([]),
			),
			http.patch("*/workspaces/acme/public-activity", async () => response.promise),
		);
		const { router, queryClient } = renderTwoWorkspaces();
		try {
			await user.click(
				await screen.findByRole("switch", { name: "Allow search engines" }, ROUTE_RENDER_WAIT),
			);
			await waitFor(() =>
				expect(
					screen
						.getByRole("switch", { name: "Allow search engines" })
						.getAttribute("aria-readonly"),
				).toBe("true"),
			);

			await switchWorkspace(user, "acme", "beta");
			await waitFor(() => expect(router.state.location.pathname).toBe("/w/beta/admin/settings"));
			await waitFor(() => expect(publishSwitch().getAttribute("aria-checked")).toBe("false"));
			expect(publishSwitch().getAttribute("aria-readonly")).not.toBe("true");

			await act(async () => {
				response.resolve(HttpResponse.json({ status: 500 }, { status: 500 }));
			});
			await screen.findByText("We could not change the public activity page. Try again.");
			screen.getByText("In workspace acme.");
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			expect(publishSwitch().getAttribute("aria-checked")).toBe("false");
			expect(
				queryClient.getQueryData(
					getWorkspacePublicActivitySettingsQueryKey({ path: { workspaceSlug: "beta" } }),
				),
			).toStrictEqual(betaSettings);

			await switchWorkspace(user, "beta", "acme");
			await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/admin/settings"));
			const searchEngines = await screen.findByRole("switch", { name: "Allow search engines" });
			expect(searchEngines.getAttribute("aria-checked")).toBe("false");
			expect(publishSwitch().getAttribute("aria-checked")).toBe("true");
		} finally {
			response.resolve(HttpResponse.json({ status: 500 }, { status: 500 }));
		}
	});

	it.each([
		{
			outcome: "succeeds",
			reply: () => new HttpResponse(null, { status: 204 }),
			notification: "Shown in activity again",
			acmeHiddenAfter: [],
		},
		{
			outcome: "fails",
			reply: () => HttpResponse.json({ status: 500 }, { status: 500 }),
			notification: "We could not show this person again. Try again.",
			acmeHiddenAfter: [HIDDEN_ADA],
		},
	])(
		"keeps Show again with the workspace it was asked for when it $outcome",
		async ({ reply, notification, acmeHiddenAfter }) => {
			const user = userEvent.setup();
			const response = deferred<Response>();
			const shown: string[] = [];
			let acmeHidden = [HIDDEN_ADA];
			server.use(
				http.get("*/workspaces/:workspaceSlug/public-activity", () =>
					HttpResponse.json({ enabled: true, allowSearchEngines: false }),
				),
				http.get("*/workspaces/acme/activity/hidden-contributors", () =>
					HttpResponse.json(acmeHidden),
				),
				http.get("*/workspaces/beta/activity/hidden-contributors", () =>
					HttpResponse.json([HIDDEN_ADA]),
				),
				http.patch(
					"*/workspaces/:workspaceSlug/activity/people/11/public-visibility",
					async ({ request }) => {
						shown.push(new URL(request.url).pathname);
						return response.promise;
					},
				),
			);
			const { router } = renderTwoWorkspaces();
			try {
				await user.click(
					await screen.findByRole("button", { name: "Show again" }, ROUTE_RENDER_WAIT),
				);
				await waitFor(() =>
					expect(shown).toStrictEqual(["/workspaces/acme/activity/people/11/public-visibility"]),
				);
				// Focusable while it waits, so the press is refused through aria-disabled.
				await waitFor(() =>
					expect(
						screen.getByRole("button", { name: "Show again" }).getAttribute("aria-disabled"),
					).toBe("true"),
				);

				await switchWorkspace(user, "acme", "beta");
				await waitFor(() => expect(router.state.location.pathname).toBe("/w/beta/admin/settings"));
				const betaShowAgain = await screen.findByRole(
					"button",
					{ name: "Show again" },
					ROUTE_RENDER_WAIT,
				);
				expect(betaShowAgain.getAttribute("aria-disabled")).not.toBe("true");

				acmeHidden = acmeHiddenAfter;
				await act(async () => {
					response.resolve(reply());
				});
				await screen.findByText(notification);
				screen.getByText("In workspace acme.");
				expect(screen.queryByText(/In workspace beta/u)).toBeNull();
				expect(
					screen.getByRole("button", { name: "Show again" }).getAttribute("aria-disabled"),
				).not.toBe("true");

				await switchWorkspace(user, "beta", "acme");
				await waitFor(() => expect(router.state.location.pathname).toBe("/w/acme/admin/settings"));
				await waitFor(() =>
					expect(screen.queryAllByRole("button", { name: "Show again" })).toHaveLength(
						acmeHiddenAfter.length,
					),
				);
				for (const button of screen.queryAllByRole("button", { name: "Show again" })) {
					expect(button.getAttribute("aria-disabled")).not.toBe("true");
				}
				expect(shown).toStrictEqual(["/workspaces/acme/activity/people/11/public-visibility"]);
			} finally {
				response.resolve(reply());
			}
		},
	);

	it("does not carry an open publishing confirmation to the next workspace", async () => {
		const user = userEvent.setup();
		const changes: string[] = [];
		server.use(
			http.get("*/workspaces/:workspaceSlug/public-activity", () =>
				HttpResponse.json({ enabled: false, allowSearchEngines: false }),
			),
			http.get("*/workspaces/:workspaceSlug/activity/hidden-contributors", () =>
				HttpResponse.json([]),
			),
			http.patch("*/workspaces/:workspaceSlug/public-activity", ({ request }) => {
				changes.push(new URL(request.url).pathname);
				return HttpResponse.json({ enabled: true, allowSearchEngines: false });
			}),
		);
		const { router } = renderTwoWorkspaces();

		await user.click(
			await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByRole("alertdialog");
		// The dialog covers the switcher, so the other workspace's address arrives some other way, such
		// as from the browser's history.
		await act(async () => {
			await router.navigate({
				to: "/w/$workspaceSlug/admin/settings",
				params: { workspaceSlug: "beta" },
			});
		});
		await waitFor(() => expect(router.state.location.pathname).toBe("/w/beta/admin/settings"));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		await screen.findByRole("switch", { name: "Publish the page" }, ROUTE_RENDER_WAIT);
		expect(changes).toStrictEqual([]);
	});
});

function serveTarget(status = "ACTIVE") {
	const target = {
		...workspaceListItem("tutors", { displayName: "Tutors", practicesEnabled: true, status }),
		publicActivityEnabled: true,
	};
	server.use(
		http.get("*/user", () => HttpResponse.json(currentUser)),
		http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
		http.get("*/workspaces/tutors", () => HttpResponse.json(target)),
		http.get("*/workspaces/tutors/members/me", () =>
			HttpResponse.json({ status: 403 }, { status: 403 }),
		),
		http.get("*/workspaces/tutors/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/tutors/public-activity", () =>
			HttpResponse.json({ enabled: true, allowSearchEngines: false }),
		),
		http.get("*/admin/settings/public-activity", () => HttpResponse.json({ allowed: true })),
	);
	return target;
}

describe("instance administrator workspace settings", () => {
	it("renders verified nonmember settings and observes native workspace cache updates without a membership", async () => {
		const target = serveTarget();
		const { queryClient } = renderRouteAtWithRouter("/w/tutors/admin/settings");
		await screen.findByRole("heading", { name: "Capabilities" }, ROUTE_RENDER_WAIT);
		await screen.findByText("On. Anyone can see it at", { exact: false }, ROUTE_RENDER_WAIT);
		expect(screen.getByRole("link", { name: /Review settings/u }).getAttribute("href")).toBe(
			"/w/tutors/admin/practices/review",
		);
		expect(screen.queryByRole("button", { name: "Delete workspace" })).toBeNull();
		await act(async () => {
			queryClient.setQueryData(getWorkspaceQueryKey({ path: { workspaceSlug: "tutors" } }), {
				...target,
				displayName: "Tutors updated",
				practicesEnabled: false,
			});
		});
		await screen.findByText("Tutors updated administration");
		within(screen.getByRole("region", { name: "Capabilities" })).getByText(/^Off\./u);
		expect(queryClient.getQueryData(listWorkspacesQueryKey())).toStrictEqual([
			workspaceListItem("acme"),
		]);
	});

	it("opens a nonmember workspace's administration from Workspaces on its settings", async () => {
		serveTarget();
		server.use(
			http.get("*/admin/settings", () =>
				HttpResponse.json({ etag: "1", silentModeEngaged: false }),
			),
			http.get("*/admin/workspaces", () =>
				HttpResponse.json([
					{
						id: 2,
						workspaceSlug: "tutors",
						displayName: "Tutors",
						status: "ACTIVE",
						accountLogin: "tutors",
						providerType: "GITHUB",
						memberCount: 3,
						createdAt: "2026-01-15T00:00:00Z",
					},
				]),
			),
		);
		const { router } = renderRouteAtWithRouter("/admin/workspaces");
		await userEvent.click(
			await screen.findByRole("link", { name: "Open administration of Tutors" }, ROUTE_RENDER_WAIT),
		);
		await screen.findByRole("heading", { name: "Capabilities" }, ROUTE_RENDER_WAIT);
		expect(router.state.location.pathname).toBe("/w/tutors/admin/settings");
	});

	it.each([
		{ status: "ARCHIVED", allowed: true },
		{ status: "ACTIVE", allowed: false },
	])(
		"does not claim public publication for $status with instance policy $allowed",
		async ({ status, allowed }) => {
			serveTarget(status);
			server.use(
				http.get("*/admin/settings/public-activity", () => HttpResponse.json({ allowed })),
			);
			renderRouteAt("/w/tutors/admin/settings");
			await screen.findByText("On for this workspace.", undefined, ROUTE_RENDER_WAIT);
			expect(screen.queryByText("On. Anyone can see it at", { exact: false })).toBeNull();
		},
	);

	it("withdraws refused instance publication policy instead of showing cached public status", async () => {
		serveTarget();
		const policy = serveRead("*/admin/settings/public-activity", {
			status: 200,
			body: { allowed: true },
		});
		const { queryClient } = renderRouteAtWithRouter("/w/tutors/admin/settings");
		await screen.findByText("On. Anyone can see it at", { exact: false }, ROUTE_RENDER_WAIT);
		policy.answer = { status: 403 };
		await act(async () => {
			await queryClient.invalidateQueries({ queryKey: getPublicActivityPolicyQueryKey() });
		});
		await screen.findByText("You do not have access to the public activity page settings");
		expect(screen.queryByText("On. Anyone can see it at", { exact: false })).toBeNull();
		policy.answer = { status: 503 };
		await reread(queryClient, getPublicActivityPolicyQueryKey());
		expect(screen.queryByText("On. Anyone can see it at", { exact: false })).toBeNull();
	});
	it("keeps a refused administration target separate from a failed member-list refresh", async () => {
		serveTarget();
		const { queryClient } = renderRouteAtWithRouter("/w/tutors/admin/settings");
		await screen.findByRole("heading", { name: "Capabilities" }, ROUTE_RENDER_WAIT);
		const otherRequests: string[] = [];
		server.use(
			http.get("*/workspaces", () => HttpResponse.json({ status: 503 }, { status: 503 })),
			http.get("*/workspaces/tutors", () => HttpResponse.json({ status: 403 }, { status: 403 })),
			http.all("*/workspaces/acme/*", ({ request }) => {
				otherRequests.push(new URL(request.url).pathname);
				return HttpResponse.json({ status: 503 }, { status: 503 });
			}),
		);
		await act(async () => {
			await Promise.all([
				queryClient.invalidateQueries({ queryKey: listWorkspacesQueryKey() }),
				queryClient.invalidateQueries({
					queryKey: getWorkspaceQueryKey({ path: { workspaceSlug: "tutors" } }),
				}),
			]);
		});
		await screen.findByText("You do not have access to workspace settings");
		screen.getByRole("link", { name: "Back to instance workspaces" });
		expect(screen.queryByRole("heading", { name: "Capabilities" })).toBeNull();
		expect(screen.queryByRole("link", { name: /Review settings/u })).toBeNull();
		expect(screen.queryByRole("link", { name: "Ask Hephaestus" })).toBeNull();
		expect(otherRequests).toStrictEqual([]);
		expect(queryClient.getQueryData(listWorkspacesQueryKey())).toStrictEqual([
			workspaceListItem("acme"),
		]);
	});
});
