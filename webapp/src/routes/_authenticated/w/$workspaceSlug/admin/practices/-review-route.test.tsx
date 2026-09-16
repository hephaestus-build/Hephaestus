import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { assert, describe, expect, it, vi } from "vitest";

import { buildAutonomyFixture } from "@/components/admin/practices/practice-autonomy/fixtures";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const fixture = buildAutonomyFixture({
	workspaceDefault: "HUMAN_APPROVAL",
	groups: [
		{
			slug: "hygiene",
			name: "Hygiene",
			practices: [{ name: "States the motivation" }, { name: "Links the issue", override: "OFF" }],
		},
	],
});

/** `extra` goes first: MSW answers with the first handler that matches, so a test can override one. */
function stubWorkspace(extra: Parameters<typeof server.use>) {
	server.use(
		...extra,
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
		),
		http.get("*/workspaces/:workspaceSlug/practices/review-settings", () =>
			HttpResponse.json(fixture.settings),
		),
		http.get("*/workspaces/:workspaceSlug/practices/autonomy", () =>
			HttpResponse.json(fixture.rollup),
		),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json(fixture.practices)),
	);
}

describe("review route", () => {
	it("answers 'what is this workspace doing' from the rollup rather than from the rows", async () => {
		stubWorkspace([]);

		renderRouteAt("/w/acme/admin/practices/review");

		await screen.findByRole("heading", { name: "Review" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("button", { name: /Hygiene/u }, ROUTE_RENDER_WAIT);

		screen.getByText("2 practices: 1 off and 1 review before sending. 1 practice set by hand.");
		screen.getByText("2 practices: 1 off and 1 review before sending.");
		expect(screen.queryByText("States the motivation")).toBeNull();
	});

	/**
	 * Three URLs that have been in the sidebar, in the admin docs and in people's bookmarks, so each
	 * has to keep resolving to the section that holds its subject. The autonomy screen's overrides
	 * filter travels too: it is the one deep link into these anybody had reason to save.
	 */
	it.each([
		["/w/acme/admin/practices/autonomy", "/w/acme/admin/practices/review"],
		[
			"/w/acme/admin/practices/autonomy?overrides=true",
			"/w/acme/admin/practices/review?overrides=true",
		],
		["/w/acme/admin/practices/settings", "/w/acme/admin/practices/review?section=when-and-where"],
		["/w/acme/admin/practices/backfill", "/w/acme/admin/practices/review?section=past-work"],
	])("redirects %s to %s", async (from, to) => {
		stubWorkspace([]);

		const { router } = renderRouteAtWithRouter(from);

		await waitFor(() => expect(router.state.location.href).toBe(to), ROUTE_RENDER_WAIT);
	});

	/**
	 * The whole reason the page hands its section bodies down as elements instead of composing them:
	 * an element that is never mounted runs no hooks, so a reader who opens one tab pays for one
	 * tab's data. Asserted at the network, because the DOM assertion below cannot tell a section that
	 * rendered nothing from one that never ran.
	 */
	it("asks for a section's data only once its tab is opened", async () => {
		const requested: string[] = [];
		const recorded = (path: string, body: Parameters<typeof HttpResponse.json>[0]) =>
			http.get(path, ({ request }) => {
				requested.push(new URL(request.url).pathname);
				return HttpResponse.json(body);
			});
		stubWorkspace([
			recorded("*/workspaces/:workspaceSlug/practices/autonomy", fixture.rollup),
			recorded("*/workspaces/:workspaceSlug/practices/backfill-runs", []),
		]);

		renderRouteAt("/w/acme/admin/practices/review?section=past-work");

		await waitFor(
			() => expect(requested.some((path) => path.endsWith("/backfill-runs"))).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		expect(requested.some((path) => path.endsWith("/autonomy"))).toBe(false);

		await userEvent.click(screen.getByRole("tab", { name: "How much" }));

		await waitFor(
			() => expect(requested.some((path) => path.endsWith("/autonomy"))).toBe(true),
			ROUTE_RENDER_WAIT,
		);
	});

	it("opens the section a deep link names", async () => {
		stubWorkspace([]);

		renderRouteAt("/w/acme/admin/practices/review?section=past-work");

		const tab = await screen.findByRole("tab", { name: "Past work" }, ROUTE_RENDER_WAIT);
		expect(tab.getAttribute("aria-selected")).toBe("true");
		// Only the named panel is rendered, which is what keeps the other two sections' queries — and
		// the autonomy screen's sticky strip — off a page nobody has opened them on.
		expect(screen.queryByRole("tabpanel", { name: "How much" })).toBeNull();
	});

	/**
	 * The one wire detail a screen can get wrong silently. The generated request types the autonomy as
	 * optional rather than nullable, and the server reads an absent field as "hold no autonomy here and
	 * inherit" — so clearing an override means omitting the key, not sending `null`.
	 */
	it("clears an override by omitting the autonomy, not by sending null", async () => {
		const bodies: Record<string, unknown>[] = [];
		stubWorkspace([
			http.patch<PathParams, Record<string, unknown>>(
				"*/workspaces/:workspaceSlug/practices/:practiceSlug/autonomy",
				async ({ request }) => {
					bodies.push(await request.json());
					return HttpResponse.json({
						...fixture.practices[1],
						autonomy: { effective: "HUMAN_APPROVAL", source: "GROUP", inherited: true },
					});
				},
			),
		]);

		renderRouteAt("/w/acme/admin/practices/review");

		const group = await screen.findByRole("button", { name: /Hygiene/u }, ROUTE_RENDER_WAIT);
		await userEvent.click(group);
		const practice = await screen.findByText("Links the issue");
		const row = practice.closest("li");
		assert(row instanceof HTMLElement, "Practice row not rendered");

		// `hidden: true`: jsdom runs no layout, so Base UI leaves the opened accordion panel carrying
		// the attribute it strips once the panel has a height. The browser-run story covers the state a
		// real user meets; this test is here for the request body.
		await userEvent.click(
			within(row).getByRole("button", {
				name: "Use the default for Links the issue",
				hidden: true,
			}),
		);

		await waitFor(() => expect(bodies).toHaveLength(1));
		const [body] = bodies;
		assert(body);
		expect(body).toStrictEqual({});
		expect("autonomy" in body).toBe(false);
	});
});
