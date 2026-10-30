import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http, type PathParams } from "msw";
import { assert, describe, expect, it, vi } from "vitest";

import { listWorkspacesQueryKey } from "@/api/@tanstack/react-query.gen";
import type {
	PracticeGroup,
	PracticeReviewSettings,
	ReviewSchedule,
	SetFeedbackModeRequest,
	SetReviewScheduleEnabledRequest,
	UpdatePracticeReviewSettingsRequest,
	UpdateWorkspaceFeaturesRequest,
	WorkspaceMembership,
	WorkspaceReviewScope,
} from "@/api/types.gen";
import { buildFeedbackModeFixture } from "@/components/admin/practices/practice-feedback-mode/fixtures";
import {
	adaAccount,
	reviewSchedule,
	reviewSettings,
} from "@/components/admin/practices/review/fixtures";
import { currentUser } from "@/mocks/fixtures/auth";
import { workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt, renderRouteAtWithRouter } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const fixture = buildFeedbackModeFixture({
	workspaceDefault: "APPROVE_FIRST",
	groups: [
		{
			slug: "hygiene",
			name: "Hygiene",
			practices: [{ name: "States the motivation" }, { name: "Links the issue", override: "OFF" }],
		},
	],
});

const membership = (overrides: Partial<WorkspaceMembership>): WorkspaceMembership => ({
	account: adaAccount,
	role: "ADMIN",
	joinedAt: new Date("2026-01-01T00:00:00Z"),
	leftAt: null,
	hidden: false,
	reviewSelected: true,
	eligibleForPracticeReview: true,
	publicActivityAnswered: null,
	...overrides,
});

const unclaimed = membership({
	role: "MEMBER",
	account: {
		id: "9",
		status: "UNCLAIMED",
		displayName: null,
		avatarUrl: null,
		identities: [{ provider: "GITLAB", login: "grace", displayName: null, avatarUrl: null }],
	},
});

/** `extra` goes first: MSW answers with the first handler that matches, so a test can override one. */
function stubWorkspace(extra: Parameters<typeof server.use>) {
	server.use(
		...extra,
		http.get("*/workspaces/:workspaceSlug/members/me", () => HttpResponse.json(membership({}))),
		http.get("*/workspaces/:workspaceSlug", () =>
			HttpResponse.json({
				id: 1,
				slug: "acme",
				displayName: "Acme",
				practicesEnabled: true,
				practiceReviewAutoTriggerEnabled: true,
				practiceReviewManualTriggerEnabled: true,
			}),
		),
		http.get("*/workspaces/:workspaceSlug/agents", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/members", () =>
			HttpResponse.json({
				content: [membership({}), unclaimed],
				number: 0,
				size: 100,
				totalElements: 2,
				totalPages: 1,
				last: true,
				first: true,
				empty: false,
				numberOfElements: 2,
			}),
		),
		http.get("*/workspaces/:workspaceSlug/practices/items", () =>
			HttpResponse.json({
				content: [
					{
						id: "101",
						kind: "scm.repository",
						label: "owner/repo",
						title: null,
						container: null,
						parentId: null,
						path: null,
						provider: "GITHUB",
						removedAt: null,
						url: null,
					},
				],
			}),
		),
		http.get("*/workspaces/:workspaceSlug/practices/review-schedules", () => HttpResponse.json([])),
		http.get("*/workspaces/:workspaceSlug/practices/review-batches", () =>
			HttpResponse.json({ content: [] }),
		),
		http.get("*/workspaces/:workspaceSlug/practices/review-settings", () =>
			HttpResponse.json(fixture.settings),
		),
		http.get("*/workspaces/:workspaceSlug/practices/feedback-modes", () =>
			HttpResponse.json(fixture.rollup),
		),
		http.get("*/workspaces/:workspaceSlug/practice-groups", () =>
			HttpResponse.json(fixture.groups),
		),
		http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json(fixture.practices)),
	);
}

