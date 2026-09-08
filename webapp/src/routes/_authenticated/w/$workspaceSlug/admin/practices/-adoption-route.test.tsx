import { fireEvent, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { CatalogPracticePreview } from "@/api/types.gen";
import { mockPractices } from "@/components/admin/practices/story-mock-data";
import {
	mockAuthorDeclaredEvidenceValidation,
	mockPracticeDefinitionOptions,
	mockPullRequestBinding,
	mockPullRequestPolicy,
} from "@/mocks/fixtures/practice";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const preview: CatalogPracticePreview = {
	slug: "describe-what-and-why",
	availability: "AVAILABLE",
	etag: '"reviewed-plan"',
	initialAutonomy: "HUMAN_APPROVAL",
	sourceReviewRuleFingerprint: mockAuthorDeclaredEvidenceValidation.reviewRuleFingerprint,
	group: {
		slug: "review-ready-work",
		disposition: "CREATE_CATALOG_GROUP",
		definition: { name: "Review-ready work" },
	},
	definition: {
		name: "Describe what changed and why",
		artifactKind: "scm.pull_request",
		bindings: [mockPullRequestBinding],
		automatedReviewPolicy: mockPullRequestPolicy,
		automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
		criteria: "Explain the change and why it is needed.",
		whyItMatters: "Reviewers need intent.",
		whatGoodLooksLike: "A concise summary and motivation.",
		groupSlug: "review-ready-work",
	},
};

/**
 * The preview as it stands, then as it stands after someone else changed it — the shape a 412 is
 * about. A handler is where setup is allowed to branch; a test body is not.
 */
function planChangedAfterTheFirstRead() {
	let reads = 0;
	return http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () => {
		reads += 1;
		const changed = reads > 1;
		return HttpResponse.json({
			...preview,
			definition: {
				...preview.definition,
				criteria: changed
					? "Updated review rule that must be reviewed."
					: preview.definition.criteria,
			},
			etag: changed ? '"updated-plan"' : preview.etag,
		});
	});
}

/** The preview once, and then nothing: the refresh a 412 asks for is itself allowed to fail. */
function planUnreadableAfterTheFirstRead() {
	let reads = 0;
	return http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () => {
		reads += 1;
		return reads === 1
			? HttpResponse.json(preview)
			: HttpResponse.json(
					{ title: "Service Unavailable", status: 503, detail: "Try again later." },
					{ status: 503 },
				);
	});
}

/** A one-level stack survives the readable URL form as well as the encoded array form. */
const LIBRARY = "/w/acme/admin/practices?library=true";
const REVIEWING = `${LIBRARY}&detail=catalog-practice:${preview.slug}`;

const workspacePractice = {
	...mockPractices[0],
	slug: "already-mine",
	name: "Already mine",
	groupSlug: undefined,
};

