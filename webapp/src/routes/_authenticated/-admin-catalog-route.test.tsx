import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay, HttpResponse, http, type PathParams } from "msw";
import { beforeAll, assert, describe, expect, it } from "vitest";

import type {
	CatalogGroup,
	CatalogPractice,
	CatalogPracticeInput,
	PracticeReleaseProposal,
} from "@/api/types.gen";
import {
	catalogGroup,
	catalogPractice,
	catalogPracticeDefinition,
} from "@/components/admin/catalog/fixtures";
import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { server } from "@/mocks/server";
import { precedes } from "@/test/dom";
import { loadRouteCode, ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

beforeAll(async () => loadRouteCode("/admin/catalog"), ROUTE_RENDER_WAIT.timeout);

const PRACTICE_PATH = "*/admin/practice-catalog/practices/:slug";

const packaging = catalogGroup({ slug: "packaging", name: "Packaging work" });
const delivery = catalogGroup({ slug: "delivery", position: 1, name: "Delivery" });

const definition = catalogPracticeDefinition({ criteria: "Our own criteria" });
const savedPractice = catalogPractice({ groupSlug: "packaging", definition });

const release: PracticeReleaseProposal = {
	currentVersionId: null,
	practiceSlug: savedPractice.slug,
	base: { ...definition, criteria: "Earlier criteria" },
	current: definition,
	offered: { ...definition, criteria: "The definition Hephaestus ships now" },
	offeredDigest: "offered-digest",
	etag: "release-tag",
	fields: [{ field: "CRITERIA", offeredChanged: true, changedLocally: true, conflict: true }],
};

function mockCatalog({
	groups = [packaging],
	practices = [{ ...savedPractice, state: "UPDATE_WAITING", changeKind: "REVIEW" }],
}: { groups?: CatalogGroup[]; practices?: CatalogPractice[] } = {}) {
	const catalog = { groups, practices };
	server.use(
		http.get("*/admin/practice-catalog", () => HttpResponse.json(catalog)),
		http.get("*/admin/practice-catalog/definition-options", () =>
			HttpResponse.json(mockPracticeDefinitionOptions),
		),
	);
	return catalog;
}

function servePractice(practice: CatalogPractice) {
	server.use(http.get(PRACTICE_PATH, () => HttpResponse.json(practice)));
}

describe("instance catalog routes", () => {
	it("leads pending updates to a focused review", async () => {
		mockCatalog();
		renderRouteAt("/admin/catalog");

		await screen.findByText("1 Hephaestus change needs review", undefined, ROUTE_RENDER_WAIT);
		screen.getByText("1 update would change review rules");
		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));
		await screen.findByRole("button", { name: "Show all entries" });
	});

	it("opens the practice editor from the catalog", async () => {
		mockCatalog();
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole("link", { name: "Create practice" }, ROUTE_RENDER_WAIT),
		);

		await screen.findByRole("heading", { name: "Create practice" }, ROUTE_RENDER_WAIT);
	});

	it("opens the group editor from the catalog", async () => {
		mockCatalog();
		renderRouteAt("/admin/catalog");

		fireEvent.click(await screen.findByRole("link", { name: "Create group" }, ROUTE_RENDER_WAIT));

		await screen.findByRole("heading", { name: "Create group" }, ROUTE_RENDER_WAIT);
	});

	it("creates a practice with one definition and its group outside it", async () => {
		mockCatalog();
		let body: CatalogPracticeInput | undefined;
		server.use(
			http.post<PathParams, CatalogPracticeInput>(
				"*/admin/practice-catalog/practices",
				async ({ request }) => {
					body = await request.json();
					return HttpResponse.json(catalogPractice());
				},
			),
		);
		renderRouteAt("/admin/catalog?detail=practice-new:draft");

		fireEvent.change(await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT), {
			target: { value: "Explain the change" },
		});
		fireEvent.change(screen.getByRole("textbox", { name: /^What to look for/u }), {
			target: { value: "The description says why the change is needed." },
		});
		const user = userEvent.setup();
		await user.click(screen.getByRole("button", { name: "Add condition" }));
		await user.click(screen.getByRole("combobox", { name: "Condition 1" }));
		await user.click(await screen.findByRole("option", { name: "A source is not empty" }));
		await user.click(screen.getByRole("combobox", { name: "Source 1" }));
		const sourceOptions = await screen.findByRole("listbox", { name: "Source 1" });
		expect(
			within(sourceOptions)
				.getAllByRole("option")
				.map((option) => option.textContent),
		).toStrictEqual(["Review threads and decisions", "General review comments"]);
		await user.click(
			within(sourceOptions).getByRole("option", { name: "General review comments" }),
		);
		expect(screen.getByRole("combobox", { name: "Source 1" }).textContent).toContain(
			"General review comments",
		);
		fireEvent.change(screen.getByRole("textbox", { name: /^Otherwise/u }), {
			target: { value: "There are no review comments to check." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Choose sources" }));
		fireEvent.click(
			within(
				screen.getByRole("radiogroup", { name: "How Pull request details is read" }),
			).getByRole("radio", { name: "Required" }),
		);
		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));

		await waitFor(() => expect(body).toBeDefined());
		assert(body);
		expect(Object.keys(body).sort()).toStrictEqual([
			"definition",
			"groupSlug",
			"kind",
			"position",
			"slug",
		]);
		expect(body.slug).toBe("explain-the-change");
		expect(body.position).toBeNull();
		expect(body.definition).toMatchObject({
			name: "Explain the change",
			criteria: "The description says why the change is needed.",
		});
		expect(body.definition.appliesWhen).toStrictEqual({
			anyOf: [{ hasItems: "scm.general-review-comments" }],
			otherwise: "There are no review comments to check.",
		});
		expect(body.definition).not.toHaveProperty("groupSlug");
		expect(body.definition).not.toHaveProperty("kind");
	});

	it("saves an edit as one complete definition with the entry's tag", async () => {
		const visual = {
			svg: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 640 320"><rect class="pv-fill-accent" width="10" height="10"/></svg>',
			alt: "One small square.",
		};
		const guided = catalogPractice({
			...savedPractice,
			definition: { ...definition, guidance: { ...definition.guidance, visual } },
		});
		mockCatalog({ practices: [guided] });
		servePractice(guided);
		let ifMatch: string | null = null;
		let body: CatalogPracticeInput | undefined;
		server.use(
			http.put<PathParams, CatalogPracticeInput>(PRACTICE_PATH, async ({ request }) => {
				ifMatch = request.headers.get("if-match");
				body = await request.json();
				return HttpResponse.json({ ...guided, etag: "tag-2" });
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		const name = await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT);
		fireEvent.change(name, { target: { value: "Say what changed, and why" } });
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await waitFor(() => expect(body).toBeDefined());
		expect(ifMatch).toBe('"tag-1"');
		expect(body).toStrictEqual({
			kind: guided.kind,
			groupSlug: "packaging",
			position: null,
			definition: { ...guided.definition, name: "Say what changed, and why" },
		});
	});

	it("reorders groups", async () => {
		mockCatalog({ groups: [packaging, delivery] });
		let body: unknown;
		server.use(
			http.patch("*/admin/practice-catalog/groups/reorder", async ({ request }) => {
				body = await request.json();
				return HttpResponse.json({
					groups: [
						{ ...delivery, position: 0 },
						{ ...packaging, position: 1 },
					],
					practices: [],
				});
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: "More actions for Packaging work" },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(await screen.findByRole("menuitem", { name: "Move down" }));

		await waitFor(() => expect(body).toStrictEqual({ slugs: ["delivery", "packaging"] }));
	});

	it("restores the Hephaestus order", async () => {
		const catalog = mockCatalog();
		let restored = false;
		server.use(
			http.delete("*/admin/practice-catalog/order", () => {
				restored = true;
				return HttpResponse.json(catalog);
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole("button", { name: "Use Hephaestus order" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		fireEvent.click(within(dialog).getByRole("button", { name: "Use Hephaestus order" }));

		await waitFor(() => expect(restored).toBe(true));
	});

	it("restores the order and focus after a reorder conflict", async () => {
		mockCatalog({ groups: [packaging, delivery] });
		server.use(
			http.patch("*/admin/practice-catalog/groups/reorder", async () => {
				await delay(100);
				return HttpResponse.json({ status: 409, title: "Stale" }, { status: 409 });
			}),
		);
		renderRouteAt("/admin/catalog");

		const packagingHeading = await screen.findByText(
			"Packaging work",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(precedes(packagingHeading, screen.getByText("Delivery"))).toBe(true);
		const actions = await screen.findByRole(
			"button",
			{ name: "More actions for Packaging work" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(actions);
		fireEvent.click(await screen.findByRole("menuitem", { name: "Move down" }));
		await waitFor(() =>
			expect(precedes(screen.getByText("Delivery"), screen.getByText("Packaging work"))).toBe(true),
		);

		await screen.findByText(
			"The catalog order changed before this move was saved. We reloaded the latest order.",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		expect(precedes(screen.getByText("Packaging work"), screen.getByText("Delivery"))).toBe(true);
		await waitFor(() => expect(document.activeElement).toBe(actions));
	});

	it("moves a practice to another group", async () => {
		const catalog = mockCatalog({ groups: [packaging, delivery] });
		let body: unknown;
		server.use(
			http.patch(`${PRACTICE_PATH}/group`, async ({ request }) => {
				body = await request.json();
				return HttpResponse.json({
					...catalog,
					practices: catalog.practices.map((practice) => ({
						...practice,
						groupSlug: "delivery",
					})),
				});
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: `More actions for ${definition.name}` },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(await screen.findByRole("menuitemradio", { name: "Delivery" }));

		await waitFor(() => expect(body).toStrictEqual({ groupSlug: "delivery", position: 0 }));
	});

	it("explains which practices group exclusion affects", async () => {
		const catalog = mockCatalog();
		let ifMatch: string | null = null;
		let body: unknown;
		server.use(
			http.patch("*/admin/practice-catalog/groups/:slug/status", async ({ request }) => {
				ifMatch = request.headers.get("if-match");
				body = await request.json();
				return HttpResponse.json({
					...catalog,
					groups: catalog.groups.map((group) => ({ ...group, excluded: true, offered: false })),
				});
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Include Packaging work for workspaces" },
				ROUTE_RENDER_WAIT,
			),
		);
		const confirmation = screen.getByRole("alertdialog");
		within(confirmation).getByText(/also excludes 1 included practice/u);
		within(confirmation).getByText(definition.name);
		fireEvent.click(within(confirmation).getByRole("button", { name: "Exclude group" }));

		await waitFor(() => expect(ifMatch).toBe('"group-tag-1"'));
		expect(body).toStrictEqual({ excluded: true });
	});

	it("includes an excluded group again", async () => {
		const excluded = { ...packaging, excluded: true, offered: false };
		const catalog = mockCatalog({ groups: [excluded] });
		let body: unknown;
		server.use(
			http.patch("*/admin/practice-catalog/groups/:slug/status", async ({ request }) => {
				body = await request.json();
				return HttpResponse.json({ ...catalog, groups: [packaging] });
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Include Packaging work for workspaces" },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() => expect(body).toStrictEqual({ excluded: false }));
	});

	it("prevents a second catalog write while a group update is pending", async () => {
		const catalog = mockCatalog({ groups: [packaging, delivery] });
		server.use(
			http.patch("*/admin/practice-catalog/groups/:slug/status", async () => {
				await delay(500);
				return HttpResponse.json(catalog);
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Include Packaging work for workspaces" },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Exclude group" }));

		await waitFor(() =>
			expect(
				screen
					.getByRole("switch", { name: "Include Delivery for workspaces" })
					.getAttribute("aria-disabled"),
			).toBe("true"),
		);
		expect(
			screen.getByRole("button", { name: "Use Hephaestus order" }).hasAttribute("disabled"),
		).toBe(true);
		expect(screen.getByRole("button", { name: "Create group" }).hasAttribute("disabled")).toBe(
			true,
		);
		expect(screen.getByRole("button", { name: "Create practice" }).hasAttribute("disabled")).toBe(
			true,
		);
	});

	it("sends the entry's tag when it excludes a practice", async () => {
		mockCatalog({ practices: [savedPractice] });
		let ifMatch: string | null = null;
		let body: unknown;
		server.use(
			http.patch(`${PRACTICE_PATH}/status`, async ({ request }) => {
				ifMatch = request.headers.get("if-match");
				body = await request.json();
				return HttpResponse.json({ ...savedPractice, excluded: true, offered: false });
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: `Include ${definition.name} for workspaces` },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Exclude practice" }));

		await waitFor(() => expect(ifMatch).toBe('"tag-1"'));
		expect(body).toStrictEqual({ excluded: true });
	});

	it("uses the status response tag when an inactive editor is reopened", async () => {
		mockCatalog({ practices: [savedPractice] });
		let latestTag = "tag-1";
		let updateIfMatch: string | null = null;
		server.use(
			http.get(PRACTICE_PATH, () => HttpResponse.json({ ...savedPractice, etag: latestTag })),
			http.patch(`${PRACTICE_PATH}/status`, () => {
				latestTag = "tag-2";
				return HttpResponse.json({
					...savedPractice,
					etag: latestTag,
					excluded: true,
					offered: false,
				});
			}),
			http.put(PRACTICE_PATH, ({ request }) => {
				updateIfMatch = request.headers.get("if-match");
				return HttpResponse.json({ ...savedPractice, etag: "tag-3" });
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		fireEvent.click(await screen.findByRole("button", { name: "Cancel" }, ROUTE_RENDER_WAIT));
		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: `Include ${definition.name} for workspaces` },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Exclude practice" }));
		await waitFor(() => expect(latestTag).toBe("tag-2"));

		fireEvent.click(await screen.findByRole("link", { name: definition.name }));
		const name = await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT);
		fireEvent.change(name, { target: { value: "Updated name" } });
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await waitFor(() => expect(updateIfMatch).toBe('"tag-2"'));
	});

	it("restores the Hephaestus default with the entry's tag", async () => {
		const customized = {
			...savedPractice,
			state: "EDITED_HERE",
			shipped: catalogPracticeDefinition({ criteria: "The Hephaestus criteria" }),
		} satisfies CatalogPractice;
		mockCatalog({ practices: [customized] });
		servePractice(customized);
		let ifMatch: string | null = null;
		server.use(
			http.delete(`${PRACTICE_PATH}/override`, ({ request }) => {
				ifMatch = request.headers.get("if-match");
				return HttpResponse.json({
					...customized,
					state: "FROM_HEPHAESTUS",
					definition: customized.shipped,
					etag: "tag-2",
				});
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		fireEvent.click(
			await screen.findByRole("button", { name: "Restore Hephaestus default" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		fireEvent.click(within(dialog).getByRole("button", { name: "Restore Hephaestus default" }));

		await waitFor(() => expect(ifMatch).toBe('"tag-1"'));
		await screen.findByText("Uses Hephaestus default", undefined, ROUTE_RENDER_WAIT);
	});

	it("restores a group's Hephaestus default with the group's tag", async () => {
		const customized = {
			...packaging,
			state: "EDITED_HERE",
			name: "Our packaging",
		} satisfies CatalogGroup;
		mockCatalog({ groups: [customized] });
		server.use(
			http.get("*/admin/practice-catalog/groups/:slug", () => HttpResponse.json(customized)),
		);
		let ifMatch: string | null = null;
		server.use(
			http.delete("*/admin/practice-catalog/groups/:slug/override", ({ request }) => {
				ifMatch = request.headers.get("if-match");
				return HttpResponse.json({ ...packaging, etag: "group-tag-2" });
			}),
		);
		renderRouteAt("/admin/catalog?detail=group-edit:packaging");

		fireEvent.click(
			await screen.findByRole("button", { name: "Restore Hephaestus default" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		fireEvent.click(within(dialog).getByRole("button", { name: "Restore Hephaestus default" }));

		await waitFor(() => expect(ifMatch).toBe('"group-tag-1"'));
	});

	it("shows the Hephaestus version before it is taken", async () => {
		const waiting = {
			...savedPractice,
			state: "UPDATE_WAITING",
			changeKind: "REVIEW",
			shipped: release.offered,
		} satisfies CatalogPractice;
		mockCatalog({ practices: [waiting] });
		servePractice(waiting);
		server.use(http.get(`${PRACTICE_PATH}/release`, () => HttpResponse.json(release)));
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		await screen.findByText("The definition Hephaestus ships now", undefined, ROUTE_RENDER_WAIT);
		screen.getByText("Conflict");
	});

	it("accepts a selected field with the reviewed release tag", async () => {
		const waiting = {
			...savedPractice,
			state: "UPDATE_WAITING",
			changeKind: "REVIEW",
		} satisfies CatalogPractice;
		mockCatalog({ practices: [waiting] });
		servePractice(waiting);
		let ifMatch: string | null = null;
		let body: unknown;
		server.use(
			http.get(`${PRACTICE_PATH}/release`, () => HttpResponse.json(release)),
			http.put(`${PRACTICE_PATH}/release`, async ({ request }) => {
				ifMatch = request.headers.get("if-match");
				body = await request.json();
				return HttpResponse.json({
					...savedPractice,
					definition: release.offered,
					state: "EDITED_HERE",
					etag: "tag-2",
				});
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		const radioGroup = await screen.findByRole(
			"radiogroup",
			{ name: "Use a version for Review criteria" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(within(radioGroup).getByRole("radio", { name: "Proposed" }));
		fireEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));

		await waitFor(() => expect(ifMatch).toBe('"release-tag"'));
		expect(body).toStrictEqual({ choices: { CRITERIA: "OFFERED" } });
	});

	it("refreshes the practice when a release decision is stale", async () => {
		const waiting = { ...savedPractice, state: "UPDATE_WAITING" } satisfies CatalogPractice;
		mockCatalog({ practices: [waiting] });
		servePractice(waiting);
		server.use(
			http.get(`${PRACTICE_PATH}/release`, () => HttpResponse.json(release)),
			http.put(`${PRACTICE_PATH}/release`, () => {
				server.use(
					http.get(PRACTICE_PATH, () =>
						HttpResponse.json({ ...savedPractice, state: "EDITED_HERE" }),
					),
					http.get(`${PRACTICE_PATH}/release`, () => new HttpResponse(null, { status: 404 })),
				);
				return HttpResponse.json({ status: 412, title: "Stale" }, { status: 412 });
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		const radioGroup = await screen.findByRole(
			"radiogroup",
			{ name: "Use a version for Review criteria" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(within(radioGroup).getByRole("radio", { name: "Proposed" }));
		fireEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));

		await screen.findByText("Customized on this instance", undefined, ROUTE_RENDER_WAIT);
		expect(
			screen.queryByRole("radiogroup", { name: "Use a version for Review criteria" }),
		).toBeNull();
	});

	it("lets a waiting update be declined, not only taken", async () => {
		const waiting = {
			...savedPractice,
			state: "UPDATE_WAITING",
			changeKind: "REVIEW",
		} satisfies CatalogPractice;
		mockCatalog({ practices: [waiting] });
		servePractice(waiting);
		let ifMatch: string | null = null;
		server.use(
			http.get(`${PRACTICE_PATH}/release`, () => HttpResponse.json(release)),
			http.put(`${PRACTICE_PATH}/override/acknowledgement`, ({ request }) => {
				ifMatch = request.headers.get("if-match");
				return HttpResponse.json({ ...savedPractice, state: "EDITED_HERE", etag: "tag-2" });
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");
		const name = await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT);
		fireEvent.change(name, { target: { value: "Unsaved draft name" } });

		fireEvent.click(
			await screen.findByRole("button", { name: "Decline this update" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(() => expect(ifMatch).toBe('"release-tag"'));
		await screen.findByText("Customized on this instance", undefined, ROUTE_RENDER_WAIT);
		expect(name).toHaveProperty("value", "Unsaved draft name");
	});

	it("keeps a removed default as a custom practice", async () => {
		const removed = { ...savedPractice, state: "NO_LONGER_SHIPPED" } satisfies CatalogPractice;
		mockCatalog({ practices: [removed] });
		servePractice(removed);
		let ifMatch: string | null = null;
		server.use(
			http.put(`${PRACTICE_PATH}/override/acknowledgement`, ({ request }) => {
				ifMatch = request.headers.get("if-match");
				return HttpResponse.json({ ...savedPractice, state: "YOURS", etag: "tag-2" });
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Keep saved version as custom" },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() => expect(ifMatch).toBe('"tag-1"'));
		await screen.findByText("No Hephaestus default", undefined, ROUTE_RENDER_WAIT);
	});

	it("preserves the draft and refreshes the tag after an edit conflict", async () => {
		mockCatalog({ practices: [savedPractice] });
		let currentEtag = "tag-1";
		let lastIfMatch: string | null = null;
		server.use(
			http.get(PRACTICE_PATH, () => HttpResponse.json({ ...savedPractice, etag: currentEtag })),
			http.put(
				PRACTICE_PATH,
				({ request }) => {
					lastIfMatch = request.headers.get("if-match");
					currentEtag = "tag-2";
					return HttpResponse.json({ status: 412, title: "Stale" }, { status: 412 });
				},
				{ once: true },
			),
			http.put(PRACTICE_PATH, ({ request }) => {
				lastIfMatch = request.headers.get("if-match");
				return HttpResponse.json({ ...savedPractice, etag: "tag-3" });
			}),
		);
		renderRouteAt("/admin/catalog?detail=practice-edit:describe-what-and-why");

		const name = await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT);
		fireEvent.change(name, { target: { value: "My unsaved draft" } });
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await screen.findByText("This practice changed while you were editing");

		fireEvent.click(screen.getByRole("button", { name: "Continue with your draft" }));
		await waitFor(() =>
			expect(screen.getByRole<HTMLInputElement>("textbox", { name: /Name/u }).value).toBe(
				"My unsaved draft",
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await waitFor(() => expect(lastIfMatch).toBe('"tag-2"'));
	});
});