function stubSettings(initial: PracticeReviewSettings) {
	let settings = initial;
	const previews: WorkspaceReviewScope[] = [];
	const patches: { body: UpdatePracticeReviewSettingsRequest; etag: string | null }[] = [];
	const handlers = (widens: boolean) => [
		http.get("*/workspaces/:workspaceSlug/practices/review-settings", () =>
			HttpResponse.json(settings),
		),
		http.post<PathParams, WorkspaceReviewScope>(
			"*/workspaces/:workspaceSlug/practices/review-settings/scope-preview",
			async ({ request }) => {
				previews.push(await request.json());
				return HttpResponse.json({
					current: settings.scopeSummary,
					proposed: { ...settings.scopeSummary, selectedRepositories: 3 },
					widens,
				});
			},
		),
		http.patch<PathParams, UpdatePracticeReviewSettingsRequest>(
			"*/workspaces/:workspaceSlug/practices/review-settings",
			async ({ request }) => {
				const body = await request.json();
				patches.push({ body, etag: request.headers.get("If-Match") });
				settings = {
					...settings,
					etag: `"${patches.length}"`,
					reviewScope: body.reviewScope ?? settings.reviewScope,
					generatedPaths: { ...settings.generatedPaths, ...body.generatedPaths },
				};
				return HttpResponse.json(settings);
			},
		),
	];
	return { previews, patches, handlers };
}

async function openPractice(name: string): Promise<HTMLElement> {
	const group = await screen.findByRole("button", { name: /^Hygiene/u }, ROUTE_RENDER_WAIT);
	if (group.getAttribute("aria-expanded") !== "true") {
		await userEvent.click(group);
	}
	const label = await screen.findByText(name);
	const row = label.closest("li");
	assert(row instanceof HTMLElement, "Practice row not rendered");
	return row;
}

