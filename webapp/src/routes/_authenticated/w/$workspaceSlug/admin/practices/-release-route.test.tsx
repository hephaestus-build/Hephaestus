import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it } from "vitest";

import { getPracticeGuidanceOptions } from "@/api/@tanstack/react-query.gen";
import type { PracticeReleaseProposal } from "@/api/types.gen";
import { definitionOf } from "@/components/admin/practice-editor/fixtures";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import {
	loadRouteCode,
	ROUTE_RENDER_WAIT,
	renderRouteAt,
	testQueryClient,
} from "@/test/router-harness";

import { practiceSetupHandlers, wireGuidance, wirePractice } from "./-practice-server";

beforeAll(
	async () => loadRouteCode("/w/acme/admin/practices", "/w/acme/admin/practices/releases"),
	ROUTE_RENDER_WAIT.timeout,
);

const base = definitionOf({ name: "Explain the change", criteria: "Earlier criteria" });

const proposal: Wire<PracticeReleaseProposal> = {
	practiceSlug: "explain-the-change",
	currentVersionId: "801",
	base,
	current: { ...base, criteria: "Our criteria" },
	offered: { ...base, criteria: "New catalog criteria" },
	offeredDigest: "sha256:offered",
	etag: "reviewed-offer",
	fields: [{ field: "CRITERIA", changedLocally: true, offeredChanged: true, conflict: true }],
};

const accepted = wirePractice({
	slug: proposal.practiceSlug,
	definition: proposal.offered,
	versionId: "802",
	versionNumber: 2,
});

beforeEach(() => {
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([proposal])),
		...practiceSetupHandlers(),
	);
});

it("names Catalog updates the same from Practice setup, on its page, and on the way back", async () => {
	renderRouteAt("/w/acme/admin/practices");
	const open = await screen.findByRole(
		"button",
		{ name: "Catalog updates (1)" },
		ROUTE_RENDER_WAIT,
	);
	expect(open.getAttribute("href")).toBe("/w/acme/admin/practices/releases");

	fireEvent.click(open);
	await screen.findByRole("heading", { name: "Catalog updates", level: 1 }, ROUTE_RENDER_WAIT);
	const back = screen.getByRole("button", { name: "Back to practice setup" });
	expect(back.getAttribute("href")).toBe("/w/acme/admin/practices");
});

it("accepts a workspace update with explicit field choice and the reviewed version", async () => {
	const queryClient = testQueryClient();
	const guidanceOptions = {
		...getPracticeGuidanceOptions({
			path: { workspaceSlug: "acme", practiceSlug: proposal.practiceSlug },
		}),
		staleTime: 30_000,
	};
	queryClient.setQueryData(
		guidanceOptions.queryKey,
		wireGuidance(proposal.practiceSlug, "Earlier guide"),
	);
	let receivedTag: string | null = null;
	let receivedBody: unknown;
	server.use(
		http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
			HttpResponse.json(wireGuidance(proposal.practiceSlug, "Adopted guide")),
		),
		http.put("*/workspaces/:workspaceSlug/practices/releases/:slug", async ({ request }) => {
			receivedTag = request.headers.get("if-match");
			receivedBody = await request.json();
			server.use(
				http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([])),
			);
			return HttpResponse.json(accepted);
		}),
	);
	renderRouteAt("/w/acme/admin/practices/releases", queryClient);

	const choices = await screen.findByRole(
		"radiogroup",
		{ name: "Use a version for Review criteria" },
		ROUTE_RENDER_WAIT,
	);
	expect(screen.getByRole("button", { name: "Accept selected fields" })).toHaveProperty(
		"disabled",
		true,
	);
	fireEvent.click(within(choices).getByRole("radio", { name: "Proposed" }));
	fireEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));

	await waitFor(() => expect(receivedTag).toBe('"reviewed-offer"'));
	expect(receivedBody).toStrictEqual({ choices: { CRITERIA: "OFFERED" } });
	await screen.findByText("No updates to review", undefined, ROUTE_RENDER_WAIT);
	await expect(queryClient.query(guidanceOptions)).resolves.toMatchObject({
		guide: { markdown: "Adopted guide" },
	});
});

const decisions = [
	{
		decision: "accept",
		action: "Accept selected fields",
		progress: "Accepting selected fields…",
		other: "Decline this update",
		success: "Practice update accepted",
	},
	{
		decision: "decline",
		action: "Decline this update",
		progress: "Declining update…",
		other: "Accept selected fields",
		success: "Practice update declined",
	},
];