describe("catalog adoption over practice setup", () => {
	beforeEach(() => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
				HttpResponse.json(mockPracticeDefinitionOptions),
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces", () =>
				HttpResponse.json([
					{
						id: 1,
						workspaceSlug: "acme",
						displayName: "Acme",
						providerType: "GITHUB",
						status: "ACTIVE",
						practicesEnabled: false,
						mentorEnabled: false,
						leaderboardEnabled: false,
						progressionEnabled: false,
						leaguesEnabled: false,
					},
				]),
			),
		);
	});

	it("shows every included practice in the catalog and distinguishes adoption states", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([
					{
						slug: preview.slug,
						name: preview.definition.name,
						artifactKind: "scm.pull_request",
						groupSlug: "review-ready-work",
						availability: "AVAILABLE",
						automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
					},
					{
						slug: "already-there",
						name: "Already there",
						artifactKind: "scm.issue",
						availability: "ADOPTED",
						automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
					},
					{
						slug: "issue-context",
						name: "Include enough issue context",
						artifactKind: "scm.issue",
						availability: "SLUG_CONFLICT",
						automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
					},
				]),
			),
		);

		renderRouteAt(LIBRARY);

		await screen.findByRole("heading", { name: "Instance catalog" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Available")).toBeNull();
		await screen.findByText("Name unavailable");
		screen.getByRole("link", { name: /Describe what changed and why/ });
		screen.getByRole("link", {
			name: /Include enough issue context, see why it cannot be added/,
		});
	});

	it("reviews a practice over the catalog instead of leaving the page", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([
					{
						slug: preview.slug,
						name: preview.definition.name,
						artifactKind: "scm.pull_request",
						groupSlug: "review-ready-work",
						availability: "AVAILABLE",
						automatedReviewValidation: mockAuthorDeclaredEvidenceValidation,
					},
				]),
			),
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(preview),
			),
		);

		const { router } = renderRouteAtWithRouter(LIBRARY);
		fireEvent.click(
			await screen.findByRole("link", { name: /Describe what changed and why/ }, ROUTE_RENDER_WAIT),
		);

		await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT);
		// The catalog is still the page; only the drawer stack changed.
		expect(router.state.location.pathname).toBe("/w/acme/admin/practices");
		expect(router.state.location.search.detail).toStrictEqual([`catalog-practice:${preview.slug}`]);
	});

	it("opens a workspace practice read-only over the tree instead of in the edit form", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practices", () =>
				HttpResponse.json([workspacePractice]),
			),
			// Declared before the `:practiceSlug` handler below, which would otherwise match this path
			// with `practiceSlug === "definition-options"`. MSW takes the first match in order.
			http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
				HttpResponse.json(mockPracticeDefinitionOptions),
			),
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug", () =>
				HttpResponse.json(workspacePractice),
			),
		);

		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices");
		fireEvent.click(await screen.findByRole("link", { name: "Already mine" }, ROUTE_RENDER_WAIT));

		// The definition is readable, and editing is a separate, explicit act: a level on top of it,
		// so leaving the form lands back on the panel it was opened from rather than on the bare tree.
		const edit = await screen.findByRole("link", { name: "Edit practice" }, ROUTE_RENDER_WAIT);
		expect(edit.getAttribute("href")).toBe(
			`/w/acme/admin/practices?detail=${encodeURIComponent(
				'["practice:already-mine","practice-edit:already-mine"]',
			)}`,
		);
		expect(router.state.location.pathname).toBe("/w/acme/admin/practices");
		expect(router.state.location.search.detail).toStrictEqual(["practice:already-mine"]);
	});

	it("pins adoption to the reviewed ETag and returns to the catalog", async () => {
		const seenIfMatch = vi.fn();
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(preview),
			),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", ({ request }) => {
				seenIfMatch(request.headers.get("If-Match"));
				return HttpResponse.json(
					{ ...mockPractices[0], slug: preview.slug, name: preview.definition.name },
					{ status: 201 },
				);
			}),
		);

		const { router } = renderRouteAtWithRouter(REVIEWING);
		fireEvent.click(await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT));

		await waitFor(() => expect(seenIfMatch).toHaveBeenCalledWith(preview.etag));
		// Adding closes the drawer rather than opening the practice form, so the next one is one click away.
		await waitFor(
			() => expect(router.state.location.search.detail).toBeUndefined(),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.pathname).toBe("/w/acme/admin/practices");
		expect(router.state.location.search.library).toBe(true);
	});

	it("requires review again after a 412", async () => {
		const seenIfMatch = vi.fn();
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			planChangedAfterTheFirstRead(),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", ({ request }) => {
				seenIfMatch(request.headers.get("If-Match"));
				return HttpResponse.json(
					{ title: "Precondition Failed", status: 412, detail: "The adoption preview changed." },
					{ status: 412 },
				);
			}),
		);

		renderRouteAt(REVIEWING);
		fireEvent.click(await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT));

		await waitFor(() => expect(seenIfMatch).toHaveBeenCalledWith(preview.etag));
		// `role="alert"` announces the change without pulling focus off the action.
		const changed = await screen.findByRole("alert");
		expect(changed.textContent).toContain("The catalog changed while you were reading");
		// The rule is what a 412 is about, so open the disclosure that holds it and check the panel
		// is showing the refetched one rather than the plan that was just rejected.
		fireEvent.click(await screen.findByRole("button", { name: "How it decides" }));
		await screen.findByText("Updated review rule that must be reviewed.");
	});

	it("does not claim the latest preview is shown when refreshing after a 412 fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			planUnreadableAfterTheFirstRead(),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(
					{ title: "Precondition Failed", status: 412, detail: "The adoption preview changed." },
					{ status: 412 },
				),
			),
		);

		renderRouteAt(REVIEWING);
		fireEvent.click(await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT));

		await screen.findByText("Couldn't load the adoption preview", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("The catalog changed while you were reading")).toBeNull();
	});

	it("says a practice was already added and returns to the catalog", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(preview),
			),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(
					{
						type: "about:blank",
						title: "Conflict",
						status: 409,
						detail: "A workspace practice already uses this slug.",
					},
					{ status: 409 },
				),
			),
		);

		const { router } = renderRouteAtWithRouter(REVIEWING);
		fireEvent.click(await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT));

		// Both halves: closing on its own is what a route that swallowed every failure would also do.
		await screen.findByText("This practice is already in the workspace", {}, ROUTE_RENDER_WAIT);
		await waitFor(
			() => expect(router.state.location.search.detail).toBeUndefined(),
			ROUTE_RENDER_WAIT,
		);
	});

	it("turns the retired editor paths into the level they became", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices/new");
		await waitFor(
			() => expect(router.state.location.pathname).toBe("/w/acme/admin/practices"),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.search.detail).toStrictEqual(["practice-new:draft"]);

		const edited = renderRouteAtWithRouter("/w/acme/admin/practices/already-mine");
		await waitFor(
			() => expect(edited.router.state.location.pathname).toBe("/w/acme/admin/practices"),
			ROUTE_RENDER_WAIT,
		);
		expect(edited.router.state.location.search.detail).toStrictEqual([
			"practice-edit:already-mine",
		]);
	});

	it("creates a practice from the editor level and lands back on the tree", async () => {
		const created = vi.fn();
		server.use(
			http.post("*/workspaces/:workspaceSlug/practices", async ({ request }) => {
				created(await request.json());
				return HttpResponse.json(workspacePractice, { status: 201 });
			}),
		);

		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices?detail=practice-new:draft");
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/ }, ROUTE_RENDER_WAIT), {
			target: { value: "Explain the change" },
		});
		fireEvent.change(screen.getByRole("textbox", { name: /What to look for/ }), {
			target: { value: "Check that the description says why." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));

		await waitFor(() => expect(created).toHaveBeenCalled(), ROUTE_RENDER_WAIT);
		expect(created).toHaveBeenCalledWith(
			expect.objectContaining({ name: "Explain the change", slug: "explain-the-change" }),
		);
		// The level goes; the tree it was opened over is what the reader lands on.
		await waitFor(
			() => expect(router.state.location.search.detail).toBeUndefined(),
			ROUTE_RENDER_WAIT,
		);
	});

	// Escape here, a press on the page in `DetailDrawerStack`'s stories: jsdom does not drive Base
	// UI's outside-press. Both reach the same `onClose`, and this is the half that also owns the
	// guard, so it is the half worth asserting against the real form.
	it("asks before Escape discards a draft, and keeps it when refused", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices?detail=practice-new:draft");
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/ }, ROUTE_RENDER_WAIT), {
			target: { value: "A draft worth keeping" },
		});

		fireEvent.keyDown(document.body, { key: "Escape" });

		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

		// The level is still open and still holds the draft: refusing has to be free.
		expect(router.state.location.search.detail).toStrictEqual(["practice-new:draft"]);
		screen.getByDisplayValue("A draft worth keeping");
	});

	it("discards the draft and leaves when the reader says so", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices?detail=practice-new:draft");
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/ }, ROUTE_RENDER_WAIT), {
			target: { value: "A draft worth losing" },
		});

		fireEvent.keyDown(document.body, { key: "Escape" });
		fireEvent.click(
			await screen.findByRole("button", { name: "Discard changes" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(
			() => expect(router.state.location.search.detail).toBeUndefined(),
			ROUTE_RENDER_WAIT,
		);
	});

	it("leaves a clean editor without asking anything", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices?detail=practice-new:draft");
		await screen.findByRole("textbox", { name: /Name/ }, ROUTE_RENDER_WAIT);

		fireEvent.keyDown(document.body, { key: "Escape" });

		await waitFor(
			() => expect(router.state.location.search.detail).toBeUndefined(),
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});
});