describe("review route", () => {
	it("keeps an instance administrator's target chrome and review origin changes scoped to that workspace", async () => {
		const changes: { slug: string; body: unknown }[] = [];
		let target = {
			workspaceSlug: "tutors",
			displayName: "Tutors",
			providerType: "GITLAB",
			practicesEnabled: true,
			practiceReviewAutoTriggerEnabled: true,
			practiceReviewManualTriggerEnabled: true,
		};
		stubWorkspace([
			http.get("*/user", () => HttpResponse.json(currentUser)),
			http.get("*/workspaces", () => HttpResponse.json([workspaceListItem("acme")])),
			http.get("*/workspaces/tutors/members/me", () =>
				HttpResponse.json({ status: 403 }, { status: 403 }),
			),
			http.get("*/workspaces/tutors", () => HttpResponse.json(target)),
			http.patch<PathParams, UpdateWorkspaceFeaturesRequest>(
				"*/workspaces/:workspaceSlug/features",
				async ({ params, request }) => {
					const body = await request.json();
					changes.push({ slug: String(params.workspaceSlug), body });
					target = { ...target, ...body };
					return HttpResponse.json(target);
				},
			),
		]);
		const { router, queryClient } = renderRouteAtWithRouter(
			"/w/tutors/admin/practices/review?section=automated-review",
		);
		await router.load();
		expect(router.state.location.pathname).toBe("/w/tutors/admin/practices/review");
		await screen.findByRole("heading", { name: "Review settings" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("link", { name: "Back to instance workspaces" }, ROUTE_RENDER_WAIT);
		screen.getByText("Tutors administration");
		expect(screen.queryByRole("link", { name: "Hephaestus" })).toBeNull();
		const sidebarSettings = screen.getByRole("link", { name: "Workspace settings" });
		expect(sidebarSettings.getAttribute("href")).toBe("/w/tutors/admin/settings");
		const automatic = await screen.findByRole(
			"switch",
			{ name: "Start from work activity" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(automatic);
		await waitFor(() => expect(changes).toHaveLength(1));
		expect(changes[0]).toStrictEqual({
			slug: "tutors",
			body: { practiceReviewAutoTriggerEnabled: false },
		});
		await userEvent.click(screen.getByRole("switch", { name: "Start requested reviews" }));
		await waitFor(() => expect(changes).toHaveLength(2));
		expect(changes[1]).toStrictEqual({
			slug: "tutors",
			body: { practiceReviewManualTriggerEnabled: false },
		});
		expect(target.practicesEnabled).toBe(true);
		expect(queryClient.getQueryData(listWorkspacesQueryKey())).toStrictEqual([
			workspaceListItem("acme"),
		]);
	});

	it("answers 'what is this workspace doing' from the rollup rather than from the rows", async () => {
		stubWorkspace([]);

		renderRouteAt("/w/acme/admin/practices/review");

		await screen.findByRole("heading", { name: "Review settings" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("button", { name: /^Hygiene/u }, ROUTE_RENDER_WAIT);

		screen.getByText(
			"2 practices: 1 set to off and 1 set to approve first. 1 practice set by hand.",
		);
		expect(screen.queryByText("States the motivation")).toBeNull();
	});

	// Unmounted section elements run no hooks; a closed section must not fetch its data.
	it("asks for a section's data only once its tab is opened", async () => {
		const requested: string[] = [];
		const recorded = (path: string, body: Parameters<typeof HttpResponse.json>[0]) =>
			http.get(path, ({ request }) => {
				requested.push(new URL(request.url).pathname);
				return HttpResponse.json(body);
			});
		stubWorkspace([
			recorded("*/workspaces/:workspaceSlug/practices/feedback-modes", fixture.rollup),
			recorded("*/workspaces/:workspaceSlug/practices/review-batches", { content: [] }),
		]);

		renderRouteAt("/w/acme/admin/practices/review?section=past-work");

		await waitFor(
			() => expect(requested.some((path) => path.endsWith("/review-batches"))).toBe(true),
			ROUTE_RENDER_WAIT,
		);
		expect(requested.some((path) => path.endsWith("/feedback-modes"))).toBe(false);

		await userEvent.click(screen.getByRole("tab", { name: "Feedback mode" }));

		await waitFor(
			() => expect(requested.some((path) => path.endsWith("/feedback-modes"))).toBe(true),
			ROUTE_RENDER_WAIT,
		);
	});

	it("opens the section a deep link names", async () => {
		stubWorkspace([]);

		renderRouteAt("/w/acme/admin/practices/review?section=past-work");

		const tab = await screen.findByRole("tab", { name: "Past work" }, ROUTE_RENDER_WAIT);
		expect(tab.getAttribute("aria-selected")).toBe("true");
		expect(screen.queryByRole("tabpanel", { name: "Feedback mode" })).toBeNull();
	});

	it("opens sending feedback in the Feedback mode section that a link names, and saves it there", async () => {
		const settings = stubSettings(reviewSettings({ deliverToMergedOverride: true }));
		stubWorkspace(settings.handlers(false));

		renderRouteAt("/w/acme/admin/practices/review?section=feedback-mode#feedback-delivery-heading");

		const panel = await screen.findByRole("tabpanel", { name: "Feedback mode" }, ROUTE_RENDER_WAIT);
		await within(panel).findByRole("heading", { name: "Sending feedback" }, ROUTE_RENDER_WAIT);
		await userEvent.click(within(panel).getByRole("switch", { name: "Send feedback" }));
		const useDefault = within(panel).getByRole("button", {
			name: "Use default for Post feedback after merge",
		});
		// Every control waits while the first save is in flight.
		await waitFor(() => expect(useDefault).toHaveProperty("disabled", false));
		await userEvent.click(useDefault);
		await waitFor(() =>
			expect(settings.patches.map((patch) => patch.body)).toStrictEqual([
				{ deliveryStatus: "PAUSED" },
				{ reset: ["DELIVER_TO_MERGED"] },
			]),
		);
	});

	it("keeps sending feedback out of the Automated review section", async () => {
		stubWorkspace([]);

		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");

		await screen.findByRole("heading", { name: "Review scope" }, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("heading", { name: "Sending feedback" })).toBeNull();
		expect(screen.queryByRole("switch", { name: "Send feedback" })).toBeNull();
	});

	// Null restores inheritance; Off is an explicit choice and cannot serve as reset.
	it("overrides a practice's feedback mode and makes it inherit again with an explicit null", async () => {
		const bodies: SetFeedbackModeRequest[] = [];
		stubWorkspace([
			http.patch<PathParams, SetFeedbackModeRequest>(
				"*/workspaces/:workspaceSlug/practices/:practiceSlug/feedback-mode",
				async ({ request, params }) => {
					bodies.push(await request.json());
					const practice = fixture.practices.find(
						(candidate) => candidate.slug === params.practiceSlug,
					);
					return HttpResponse.json(practice);
				},
			),
		]);

		renderRouteAt("/w/acme/admin/practices/review");

		// jsdom has no layout; Base UI can retain aria-hidden until the panel receives a height.
		const inherited = await openPractice("States the motivation");
		await userEvent.click(within(inherited).getByRole("radio", { name: "Off", hidden: true }));
		await waitFor(() => expect(bodies).toStrictEqual([{ feedbackMode: "OFF" }]));

		const overridden = await openPractice("Links the issue");
		await userEvent.click(
			within(overridden).getByRole("button", {
				name: "Use the default feedback mode for Links the issue",
				hidden: true,
			}),
		);
		await waitFor(() => expect(bodies[1]).toStrictEqual({ feedbackMode: null }));
	});

	it("overrides a group's feedback mode and makes it inherit again", async () => {
		const bodies: SetFeedbackModeRequest[] = [];
		const [hygiene] = fixture.groups;
		assert(hygiene);
		const answers: PracticeGroup[] = [
			{ ...hygiene, feedbackMode: { effective: "OFF", override: "OFF", source: "GROUP" } },
			hygiene,
		];
		let group = hygiene;
		stubWorkspace([
			http.patch<PathParams, SetFeedbackModeRequest>(
				"*/workspaces/:workspaceSlug/practice-groups/:groupSlug/feedback-mode",
				async ({ request }) => {
					bodies.push(await request.json());
					const answer = answers[bodies.length - 1];
					assert(answer, "No answer for this write");
					group = answer;
					return HttpResponse.json(group);
				},
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([group])),
		]);

		renderRouteAt("/w/acme/admin/practices/review");

		const ladder = await screen.findByRole(
			"radiogroup",
			{ name: "Feedback mode for Hygiene" },
			ROUTE_RENDER_WAIT,
		);
		await userEvent.click(within(ladder).getByRole("radio", { name: "Off" }));
		await waitFor(() => expect(bodies).toStrictEqual([{ feedbackMode: "OFF" }]));

		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Use the default feedback mode for Hygiene" },
				ROUTE_RENDER_WAIT,
			),
		);
		await waitFor(() => expect(bodies[1]).toStrictEqual({ feedbackMode: null }));
	});

	it("saves a selected scope with nobody in it as nobody, never as everyone", async () => {
		const settings = stubSettings(reviewSettings());
		stubWorkspace(settings.handlers(false));

		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");

		await userEvent.click(
			await screen.findByRole("radio", { name: "Selected people" }, ROUTE_RENDER_WAIT),
		);
		screen.getByText("No people are selected");
		await userEvent.click(screen.getByRole("button", { name: "Review changes" }));

		const nobody = {
			people: "SELECTED",
			repositories: "ALL",
			accountIds: [],
			selectedRepositories: [],
		} satisfies WorkspaceReviewScope;
		await waitFor(() =>
			expect(settings.patches).toStrictEqual([{ body: { reviewScope: nobody }, etag: '"0"' }]),
		);
		expect(settings.previews).toStrictEqual([nobody]);
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	it("keeps every selected repository listed and counted, saying only what its record shows", async () => {
		const settings = stubSettings(
			reviewSettings({
				reviewScope: {
					people: "ALL",
					repositories: "SELECTED",
					accountIds: [],
					selectedRepositories: [
						{ repositoryItemId: "101", baseBranches: ["main"] },
						{ repositoryItemId: "999", baseBranches: [] },
					],
				},
			}),
		);
		stubWorkspace([
			...settings.handlers(false),
			http.get("*/workspaces/:workspaceSlug/practices/items", () =>
				HttpResponse.json(
					repositoryPage(
						[repositoryItem("101", { label: "owner/retired", removedAt: "2026-09-01T00:00:00Z" })],
						{ number: 0, last: true, totalElements: 1 },
					),
				),
			),
		]);
		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");

		const heading = await screen.findByRole("heading", { name: "Review scope" }, ROUTE_RENDER_WAIT);
		const scope = heading.closest("section");
		assert(scope);
		// A removed repository and one no record has are still selected, shown and counted.
		await within(scope).findByText(
			"2 selected · 3 monitored repositories",
			undefined,
			ROUTE_RENDER_WAIT,
		);
		const removed = within(scope).getByText("owner/retired").closest('[role="listitem"]');
		const unlisted = within(scope).getByText("Repository 999").closest('[role="listitem"]');
		expect(removed?.textContent).toContain("Removed");
		expect(unlisted?.textContent).toContain("Source unavailable");
		expect(within(scope).queryByText("Not monitored")).toBeNull();
		expect(within(scope).queryByText(/no longer syncs/u)).toBeNull();
	});

	it("widens the review scope only after the preview is confirmed, with the read ETag", async () => {
		const persisted = reviewSettings({
			etag: '"7"',
			reviewScope: {
				people: "SELECTED",
				repositories: "SELECTED",
				accountIds: ["9"],
				selectedRepositories: [{ repositoryItemId: "101", baseBranches: ["main"] }],
			},
		});
		const settings = stubSettings(persisted);
		stubWorkspace(settings.handlers(true));

		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");

		await screen.findByRole(
			"button",
			{ name: "Remove grace from the review scope" },
			ROUTE_RENDER_WAIT,
		);
		await screen.findAllByText("owner/repo");

		await userEvent.click(screen.getByRole("radio", { name: "All monitored repositories" }));
		await userEvent.click(screen.getByRole("button", { name: "Review changes" }));

		const dialog = await screen.findByRole("alertdialog", {}, ROUTE_RENDER_WAIT);
		expect(settings.patches).toStrictEqual([]);
		await userEvent.click(within(dialog).getByRole("button", { name: "Widen review scope" }));

		const widened = {
			...persisted.reviewScope,
			repositories: "ALL",
		} satisfies WorkspaceReviewScope;
		await waitFor(() =>
			expect(settings.patches).toStrictEqual([{ body: { reviewScope: widened }, etag: '"7"' }]),
		);
		expect(settings.previews).toStrictEqual([widened]);
	});

	it("pauses and resumes a recurring check with the schedule's version as its ETag", async () => {
		let schedule: ReviewSchedule = reviewSchedule({ version: "3" });
		const writes: { body: SetReviewScheduleEnabledRequest; etag: string | null }[] = [];
		stubWorkspace([
			http.get("*/workspaces/:workspaceSlug/practices/review-schedules", () =>
				HttpResponse.json([schedule]),
			),
			http.patch<PathParams, SetReviewScheduleEnabledRequest>(
				"*/workspaces/:workspaceSlug/practices/review-schedules/:scheduleId",
				async ({ request }) => {
					const body = await request.json();
					writes.push({ body, etag: request.headers.get("If-Match") });
					schedule = {
						...schedule,
						enabled: body.enabled === true,
						version: String(Number(schedule.version) + 1),
					};
					return HttpResponse.json(schedule);
				},
			),
		]);

		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");

		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Pause checking pull or merge requests" },
				ROUTE_RENDER_WAIT,
			),
		);
		await userEvent.click(
			await screen.findByRole(
				"button",
				{ name: "Resume checking pull or merge requests" },
				ROUTE_RENDER_WAIT,
			),
		);

		await waitFor(() =>
			expect(writes).toStrictEqual([
				{ body: { enabled: false }, etag: '"3"' },
				{ body: { enabled: true }, etag: '"4"' },
			]),
		);
	});

	it("deletes a recurring check only after the reader confirms, with its version as the ETag", async () => {
		let schedules: ReviewSchedule[] = [reviewSchedule({ version: "5" })];
		const deletes: (string | null)[] = [];
		stubWorkspace([
			http.get("*/workspaces/:workspaceSlug/practices/review-schedules", () =>
				HttpResponse.json(schedules),
			),
			http.delete(
				"*/workspaces/:workspaceSlug/practices/review-schedules/:scheduleId",
				({ request }) => {
					deletes.push(request.headers.get("If-Match"));
					schedules = [];
					return new HttpResponse(null, { status: 204 });
				},
			),
		]);
		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");
		const user = userEvent.setup();

		await user.click(
			await screen.findByRole(
				"button",
				{ name: "Delete the recurring check on pull or merge requests" },
				ROUTE_RENDER_WAIT,
			),
		);
		const dialog = await screen.findByRole("alertdialog", {
			name: "Delete the recurring check on pull or merge requests?",
		});
		expect(deletes).toStrictEqual([]);

		await user.click(within(dialog).getByRole("button", { name: "Delete recurring check" }));

		await waitFor(() => expect(deletes).toStrictEqual(['"5"']));
		await screen.findByText("Nothing is checked on a schedule", undefined, ROUTE_RENDER_WAIT);
	});

	it("saves and clears generated patterns through the settings API with its ETag", async () => {
		const settings = stubSettings(reviewSettings({ generatedPaths: { "101": ["old/**"] } }));
		stubWorkspace(settings.handlers(false));

		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");
		const input = await screen.findByRole("textbox", { name: "owner/repo" }, ROUTE_RENDER_WAIT);
		expect(input).toHaveProperty("value", "old/**");
		await userEvent.clear(input);
		await userEvent.type(input, "generated/**");
		await userEvent.click(
			screen.getByRole("button", { name: "Save generated paths for owner/repo" }),
		);
		await waitFor(
			() =>
				expect(settings.patches).toStrictEqual([
					{ body: { generatedPaths: { "101": ["generated/**"] } }, etag: '"0"' },
				]),
			ROUTE_RENDER_WAIT,
		);
		await waitFor(() =>
			expect(
				screen.getByRole("button", { name: "Save generated paths for owner/repo" }),
			).toHaveProperty("disabled", true),
		);
		await userEvent.clear(input);
		await userEvent.click(
			screen.getByRole("button", { name: "Save generated paths for owner/repo" }),
		);
		await waitFor(() =>
			expect(settings.patches[1]).toStrictEqual({
				body: { generatedPaths: { "101": [] } },
				etag: '"1"',
			}),
		);
	});
	it("keeps same-label repository patterns separate through item-ID writes, reset and a refused save", async () => {
		const settings = stubSettings(
			reviewSettings({ generatedPaths: { "101": ["first/**"], "102": ["second/**"] } }),
		);
		stubWorkspace([
			...settings.handlers(false),
			http.get("*/workspaces/:workspaceSlug/practices/items", () =>
				HttpResponse.json(
					repositoryPage(
						["101", "102"].map((id) => repositoryItem(id, { label: "Repository", unnamed: true })),
						{ number: 0, last: true, totalElements: 2 },
					),
				),
			),
		]);
		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");
		// No repository record names either item, so each is named by its item ID, not "Repository" twice.
		const first = await screen.findByRole("textbox", { name: "Repository 101" }, ROUTE_RENDER_WAIT);
		const second = screen.getByRole("textbox", { name: "Repository 102" });
		expect(first).toHaveProperty("value", "first/**");
		expect(second).toHaveProperty("value", "second/**");
		await userEvent.clear(first);
		await userEvent.type(first, "updated/**");
		const saveFirst = screen.getByRole("button", {
			name: "Save generated paths for Repository 101",
		});
		await userEvent.click(saveFirst);
		await waitFor(() =>
			expect(settings.patches).toStrictEqual([
				{ body: { generatedPaths: { "101": ["updated/**"] } }, etag: '"0"' },
			]),
		);
		await waitFor(() => expect(saveFirst).toHaveProperty("disabled", true));
		expect(second).toHaveProperty("value", "second/**");
		await userEvent.clear(first);
		await userEvent.click(saveFirst);
		await waitFor(() =>
			expect(settings.patches[1]).toStrictEqual({
				body: { generatedPaths: { "101": [] } },
				etag: '"1"',
			}),
		);
		await waitFor(() => expect(saveFirst).toHaveProperty("disabled", true));
		expect(first).toHaveProperty("value", "");
		expect(second).toHaveProperty("value", "second/**");
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/review-settings", () =>
				HttpResponse.json(
					{ status: 400, detail: "Select a repository this workspace monitors." },
					{ status: 400 },
				),
			),
		);
		await userEvent.type(first, "unsaved/**");
		await userEvent.click(saveFirst);
		await screen.findByText("We could not save generated paths. Check the patterns and try again.");
		expect(first).toHaveProperty("value", "unsaved/**");
		expect(second).toHaveProperty("value", "second/**");
		expect(saveFirst).toHaveProperty("disabled", false);
	});

	it("loads the next page of repositories on request and marks a removed one", async () => {
		const settings = stubSettings(reviewSettings({ generatedPaths: {} }));
		const pages: string[] = [];
		stubWorkspace([
			...settings.handlers(false),
			http.get("*/workspaces/:workspaceSlug/practices/items", ({ request }) => {
				const page = pageOf(request);
				pages.push(page);
				return HttpResponse.json(twoRepositoryPages(page));
			}),
		]);
		renderRouteAt("/w/acme/admin/practices/review?section=automated-review");
		await screen.findByText("1 of 2 repositories loaded", { exact: false }, ROUTE_RENDER_WAIT);
		await userEvent.click(screen.getByRole("button", { name: "Show more repositories" }));

		await screen.findByRole(
			"button",
			{ name: "Generated paths for owner/retired" },
			ROUTE_RENDER_WAIT,
		);
		expect(pages).toStrictEqual(["0", "1"]);
		screen.getByRole("button", { name: "Generated paths for owner/current" });
		screen.getByText("Removed");
		expect(screen.queryByRole("button", { name: "Show more repositories" })).toBeNull();
	});
});

function repositoryItem(id: string, fields: { label: string; unnamed?: true; removedAt?: string }) {
	return {
		id,
		kind: "scm.repository",
		label: fields.label,
		title: null,
		container: null,
		parentId: null,
		path: null,
		provider: fields.unnamed === true ? null : "GITHUB",
		removedAt: fields.removedAt ?? null,
		url: null,
	};
}

function repositoryPage(
	content: readonly ReturnType<typeof repositoryItem>[],
	page: { number: number; last: boolean; totalElements: number },
) {
	return {
		content,
		...page,
		first: page.number === 0,
		empty: content.length === 0,
		numberOfElements: content.length,
		size: 100,
		totalPages: page.last ? page.number + 1 : page.number + 2,
	};
}

/** A current repository on the first page and a removed one on the second. */
function twoRepositoryPages(page: string) {
	return page === "0"
		? repositoryPage([repositoryItem("101", { label: "owner/current" })], {
				number: 0,
				last: false,
				totalElements: 2,
			})
		: repositoryPage(
				[repositoryItem("102", { label: "owner/retired", removedAt: "2026-09-01T00:00:00Z" })],
				{ number: 1, last: true, totalElements: 2 },
			);
}

/** The page a list read asks for; the first when it names none. */
function pageOf(request: Request): string {
	return new URL(request.url).searchParams.get("page") ?? "0";
}
