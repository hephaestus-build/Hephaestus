import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import {
	getPracticeQueryKey,
	getPracticeGuidanceOptions,
	getPracticeDefinitionOptionsQueryKey,
	listPracticeGroupsQueryKey,
	listPracticesQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { definitionOf, mockDefinitionOptions } from "@/components/admin/practice-editor/fixtures";

import type {
	AdoptablePractice,
	PracticeAdoptionPreview,
} from "@/components/admin/practices/catalog-adoption";
import { stackInSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import {
	loadRouteCode,
	ROUTE_RENDER_WAIT,
	renderRouteAt,
	renderRouteAtWithRouter,
	testQueryClient,
} from "@/test/router-harness";

import {
	practiceSetupHandlers,
	practicesHandlers,
	wireGuidance,
	wirePractice,
} from "./-practice-server";

beforeAll(async () => loadRouteCode("/w/acme/admin/practices"), ROUTE_RENDER_WAIT.timeout);

const definition = definitionOf({
	name: "Describe what changed and why",
	criteria: "Explain the change and why it is needed.",
});

const preview: Wire<PracticeAdoptionPreview> = {
	slug: "describe-what-and-why",
	kind: "scm.pull_request",
	availability: "AVAILABLE",
	etag: '"reviewed-plan"',
	initialFeedbackMode: "APPROVE_FIRST",
	offeredDigest: "sha256:describe-what-and-why",
	group: {
		slug: "review-ready-work",
		disposition: "CREATE_CATALOG_GROUP",
		definition: { name: "Review-ready work", description: null, icon: null, color: null },
	},
	definition,
};

function summary(
	slug: string,
	name: string,
	kind: string,
	availability: AdoptablePractice["availability"],
	groupSlug?: string,
): Wire<AdoptablePractice> {
	return {
		slug,
		name,
		kind,
		availability,
		groupSlug,
		groupName: groupSlug === undefined ? undefined : "Review-ready work",
		reviewedBy: { by: "HEPHAESTUS", humanReason: null, limits: [] },
	};
}

function planChangedAfterTheFirstRead() {
	let reads = 0;
	return http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () => {
		reads += 1;
		const changed = reads > 1;
		return HttpResponse.json({
			...preview,
			definition: {
				...definition,
				criteria: changed ? "Updated review rule that must be reviewed." : definition.criteria,
			},
			etag: changed ? '"updated-plan"' : preview.etag,
		});
	});
}

function planUnreadableAfterTheFirstRead() {
	let reads = 0;
	return http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () => {
		reads += 1;
		return reads === 1
			? HttpResponse.json(preview)
			: HttpResponse.json(
					{ title: "Service Unavailable", status: 503, detail: "Try again in a moment." },
					{ status: 503 },
				);
	});
}

/** A one-level stack survives the readable URL form as well as the encoded array form. */
const LIBRARY = "/w/acme/admin/practices?library=true";
const REVIEWING = `${LIBRARY}&detail=catalog-practice:${preview.slug}`;

const workspacePractice = wirePractice({
	slug: "already-mine",
	definition: definitionOf({ name: "Already mine" }),
});

const PRACTICE_PATH = `*/workspaces/acme/practices/${workspacePractice.slug}`;
const DEFINITION_OPTIONS_PATH = "*/workspaces/acme/practices/definition-options";
const GROUPS_PATH = "*/workspaces/acme/practice-groups";
const PRACTICES_PATH = "*/workspaces/acme/practices";
const practiceKey = getPracticeQueryKey({
	path: { workspaceSlug: "acme", practiceSlug: workspacePractice.slug },
});

const unavailable = () =>
	HttpResponse.json({ status: 503, title: "Service Unavailable" }, { status: 503 });

