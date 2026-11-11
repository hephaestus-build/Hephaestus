import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";

import type { CatalogGroupAdoptionPreview } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

import { practiceSetupHandlers, wireGroup } from "./-practice-server";

beforeAll(async () => loadRouteCode("/w/acme/admin/practices"), ROUTE_RENDER_WAIT.timeout);

beforeEach(() => {
	server.use(...practiceSetupHandlers());
});

describe("practice catalog route", () => {
	it("keeps the catalog available while new reviews are paused", async () => {
		renderRouteAt("/w/acme/admin/practices");

		await screen.findByRole("heading", { name: "Practice setup" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Enable practices" })).toBeNull();
	});

	it("restores the kind of work filter from the URL", async () => {
		renderRouteAt("/w/acme/admin/practices?focus=scm.issue");

		await screen.findByText("Clear the filter to reorder practices.", {}, ROUTE_RENDER_WAIT);
	});
});

describe("resetting a group to the catalog", () => {
	const edited = wireGroup("review-ready-work", "Review-ready work", {
		description: "Our own words.",
		catalogOrigin: {
			catalogSlug: "review-ready-work",
			state: "EDITED_HERE",
			localEdits: [],
			offeredDigest: "sha256:offered-group",
		},
	});
	const offer: Wire<CatalogGroupAdoptionPreview> = {
		slug: "review-ready-work",
		disposition: "REUSE_EXISTING_GROUP",
		definition: {
			name: "Review-ready work",
			description: "Make each change easy to review.",
			icon: null,
			color: null,
		},
		practices: [],
		actions: [],
		etag: '"group-plan"',
	};

	it("shows the details it replaces, then resets with the group's own ETag", async () => {
		const seenIfMatch = vi.fn<(tag: string | null) => void>();
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([edited])),
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/groups/:slug", () =>
				HttpResponse.json(offer),
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug", () =>
				HttpResponse.json(edited, { headers: { ETag: '"group-reset-basis"' } }),
			),
			http.delete(
				"*/workspaces/:workspaceSlug/practice-groups/:groupSlug/catalog-customization",
				({ request }) => {
					seenIfMatch(request.headers.get("If-Match"));
					return HttpResponse.json({
						...edited,
						description: "Make each change easy to review.",
						catalogOrigin: { ...edited.catalogOrigin, state: "FROM_HEPHAESTUS" },
					});
				},
			),
		);

		renderRouteAt("/w/acme/admin/practices");
		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: "More actions for Review-ready work" },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(await screen.findByRole("menuitem", { name: "Reset to catalog" }));

		const dialog = await screen.findByRole("alertdialog", {
			name: "Reset “Review-ready work” to the catalog?",
		});
		const description = await within(dialog).findByRole("rowheader", { name: "Description" });
		expect(description.closest("tr")?.textContent).toBe(
			"DescriptionOur own words.Make each change easy to review.",
		);
		expect(within(dialog).queryByRole("rowheader", { name: "Name" })).toBeNull();
		expect(seenIfMatch).not.toHaveBeenCalled();

		fireEvent.click(within(dialog).getByRole("button", { name: "Reset to catalog" }));

		await waitFor(() => expect(seenIfMatch).toHaveBeenCalledWith('"group-reset-basis"'));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull(), ROUTE_RENDER_WAIT);
	});

	it.each([
		{
			change: "changes",
			group: { ...edited, description: "Someone else's words." },
			description: "Someone else's words.",
		},
		{
			change: "changes and returns to the same text",
			group: edited,
			description: "Our own words.",
		},
	])(
		"requires comparison again when the group $change after it was shown",
		async ({ group: changed, description }) => {
			const seenIfMatch = vi.fn<(tag: string | null) => void>();
			let saved = edited;
			let etag = '"group-reset-basis"';
			server.use(
				http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([saved])),
				http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/groups/:slug", () =>
					HttpResponse.json(offer),
				),
				http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug", () =>
					HttpResponse.json(saved, { headers: { ETag: etag } }),
				),
				http.delete(
					"*/workspaces/:workspaceSlug/practice-groups/:groupSlug/catalog-customization",
					({ request }) => {
						seenIfMatch(request.headers.get("If-Match"));
						return HttpResponse.json({ status: 412, title: "Group changed" }, { status: 412 });
					},
				),
			);

			renderRouteAt("/w/acme/admin/practices");
			fireEvent.click(
				await screen.findByRole(
					"button",
					{ name: "More actions for Review-ready work" },
					ROUTE_RENDER_WAIT,
				),
			);
			fireEvent.click(await screen.findByRole("menuitem", { name: "Reset to catalog" }));
			const dialog = await screen.findByRole("alertdialog");
			await within(dialog).findByText("Our own words.");
			saved = changed;
			etag = '"newer-basis"';
			fireEvent.click(within(dialog).getByRole("button", { name: "Reset to catalog" }));

			const refused = await within(dialog).findByRole("alert", {}, ROUTE_RENDER_WAIT);
			expect(refused.textContent).toContain("Nothing was reset.");
			await within(dialog).findByText(description);
			expect(seenIfMatch).toHaveBeenCalledExactlyOnceWith('"group-reset-basis"');
		},
	);
});
