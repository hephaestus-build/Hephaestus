import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { delay, HttpResponse, http, type PathParams } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { CuratedPracticeRequest, PracticeReleaseProposal } from "@/api/types.gen";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockConversationWorkType,
	mockPracticeDefinitionOptions,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { server } from "@/mocks/server";
import { precedes } from "@/test/dom";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const status = (overrides: Record<string, unknown> = {}) => ({
	etag: "tag-1",
	state: "FROM_HEPHAESTUS",
	changeKind: "NONE",
	offered: true,
	...overrides,
});

const groupDefinition = {
	name: "Packaging work",
	description: "Make a change cheap to review",
	icon: "Package",
	color: "sky",
};
const practiceDefinition = {
	name: "Say what changed and why",
	artifactKind: "scm.pull_request",
	bindings: [mockPullRequestBinding],
	criteria: "Our own criteria",
	whyItMatters: "Reviewers need context",
	automatedReviewPolicy: mockPullRequestPolicy,
	automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
	deliveryBehavior: { summaryOnly: false },
};

const release: PracticeReleaseProposal = {
	slug: "describe-what-and-why",
	base: {
		name: practiceDefinition.name,
		bindings: practiceDefinition.bindings,
		criteria: "Earlier criteria",
		automatedReviewPolicy: mockPullRequestPolicy,
		deliveryBehavior: { summaryOnly: false },
	},
	current: {
		name: practiceDefinition.name,
		bindings: practiceDefinition.bindings,
		criteria: practiceDefinition.criteria,
		automatedReviewPolicy: mockPullRequestPolicy,
		deliveryBehavior: { summaryOnly: false },
	},
	offered: {
		name: practiceDefinition.name,
		bindings: practiceDefinition.bindings,
		criteria: "The definition Hephaestus ships now",
		automatedReviewPolicy: mockPullRequestPolicy,
		deliveryBehavior: { summaryOnly: false },
	},
	baseSource: "EXACT_ADOPTION",
	offeredDigest: "offered-digest",
	etag: "release-tag",
	fields: [{ field: "CRITERIA", offeredChanged: true, conflict: true }],
};