/** Serves every read the practice editor waits for, and names each one answered in `reads`. */
function serveEditorReads() {
	const reads: string[] = [];
	server.use(
		// Before `:practiceSlug`, which would otherwise answer these paths. MSW takes the first match.
		http.get(DEFINITION_OPTIONS_PATH, () => {
			reads.push("definition options");
			return HttpResponse.json(mockDefinitionOptions);
		}),
		http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([])),
		http.get(GROUPS_PATH, () => {
			reads.push("groups");
			return HttpResponse.json([]);
		}),
		http.get(PRACTICES_PATH, () => {
			reads.push("practices");
			return HttpResponse.json([]);
		}),
		http.get(PRACTICE_PATH, () => {
			reads.push("practice");
			return HttpResponse.json(workspacePractice);
		}),
	);
	return reads;
}

async function openEditor(level: string, title: string) {
	const rendered = renderRouteAtWithRouter(`/w/acme/admin/practices?detail=${level}`);
	const dialog = await screen.findByRole("dialog", { name: title }, ROUTE_RENDER_WAIT);
	return { ...rendered, dialog };
}

async function typeDraft(dialog: HTMLElement) {
	fireEvent.change(
		await within(dialog).findByRole("textbox", { name: /^Name/u }, ROUTE_RENDER_WAIT),
		{ target: { value: "Unsaved draft name" } },
	);
}

function groupDraftName() {
	return within(screen.getByRole("dialog", { name: "Create group" })).getByRole<HTMLInputElement>(
		"textbox",
		{ name: "Name" },
	).value;
}