it.each(decisions)(
	"keeps $decision pending until the updated inbox arrives",
	async ({ action, progress, other, success }) => {
		const refreshed = deferred<Response>();
		let refreshes = 0;
		server.use(
			http.get(
				"*/workspaces/:workspaceSlug/practices/releases",
				() => HttpResponse.json([proposal]),
				{
					once: true,
				},
			),
			http.get("*/workspaces/:workspaceSlug/practices/releases", async () => {
				refreshes += 1;
				return refreshed.promise;
			}),
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
				HttpResponse.json(wireGuidance(proposal.practiceSlug, "Adopted guide")),
			),
			http.put("*/workspaces/:workspaceSlug/practices/releases/:slug", () =>
				HttpResponse.json(accepted),
			),
			http.delete(
				"*/workspaces/:workspaceSlug/practices/releases/:slug",
				() => new HttpResponse(null, { status: 204 }),
			),
		);
		renderRouteAt("/w/acme/admin/practices/releases");
		const choices = await screen.findByRole(
			"radiogroup",
			{ name: "Use a version for Review criteria" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(within(choices).getByRole("radio", { name: "Proposed" }));
		fireEvent.click(
			screen.getByRole("button", {
				name: action,
			}),
		);
		try {
			await waitFor(() => expect(refreshes).toBe(1));
			expect(screen.getByRole("button", { name: progress }).getAttribute("aria-disabled")).toBe(
				"true",
			);
			expect(
				screen.getByRole("button", {
					name: other,
				}),
			).toHaveProperty("disabled", true);
			expect(
				within(choices).getByRole("radio", { name: "Proposed" }).getAttribute("aria-disabled"),
			).toBe("true");
			await act(async () => {
				refreshed.resolve(HttpResponse.json([]));
			});
			await screen.findByText("No updates to review", undefined, ROUTE_RENDER_WAIT);
			await screen.findByText(success);
		} finally {
			refreshed.resolve(HttpResponse.json([]));
		}
	},
);

it.each(decisions)(
	"keeps a saved $decision decision successful when the inbox refresh fails",
	async ({ action, success }) => {
		server.use(
			http.get(
				"*/workspaces/:workspaceSlug/practices/releases",
				() => HttpResponse.json([proposal]),
				{ once: true },
			),
			http.get("*/workspaces/:workspaceSlug/practices/releases", () =>
				HttpResponse.json({ status: 503 }, { status: 503 }),
			),
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
				HttpResponse.json(wireGuidance(proposal.practiceSlug, "Adopted guide")),
			),
			http.put("*/workspaces/:workspaceSlug/practices/releases/:slug", () =>
				HttpResponse.json(accepted),
			),
			http.delete(
				"*/workspaces/:workspaceSlug/practices/releases/:slug",
				() => new HttpResponse(null, { status: 204 }),
			),
		);
		renderRouteAt("/w/acme/admin/practices/releases");
		const choices = await screen.findByRole(
			"radiogroup",
			{ name: "Use a version for Review criteria" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(within(choices).getByRole("radio", { name: "Proposed" }));
		fireEvent.click(
			screen.getByRole("button", {
				name: action,
			}),
		);
		await screen.findByText("We could not load catalog updates", undefined, ROUTE_RENDER_WAIT);
		await screen.findByText(success);
		expect(screen.queryByText("We could not accept the update")).toBeNull();
		expect(screen.queryByText("We could not decline the update")).toBeNull();
	},
);

describe("an update to a practice edited in the practice repository", () => {
	const inRepository = wirePractice({
		slug: proposal.practiceSlug,
		definition: proposal.current,
		versionId: "801",
		editedIn: "REPOSITORY",
	});
	const unavailable = /edited in the practice repository, so you cannot accept an update here/u;

	it("offers Decline but not Accept", async () => {
		let declines = 0;
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([inRepository])),
			http.delete("*/workspaces/:workspaceSlug/practices/releases/:slug", () => {
				declines += 1;
				return new HttpResponse(null, { status: 204 });
			}),
		);
		renderRouteAt("/w/acme/admin/practices/releases");

		const choices = await screen.findByRole(
			"radiogroup",
			{ name: "Use a version for Review criteria" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(within(choices).getByRole("radio", { name: "Proposed" }));
		const accept = screen.getByRole("button", { name: "Accept selected fields" });
		expect(accept.getAttribute("aria-disabled")).toBe("true");
		screen.getByText(unavailable);

		fireEvent.click(screen.getByRole("button", { name: "Decline this update" }));
		await waitFor(() => expect(declines).toBe(1), ROUTE_RENDER_WAIT);
	});

	it("reloads after a conflict, so a practice moved there meanwhile is shown as it now is", async () => {
		let accepts = 0;
		server.use(
			http.put("*/workspaces/:workspaceSlug/practices/releases/:slug", () => {
				accepts += 1;
				server.use(
					http.get("*/workspaces/:workspaceSlug/practices", () =>
						HttpResponse.json([inRepository]),
					),
				);
				return HttpResponse.json(
					{
						title: "Conflict",
						status: 409,
						detail:
							"This practice is edited in the practice repository. Change its folder there, or choose Edit in Hephaestus first.",
					},
					{ status: 409 },
				);
			}),
		);
		renderRouteAt("/w/acme/admin/practices/releases");

		const choices = await screen.findByRole(
			"radiogroup",
			{ name: "Use a version for Review criteria" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(within(choices).getByRole("radio", { name: "Proposed" }));
		fireEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));

		await screen.findByText(unavailable, undefined, ROUTE_RENDER_WAIT);
		expect(accepts).toBe(1);
		expect(
			screen.getByRole("button", { name: "Accept selected fields" }).getAttribute("aria-disabled"),
		).toBe("true");
		await screen.findByText("We could not accept the update");
	});
});