function mockCatalog(overrides: Record<string, unknown> = {}) {
	const catalog = {
		etag: "structure-1",
		customOrder: false,
		summary: {
			total: 2,
			updatesChangingDetection: 1,
			updatesChangingWordingOnly: 0,
			updatesChangingPresentation: 0,
			editedHere: 1,
			yours: 0,
			notOffered: 0,
			noLongerShipped: 0,
		},
		groups: [{ slug: "packaging", position: 0, definition: groupDefinition, status: status() }],
		practices: [
			{
				slug: "describe-what-and-why",
				name: practiceDefinition.name,
				artifactKind: "scm.pull_request",
				automatedReview: mockPullRequestPolicy.automatedReview,
				groupSlug: "packaging",
				position: 0,
				effectivelyOffered: true,
				status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }),
			},
		],
		...overrides,
	};
	server.use(http.get("*/admin/practice-catalog", () => HttpResponse.json(catalog)));
	server.use(
		http.get("*/admin/practice-catalog/definition-options", () =>
			HttpResponse.json(mockPracticeDefinitionOptions),
		),
	);
	return catalog;
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

	it("reorders groups with the catalog tag", async () => {
		const secondGroup = {
			slug: "delivery",
			position: 1,
			definition: { ...groupDefinition, name: "Delivery" },
			status: status(),
		};
		mockCatalog({
			groups: [
				{ slug: "packaging", position: 0, definition: groupDefinition, status: status() },
				secondGroup,
			],
		});
		let ifMatch: string | null = null;
		let body: unknown;
		server.use(
			http.patch("*/admin/practice-catalog/groups/reorder", async ({ request }) => {
				ifMatch = request.headers.get("if-match");
				body = await request.json();
				return HttpResponse.json({
					etag: "structure-2",
					summary: {
						total: 2,
						updatesChangingDetection: 0,
						updatesChangingWordingOnly: 0,
						updatesChangingPresentation: 0,
						editedHere: 0,
						yours: 0,
						notOffered: 0,
						noLongerShipped: 0,
					},
					groups: [
						secondGroup,
						{ slug: "packaging", position: 1, definition: groupDefinition, status: status() },
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

		await waitFor(() => expect(ifMatch).toBe('"structure-1"'));
		expect(body).toStrictEqual({ orderedSlugs: ["delivery", "packaging"] });
	});

	it("restores the Hephaestus order with the catalog tag", async () => {
		const catalog = mockCatalog({ customOrder: true });
		let ifMatch: string | null = null;
		server.use(
			http.delete("*/admin/practice-catalog/order", ({ request }) => {
				ifMatch = request.headers.get("If-Match");
				return HttpResponse.json({ ...catalog, customOrder: false, etag: "structure-2" });
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole("button", { name: "Use Hephaestus order" }, ROUTE_RENDER_WAIT),
		);
		const dialog = await screen.findByRole("alertdialog");
		fireEvent.click(within(dialog).getByRole("button", { name: "Use Hephaestus order" }));

		await waitFor(() => expect(ifMatch).toBe('"structure-1"'));
	});

	it("restores the order and focus after a reorder conflict", async () => {
		const secondGroup = {
			slug: "delivery",
			position: 1,
			definition: { ...groupDefinition, name: "Delivery" },
			status: status(),
		};
		mockCatalog({
			groups: [
				{ slug: "packaging", position: 0, definition: groupDefinition, status: status() },
				secondGroup,
			],
		});
		server.use(
			http.patch("*/admin/practice-catalog/groups/reorder", async () => {
				await delay(100);
				return HttpResponse.json({ status: 412, title: "Stale" }, { status: 412 });
			}),
		);
		renderRouteAt("/admin/catalog");

		const packagingHeading = await screen.findByText(
			"Packaging work",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		const deliveryHeading = screen.getByText("Delivery");
		expect(precedes(packagingHeading, deliveryHeading)).toBe(true);
		const trigger = await screen.findByRole(
			"button",
			{ name: "More actions for Packaging work" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(trigger);
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
		await waitFor(() => expect(document.activeElement).toBe(trigger));
	});

	it("loads the new group when a moved practice editor is reopened", async () => {
		const deliveryGroup = {
			slug: "delivery",
			position: 1,
			definition: { ...groupDefinition, name: "Delivery" },
			status: status(),
		};
		const catalog = mockCatalog({
			groups: [
				{ slug: "packaging", position: 0, definition: groupDefinition, status: status() },
				deliveryGroup,
			],
		});
		let latestGroup = "packaging";
		server.use(
			http.get("*/admin/practice-catalog/practices/:slug", () =>
				HttpResponse.json({
					slug: "describe-what-and-why",
					definition: { ...practiceDefinition, groupSlug: latestGroup },
					status: status(),
				}),
			),
			http.patch("*/admin/practice-catalog/practices/:slug/placement", () => {
				latestGroup = "delivery";
				return HttpResponse.json({
					...catalog,
					etag: "structure-2",
					practices: catalog.practices.map((practice) => ({
						...practice,
						groupSlug: "delivery",
					})),
				});
			}),
		);
		renderRouteAt("/admin/catalog/practices/describe-what-and-why");

		fireEvent.click(await screen.findByRole("button", { name: "Cancel" }, ROUTE_RENDER_WAIT));
		fireEvent.click(
			await screen.findByRole(
				"button",
				{ name: `More actions for ${practiceDefinition.name}` },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(await screen.findByRole("menuitemradio", { name: "Delivery" }));
		await waitFor(() => expect(latestGroup).toBe("delivery"));

		fireEvent.click(await screen.findByRole("link", { name: practiceDefinition.name }));
		const group = await screen.findByRole(
			"combobox",
			{ name: "Practice group" },
			ROUTE_RENDER_WAIT,
		);
		expect(group.textContent).toContain("Delivery");
	});

	it("explains which practices group exclusion affects", async () => {
		const catalog = mockCatalog();
		let ifMatch: string | null = null;
		server.use(
			http.patch("*/admin/practice-catalog/groups/:slug/status", ({ request }) => {
				ifMatch = request.headers.get("if-match");
				return HttpResponse.json({
					...catalog,
					etag: "structure-2",
					groups: catalog.groups.map((group) => ({
						...group,
						status: status({ offered: false }),
					})),
				});
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Offer Packaging work to workspaces" },
				ROUTE_RENDER_WAIT,
			),
		);
		const confirmation = screen.getByRole("alertdialog");
		within(confirmation).getByText(/also stops offering 1 currently offered practice/u);
		within(confirmation).getByText("Say what changed and why");
		fireEvent.click(within(confirmation).getByRole("button", { name: "Stop offering" }));

		await waitFor(() => expect(ifMatch).toBe('"structure-1"'));
	});

	it("prevents a second catalog write while a group update is pending", async () => {
		const catalog = mockCatalog({
			customOrder: true,
			groups: [
				{ slug: "packaging", position: 0, definition: groupDefinition, status: status() },
				{
					slug: "delivery",
					position: 1,
					definition: { ...groupDefinition, name: "Delivery" },
					status: status(),
				},
			],
		});
		server.use(
			http.patch("*/admin/practice-catalog/groups/:slug/status", async () => {
				await delay(500);
				return HttpResponse.json({
					...catalog,
					etag: "structure-2",
					groups: catalog.groups.map((group) => ({
						...group,
						status: status({ offered: false }),
					})),
				});
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Offer Packaging work to workspaces" },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Stop offering" }));

		await waitFor(() =>
			expect(
				screen
					.getByRole("switch", { name: "Offer Delivery to workspaces" })
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

	it("sends the entry's tag when it stops offering a practice", async () => {
		mockCatalog();
		let ifMatch: string | null = null;
		let body: unknown;
		server.use(
			http.patch("*/admin/practice-catalog/practices/:slug/status", async ({ request }) => {
				ifMatch = request.headers.get("if-match");
				body = await request.json();
				return HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					status: status({ offered: false }),
				});
			}),
		);
		renderRouteAt("/admin/catalog");

		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Offer Say what changed and why to workspaces" },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Stop offering" }));

		await waitFor(() => expect(ifMatch).toBe('"tag-1"'));
		expect(body).toStrictEqual({ status: "RETIRED" });
	});

	it("uses the status response tag when an inactive editor is reopened", async () => {
		mockCatalog();
		let latestTag = "tag-1";
		let updateIfMatch: string | null = null;
		server.use(
			http.get("*/admin/practice-catalog/practices/:slug", () =>
				HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					status: status({ etag: latestTag }),
				}),
			),
			http.patch("*/admin/practice-catalog/practices/:slug/status", () => {
				latestTag = "tag-2";
				return HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					status: status({ etag: latestTag, offered: false }),
				});
			}),
			http.put("*/admin/practice-catalog/practices/:slug", ({ request }) => {
				updateIfMatch = request.headers.get("if-match");
				return HttpResponse.json({
					slug: "describe-what-and-why",
					definition: { ...practiceDefinition, name: "Updated name" },
					status: status({ etag: "tag-3", offered: false }),
				});
			}),
		);
		renderRouteAt("/admin/catalog/practices/describe-what-and-why");

		fireEvent.click(await screen.findByRole("button", { name: "Cancel" }, ROUTE_RENDER_WAIT));
		fireEvent.click(
			await screen.findByRole(
				"switch",
				{ name: "Offer Say what changed and why to workspaces" },
				ROUTE_RENDER_WAIT,
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Stop offering" }));
		await waitFor(() => expect(latestTag).toBe("tag-2"));

		fireEvent.click(await screen.findByRole("link", { name: practiceDefinition.name }));
		const name = await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT);
		fireEvent.change(name, { target: { value: "Updated name" } });
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await waitFor(() => expect(updateIfMatch).toBe('"tag-2"'));
	});

	it("shows the Hephaestus version before it is taken", async () => {
		mockCatalog();
		server.use(
			http.get("*/admin/practice-catalog/practices/:slug", () =>
				HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					shipped: { ...practiceDefinition, criteria: "The definition Hephaestus ships now" },
					status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }),
				}),
			),
			http.get("*/admin/practice-catalog/practices/:slug/release", () =>
				HttpResponse.json(release),
			),
		);
		renderRouteAt("/admin/catalog/practices/describe-what-and-why");

		await screen.findByText("The definition Hephaestus ships now", undefined, ROUTE_RENDER_WAIT);
		screen.getByText("Conflict");
	});

	it("accepts a selected field with the reviewed release tag", async () => {
		mockCatalog();
		let ifMatch: string | null = null;
		let body: unknown;
		server.use(
			http.get("*/admin/practice-catalog/practices/:slug", () =>
				HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					shipped: { ...practiceDefinition, criteria: "Hephaestus criteria" },
					status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }),
				}),
			),
			http.get("*/admin/practice-catalog/practices/:slug/release", () =>
				HttpResponse.json(release),
			),
			http.put("*/admin/practice-catalog/practices/:slug/release", async ({ request }) => {
				ifMatch = request.headers.get("if-match");
				body = await request.json();
				return HttpResponse.json({
					slug: "describe-what-and-why",
					definition: { ...practiceDefinition, criteria: release.offered.criteria },
					status: status({ state: "EDITED_HERE", etag: "tag-2" }),
				});
			}),
		);
		renderRouteAt("/admin/catalog/practices/describe-what-and-why");

		const radioGroup = await screen.findByRole(
			"radiogroup",
			{ name: "Use a version for Review criteria" },
			ROUTE_RENDER_WAIT,
		);
		fireEvent.click(within(radioGroup).getByRole("radio", { name: "Offered" }));
		fireEvent.click(screen.getByRole("button", { name: "Accept selected fields" }));

		await waitFor(() => expect(ifMatch).toBe('"release-tag"'));
		expect(body).toStrictEqual({ choices: { CRITERIA: "OFFERED" } });
	});

	it("lets a waiting update be declined, not only taken", async () => {
		mockCatalog();
		let ifMatch: string | null = null;
		server.use(
			http.get("*/admin/practice-catalog/practices/:slug", () =>
				HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					shipped: { ...practiceDefinition, criteria: "The definition Hephaestus ships now" },
					status: status({ state: "UPDATE_WAITING", changeKind: "DETECTION" }),
				}),
			),
			http.get("*/admin/practice-catalog/practices/:slug/release", () =>
				HttpResponse.json(release),
			),
			http.delete("*/admin/practice-catalog/practices/:slug/release", ({ request }) => {
				ifMatch = request.headers.get("if-match");
				return HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					status: status({ state: "EDITED_HERE", etag: "tag-2" }),
				});
			}),
		);
		renderRouteAt("/admin/catalog/practices/describe-what-and-why");
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
		mockCatalog();
		let ifMatch: string | null = null;
		server.use(
			http.get("*/admin/practice-catalog/practices/:slug", () =>
				HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					status: status({ state: "NO_LONGER_SHIPPED" }),
				}),
			),
			http.put(
				"*/admin/practice-catalog/practices/:slug/override/acknowledgement",
				({ request }) => {
					ifMatch = request.headers.get("if-match");
					return HttpResponse.json({
						slug: "describe-what-and-why",
						definition: practiceDefinition,
						status: status({ state: "YOURS", etag: "tag-2" }),
					});
				},
			),
		);
		renderRouteAt("/admin/catalog/practices/describe-what-and-why");

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
		mockCatalog();
		let currentEtag = "tag-1";
		let lastIfMatch: string | null = null;
		const PRACTICE_PATH = "*/admin/practice-catalog/practices/:slug";
		server.use(
			http.get(PRACTICE_PATH, () =>
				HttpResponse.json({
					slug: "describe-what-and-why",
					definition: practiceDefinition,
					status: status({ etag: currentEtag }),
				}),
			),
			http.put(
				PRACTICE_PATH,
				({ request }) => {
					lastIfMatch = request.headers.get("if-match");
					// Somebody else's write landed first, and it is their tag the reload has to pick up.
					currentEtag = "tag-2";
					return HttpResponse.json({ status: 412, title: "Stale" }, { status: 412 });
				},
				{ once: true },
			),
			http.put(PRACTICE_PATH, ({ request }) => {
				lastIfMatch = request.headers.get("if-match");
				return HttpResponse.json({
					slug: "describe-what-and-why",
					definition: { ...practiceDefinition, name: "My unsaved draft" },
					status: status({ etag: "tag-3" }),
				});
			}),
		);
		renderRouteAt("/admin/catalog/practices/describe-what-and-why");

		const name = await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT);
		fireEvent.change(name, { target: { value: "My unsaved draft" } });
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
		await screen.findByText("This practice changed while you were editing");

		fireEvent.click(screen.getByRole("button", { name: "Continue with my draft" }));
		await waitFor(() =>
			expect(screen.getByRole<HTMLInputElement>("textbox", { name: /Name/u }).value).toBe(
				"My unsaved draft",
			),
		);
		fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await waitFor(() => expect(lastIfMatch).toBe('"tag-2"'));
	});

	it.each([
		[
			"unchanged",
			async () => {
				/* keep the pull request the route loaded */
			},
			mockPullRequestPolicy,
			mockPullRequestBinding.signals,
		],
		[
			"changed",
			async () => {
				await userEvent.setup().click(screen.getByRole("radio", { name: /Conversation/u }));
			},
			mockConversationWorkType.recommendedPolicy,
			["chat.conversation_thread.settled"],
		],
	] as const)(
		"%s artifact sends the visible review rule",
		async (_label, chooseArtifact, expectedPolicy, expectedSignals) => {
			mockCatalog();
			let requestBody: CuratedPracticeRequest | undefined;
			server.use(
				http.get("*/admin/practice-catalog/practices/:slug", () =>
					HttpResponse.json({
						slug: "describe-what-and-why",
						definition: practiceDefinition,
						status: status(),
					}),
				),
				http.put<PathParams, CuratedPracticeRequest>(
					"*/admin/practice-catalog/practices/:slug",
					async ({ request }) => {
						requestBody = await request.json();
						return HttpResponse.json({
							slug: "describe-what-and-why",
							definition: practiceDefinition,
							status: status({ etag: "tag-2" }),
						});
					},
				),
			);
			renderRouteAt("/admin/catalog/practices/describe-what-and-why");

			await screen.findByRole("button", { name: "Save changes" }, ROUTE_RENDER_WAIT);
			await chooseArtifact();
			fireEvent.click(screen.getByRole("button", { name: "Save changes" }));

			await waitFor(() => expect(requestBody).toBeDefined());
			expect(requestBody?.automatedReviewPolicy).toStrictEqual(expectedPolicy);
			// The kind of work is read off the signals, so switching it has to rewrite the occasions
			// rather than send a kind alongside bindings that still name the old one.
			expect(requestBody?.bindings.map((binding) => binding.signals)).toStrictEqual([
				[...expectedSignals],
			]);
		},
	);
});