describe("catalog adoption over practice setup", () => {
	beforeEach(() => {
		server.use(...practiceSetupHandlers());
	});

	it("shows every included practice in the catalog and distinguishes adoption states", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([
					summary(
						preview.slug,
						definition.name,
						"scm.pull_request",
						"AVAILABLE",
						"review-ready-work",
					),
					summary("already-there", "Already there", "scm.issue", "ADOPTED"),
					summary("issue-context", "Include enough issue context", "scm.issue", "SLUG_CONFLICT"),
				]),
			),
		);

		renderRouteAt(LIBRARY);

		await screen.findByRole("heading", { name: "Instance catalog" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Available")).toBeNull();
		await screen.findByText("Name in use");
		screen.getByRole("link", { name: /Describe what changed and why/u });
		screen.getByRole("link", {
			name: /Include enough issue context, see why it cannot be added/u,
		});
	});

	it("reviews a practice over the catalog instead of leaving the page", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([
					summary(
						preview.slug,
						definition.name,
						"scm.pull_request",
						"AVAILABLE",
						"review-ready-work",
					),
				]),
			),
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(preview),
			),
		);

		const { router } = renderRouteAtWithRouter(LIBRARY);
		fireEvent.click(
			await screen.findByRole(
				"link",
				{ name: /Describe what changed and why/u },
				ROUTE_RENDER_WAIT,
			),
		);

		await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT);
		// The catalog is still the page; only the drawer stack changed.
		expect(router.state.location.pathname).toBe("/w/acme/admin/practices");
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			`catalog-practice:${preview.slug}`,
		]);
	});

	it("opens a workspace practice read-only over the tree instead of in the edit form", async () => {
		server.use(...practicesHandlers([workspacePractice]));

		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices");
		fireEvent.click(await screen.findByRole("link", { name: "Already mine" }, ROUTE_RENDER_WAIT));

		// The definition is readable, and editing is a separate, explicit act: a level on top of it,
		// so leaving the form lands back on the panel it was opened from rather than on the bare tree.
		const edit = await screen.findByRole("link", { name: "Edit practice" }, ROUTE_RENDER_WAIT);
		expect(edit.getAttribute("href")).toBe(
			"/w/acme/admin/practices?detail=practice:already-mine&detail=practice-edit:already-mine",
		);
		expect(router.state.location.pathname).toBe("/w/acme/admin/practices");
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			"practice:already-mine",
		]);
	});

	it("pins adoption to the reviewed ETag and returns to the catalog", async () => {
		const queryClient = testQueryClient();
		const guidanceOptions = {
			...getPracticeGuidanceOptions({
				path: { workspaceSlug: "acme", practiceSlug: preview.slug },
			}),
			staleTime: 30_000,
		};
		queryClient.setQueryData(guidanceOptions.queryKey, wireGuidance(preview.slug, null));
		const seenIfMatch = vi.fn();
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(preview),
			),
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug/guidance", () =>
				HttpResponse.json(wireGuidance(preview.slug, "Adopted guide")),
			),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", ({ request }) => {
				seenIfMatch(request.headers.get("If-Match"));
				return HttpResponse.json(wirePractice({ slug: preview.slug, definition }), {
					status: 201,
				});
			}),
		);

		const { router } = renderRouteAtWithRouter(REVIEWING, queryClient);
		fireEvent.click(await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT));

		await waitFor(() => expect(seenIfMatch).toHaveBeenCalledWith(preview.etag));
		// Adding closes the drawer rather than opening the practice form, so the next one is one click away.
		await waitFor(
			() => expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([]),
			ROUTE_RENDER_WAIT,
		);
		expect(router.state.location.pathname).toBe("/w/acme/admin/practices");
		expect(router.state.location.search.library).toBe(true);
		await expect(queryClient.query(guidanceOptions)).resolves.toMatchObject({
			guide: { markdown: "Adopted guide" },
		});
	});

	it("requires review again when the plan changed before the add", async () => {
		const seenIfMatch = vi.fn();
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			planChangedAfterTheFirstRead(),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", ({ request }) => {
				seenIfMatch(request.headers.get("If-Match"));
				return HttpResponse.json(
					{
						title: "Practice adoption preview changed",
						status: 412,
						detail: "The preview changed.",
					},
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
		fireEvent.click(await screen.findByRole("button", { name: "How it decides" }));
		await screen.findByText("Updated review rule that must be reviewed.");
	});

	it("does not claim the latest preview is shown when refreshing after a stale precondition fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			planUnreadableAfterTheFirstRead(),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(
					{
						title: "Practice adoption preview changed",
						status: 412,
						detail: "The preview changed.",
					},
					{ status: 412 },
				),
			),
		);

		renderRouteAt(REVIEWING);
		fireEvent.click(await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT));

		await screen.findByText("We could not load the adoption preview", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("The catalog changed while you were reading")).toBeNull();
	});

	it("says a practice was already added and returns to the catalog", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption", () =>
				HttpResponse.json([]),
			),
			http.get(
				"*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug",
				() => HttpResponse.json(preview),
				{ once: true },
			),
			http.get("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json({ ...preview, availability: "ADOPTED" }),
			),
			http.post("*/workspaces/:workspaceSlug/practice-catalog/adoption/:slug", () =>
				HttpResponse.json(
					{
						type: "about:blank",
						title: "Conflict",
						status: 412,
						detail: "A workspace practice already uses this slug.",
					},
					{ status: 412 },
				),
			),
		);

		const { router } = renderRouteAtWithRouter(REVIEWING);
		fireEvent.click(await screen.findByRole("button", { name: "Add practice" }, ROUTE_RENDER_WAIT));

		// Both halves: closing on its own is what a route that swallowed every failure would also do.
		await screen.findByText("This practice is already in the workspace", {}, ROUTE_RENDER_WAIT);
		await waitFor(
			() => expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([]),
			ROUTE_RENDER_WAIT,
		);
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
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT), {
			target: { value: "Explain the change" },
		});
		fireEvent.change(screen.getByRole("textbox", { name: /What to look for/u }), {
			target: { value: "Check that the description says why." },
		});
		fireEvent.click(screen.getByRole("button", { name: "Choose sources" }));
		const details = await screen.findByRole("radiogroup", {
			name: "How Pull request details is read",
		});
		fireEvent.click(within(details).getByRole("radio", { name: "Required" }));
		fireEvent.click(screen.getByRole("button", { name: "Create practice" }));

		await waitFor(() => expect(created).toHaveBeenCalledOnce(), ROUTE_RENDER_WAIT);
		expect(created.mock.lastCall?.[0]).toMatchObject({
			slug: "explain-the-change",
			kind: "scm.pull_request",
			groupSlug: null,
			position: null,
			definition: {
				name: "Explain the change",
				criteria: "Check that the description says why.",
			},
		});
		// The level goes; the tree it was opened over is what the reader lands on.
		await waitFor(
			() => expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([]),
			ROUTE_RENDER_WAIT,
		);
	});

	// Escape here, a press on the page in `DetailDrawerStack`'s stories: jsdom does not drive Base
	// UI's outside-press. Both reach the same `onClose`, and this is the half that also owns the
	// guard, so it is the half worth asserting against the real form.
	it("asks before Escape discards a draft, and keeps it when refused", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices?detail=practice-new:draft");
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT), {
			target: { value: "A draft worth keeping" },
		});

		fireEvent.keyDown(document.body, { key: "Escape" });

		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" }, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

		// The level is still open and still holds the draft: refusing has to be free.
		expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([
			"practice-new:draft",
		]);
		screen.getByDisplayValue("A draft worth keeping");
	});

	it("discards the draft and leaves when the reader says so", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices?detail=practice-new:draft");
		fireEvent.change(await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT), {
			target: { value: "A draft worth losing" },
		});

		fireEvent.keyDown(document.body, { key: "Escape" });
		fireEvent.click(
			await screen.findByRole("button", { name: "Discard changes" }, ROUTE_RENDER_WAIT),
		);

		await waitFor(
			() => expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([]),
			ROUTE_RENDER_WAIT,
		);
	});

	it("leaves a clean editor without asking anything", async () => {
		const { router } = renderRouteAtWithRouter("/w/acme/admin/practices?detail=practice-new:draft");
		await screen.findByRole("textbox", { name: /Name/u }, ROUTE_RENDER_WAIT);

		fireEvent.keyDown(document.body, { key: "Escape" });

		await waitFor(
			() => expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([]),
			ROUTE_RENDER_WAIT,
		);
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	describe("the editor's reads", () => {
		it.each([
			[
				"practice editor",
				"practice",
				`practice-edit:${workspacePractice.slug}`,
				"Edit practice",
				PRACTICE_PATH,
				practiceKey,
			],
			[
				"practice editor",
				"definition options",
				`practice-edit:${workspacePractice.slug}`,
				"Edit practice",
				DEFINITION_OPTIONS_PATH,
				getPracticeDefinitionOptionsQueryKey({ path: { workspaceSlug: "acme" } }),
			],
			[
				"new practice",
				"groups",
				"practice-new:draft",
				"Create practice",
				GROUPS_PATH,
				listPracticeGroupsQueryKey({ path: { workspaceSlug: "acme" } }),
			],
		] as const)(
			"%s keeps the draft when its %s refresh fails, and Retry reads only that again",
			async (_editor, failingRead, level, title, failingPath, queryKey) => {
				const reads = serveEditorReads();
				const { queryClient, router, dialog } = await openEditor(level, title);
				await typeDraft(dialog);
				const draftName = () =>
					within(dialog).getByRole<HTMLInputElement>("textbox", { name: /^Name/u }).value;

				server.use(http.get(failingPath, unavailable, { once: true }));
				await act(async () => {
					await queryClient.invalidateQueries({ queryKey, exact: true });
				});
				const retry = await within(dialog).findByRole("button", { name: "Retry" });
				expect(draftName()).toBe("Unsaved draft name");

				const readsBeforeRetry = reads.length;
				fireEvent.click(retry);
				await waitFor(() =>
					expect(within(dialog).queryByRole("button", { name: /^Retry/u })).toBeNull(),
				);
				expect(draftName()).toBe("Unsaved draft name");
				expect(reads.slice(readsBeforeRetry)).toStrictEqual([failingRead]);
				expect(stackInSearch(router.state.location.search.detail)).toStrictEqual([level]);
			},
		);

		it("hides a refused practice until an authorized read returns", async () => {
			serveEditorReads();
			const served: number[] = [];
			const { queryClient, dialog } = await openEditor(
				`practice-edit:${workspacePractice.slug}`,
				"Edit practice",
			);
			await typeDraft(dialog);
			server.use(
				http.get(
					PRACTICE_PATH,
					() => {
						served.push(403);
						return HttpResponse.json({ status: 403, title: "Forbidden" }, { status: 403 });
					},
					{ once: true },
				),
				http.get(
					PRACTICE_PATH,
					() => {
						served.push(503);
						return unavailable();
					},
					{ once: true },
				),
				http.get(PRACTICE_PATH, () => {
					served.push(200);
					return HttpResponse.json({
						...workspacePractice,
						name: "Renamed elsewhere",
						currentVersion: {
							...workspacePractice.currentVersion,
							definition: {
								...workspacePractice.currentVersion.definition,
								name: "Renamed elsewhere",
							},
						},
					});
				}),
			);
			const refreshPractice = async () => {
				await act(async () => {
					await queryClient.invalidateQueries({ queryKey: practiceKey, exact: true });
				});
			};

			await refreshPractice();
			await within(dialog).findByText(/Ask a workspace admin or instance admin for access/u);
			expect(within(dialog).queryByRole("textbox", { name: /^Name/u })).toBeNull();
			expect(within(dialog).queryByRole("button", { name: "Save changes" })).toBeNull();
			expect(within(dialog).queryByRole("button", { name: "Retry" })).toBeNull();
			await waitFor(() => expect(queryClient.getQueryData(practiceKey)).toBeUndefined());

			await refreshPractice();
			const retry = await within(dialog).findByRole("button", { name: "Retry" });
			expect(within(dialog).queryByRole("textbox", { name: /^Name/u })).toBeNull();
			expect(within(dialog).queryByRole("button", { name: "Save changes" })).toBeNull();

			fireEvent.click(retry);
			const name = await within(dialog).findByRole<HTMLInputElement>("textbox", {
				name: /^Name/u,
			});
			expect(name.value).toBe("Renamed elsewhere");
			expect(served).toStrictEqual([403, 503, 200]);
		});

		it("keeps a new group's name through a failed practices refresh and the read after it", async () => {
			const reads = serveEditorReads();
			const queryClient = renderRouteAt("/w/acme/admin/practices");
			fireEvent.click(
				await screen.findByRole("button", { name: "Create group" }, ROUTE_RENDER_WAIT),
			);
			const dialog = await screen.findByRole("dialog", { name: "Create group" });
			fireEvent.change(within(dialog).getByRole("textbox", { name: "Name" }), {
				target: { value: "Unsaved group name" },
			});
			const refreshPractices = async () => {
				await act(async () => {
					await queryClient.invalidateQueries({
						queryKey: listPracticesQueryKey({ path: { workspaceSlug: "acme" } }),
						exact: true,
					});
				});
			};

			server.use(http.get(PRACTICES_PATH, unavailable, { once: true }));
			await refreshPractices();
			expect(groupDraftName()).toBe("Unsaved group name");

			const readsBeforeRefresh = reads.length;
			await refreshPractices();
			expect(groupDraftName()).toBe("Unsaved group name");
			expect(reads.slice(readsBeforeRefresh)).toStrictEqual(["practices"]);
		});

		it.each([
			[
				"practice editor",
				"practice",
				`practice-edit:${workspacePractice.slug}`,
				"Edit practice",
				PRACTICE_PATH,
			],
			[
				"new practice",
				"definition options",
				"practice-new:draft",
				"Create practice",
				DEFINITION_OPTIONS_PATH,
			],
		] as const)(
			"%s offers Retry when its first %s read fails",
			async (_editor, _failingRead, level, title, failingPath) => {
				serveEditorReads();
				const failed: number[] = [];
				server.use(
					http.get(
						failingPath,
						() => {
							failed.push(503);
							return unavailable();
						},
						{ once: true },
					),
				);
				const { dialog } = await openEditor(level, title);
				await waitFor(() => expect(failed).toStrictEqual([503]), ROUTE_RENDER_WAIT);

				const retry = await within(dialog).findByRole("button", { name: "Retry" });
				expect(within(dialog).queryByRole("textbox", { name: /^Name/u })).toBeNull();

				fireEvent.click(retry);
				await within(dialog).findByRole("textbox", { name: /^Name/u });
			},
		);
	});
});
