import { describe, expect, it, vi } from "vitest";

import type {
	ArtifactTrace,
	DeliveredWorkFeedback,
	DeliveredWorkFeedbackItem,
	DeliveredWorkFeedbackPlacement,
	ObservationList,
	ReviewContext as ReviewContextDTO,
	Workspace,
	WorkspaceListItem,
} from "~/api/types.gen";
import {
	type ContextApi,
	type ContextEnvironment,
	type DetailsApi,
	workFeedback,
	observationPage,
	resolveContext,
	WorkspaceDirectory,
} from "~/background/context";

const MR = "https://gitlab.example.test/team/app/-/merge_requests/4";
const ISSUE = "https://gitlab.example.test/team/app/-/issues/4";

function listItem(
	slug: string,
	providerType: WorkspaceListItem["providerType"],
): WorkspaceListItem {
	return {
		accountLogin: slug,
		createdAt: "2026-01-01T00:00:00Z",
		displayName: `Workspace ${slug}`,
		id: slug.length,
		leaderboardEnabled: false,
		leaguesEnabled: false,
		mentorEnabled: false,
		practicesEnabled: true,
		progressionEnabled: false,
		providerType,
		status: "ACTIVE",
		workspaceSlug: slug,
	};
}

function workspace(slug: string, serverUrl: string | undefined): Workspace {
	return {
		accountLogin: slug,
		createdAt: "2026-01-01T00:00:00Z",
		displayName: `Workspace ${slug}`,
		gitlabWebhookRegistered: false,
		hasPersonalAccessToken: false,
		hasSlackToken: false,
		id: slug.length,
		isPubliclyViewable: false,
		leaderboardEnabled: false,
		leaguesEnabled: false,
		mentorEnabled: false,
		practiceReviewAutoTriggerEnabled: true,
		practiceReviewManualTriggerEnabled: true,
		practicesEnabled: true,
		progressionEnabled: false,
		providerType: "GITLAB",
		serverUrl,
		status: "ACTIVE",
		updatedAt: "2026-01-01T00:00:00Z",
		workspaceSlug: slug,
	};
}

function dto(id: string, kind = "scm.pull_request"): ReviewContextDTO {
	return {
		work: { id, kind, label: `!${id}`, title: "Add login", repositoryName: "team/app" },
		canRequestReview: true,
		canInspectReviewDetails: false,
	};
}

const TRACE: ArtifactTrace = {
	artifactId: 4009,
	artifactKind: "scm.pull_request",
	title: "Add login",
	practices: [],
	signals: [],
};

interface Setup {
	url: string;
	workspaces?: WorkspaceListItem[];
	servers?: Record<string, string | undefined>;
	resolved?: Record<string, ReviewContextDTO | undefined>;
}

function environment(setup: Setup) {
	let { url } = setup;
	const trace = vi.fn<ContextApi["trace"]>().mockResolvedValue(TRACE);
	const resolve = vi
		.fn<ContextApi["resolve"]>()
		.mockImplementation(async (slug) => setup.resolved?.[slug]);
	const api: ContextApi = {
		generation: 1,
		assertCurrent: async () => undefined,
		workspaces: async () => setup.workspaces ?? [listItem("team", "GITLAB")],
		workspace: async (slug) =>
			workspace(slug, setup.servers?.[slug] ?? "https://gitlab.example.test"),
		resolve,
		trace,
	};
	const env: ContextEnvironment = {
		instance: {
			origin: "https://heph.example.test",
			apiBase: "https://heph.example.test/api",
			webAppOrigin: "https://heph.example.test",
		},
		api,
		tabUrl: async () => url,
		directory: new WorkspaceDirectory(),
		now: () => new Date("2026-09-26T12:00:00Z"),
	};
	return {
		env,
		trace,
		resolve,
		navigate: (next: string) => {
			url = next;
		},
	};
}

describe("resolveContext", () => {
	it("says so when the tab is not a piece of work", async () => {
		const { env } = environment({ url: "https://gitlab.example.test/team/app" });
		await expect(resolveContext(env, 1)).resolves.toStrictEqual({ status: "unsupported-page" });
	});

	it("asks only workspaces connected to the page's provider origin", async () => {
		const { env, resolve } = environment({
			url: MR,
			workspaces: [
				listItem("team", "GITLAB"),
				listItem("other", "GITLAB"),
				listItem("gh", "GITHUB"),
			],
			servers: { other: "https://gitlab.com" },
			resolved: { team: dto("4009") },
		});
		const context = await resolveContext(env, 1);
		expect(resolve.mock.calls).toStrictEqual([["team", MR]]);
		expect(context).toMatchObject({ status: "ready", workspace: { slug: "team" } });
	});

	it("reports no workspace when none is connected to the site", async () => {
		const { env } = environment({ url: "https://gitlab.other.test/a/b/-/issues/1" });
		await expect(resolveContext(env, 1)).resolves.toMatchObject({
			status: "no-workspace",
			siteOrigin: "https://gitlab.other.test",
		});
	});

	it("reports work nobody can see without saying why", async () => {
		const { env } = environment({ url: ISSUE, resolved: {} });
		await expect(resolveContext(env, 1)).resolves.toStrictEqual({
			status: "not-found",
			instanceHost: "heph.example.test",
			workLabel: "team/app #4",
		});
	});

	it("asks the reader to choose when two workspaces know the work", async () => {
		const { env, trace } = environment({
			url: MR,
			workspaces: [listItem("team", "GITLAB"), listItem("course", "GITLAB")],
			resolved: { team: dto("4009"), course: dto("4009") },
		});
		await expect(resolveContext(env, 1)).resolves.toMatchObject({
			status: "choose-workspace",
			candidates: [{ slug: "team" }, { slug: "course" }],
		});
		expect(trace).not.toHaveBeenCalled();
	});

	it("uses the frame’s explicit workspace selection and offers the other", async () => {
		const { env } = environment({
			url: MR,
			workspaces: [listItem("team", "GITLAB"), listItem("course", "GITLAB")],
			resolved: { team: dto("4009"), course: dto("4009") },
		});
		await expect(resolveContext(env, 1, "course")).resolves.toMatchObject({
			status: "ready",
			workspace: { slug: "course" },
			alternatives: [{ slug: "team" }],
		});
	});

	it("links into the web app, not the API", async () => {
		const { env } = environment({ url: MR, resolved: { team: dto("4009") } });
		await expect(resolveContext(env, 1)).resolves.toMatchObject({
			links: { trace: "https://heph.example.test/w/team/reviews/scm.pull_request/4009" },
		});
	});

	it("treats a trace 404 as nothing recorded, not as an error", async () => {
		const { env, trace } = environment({ url: MR, resolved: { team: dto("4009") } });
		trace.mockResolvedValue(null);
		await expect(resolveContext(env, 1)).resolves.toMatchObject({ status: "ready", trace: null });
	});

	it("drops the answer for page A when the tab moved on to page B meanwhile", async () => {
		const { env, trace, navigate } = environment({ url: MR, resolved: { team: dto("4009") } });
		const released = Promise.withResolvers<ArtifactTrace | null>();
		trace.mockReturnValue(released.promise);
		const pending = resolveContext(env, 1);
		await vi.waitFor(() => {
			expect(trace).toHaveBeenCalledOnce();
		});
		navigate(ISSUE);
		released.resolve(TRACE);
		await expect(pending).rejects.toMatchObject({ code: "stale" });
	});

	it("keeps the answer when only the sub-page changed", async () => {
		const { env, trace, navigate } = environment({ url: MR, resolved: { team: dto("4009") } });
		const released = Promise.withResolvers<ArtifactTrace | null>();
		trace.mockReturnValue(released.promise);
		const pending = resolveContext(env, 1);
		await vi.waitFor(() => {
			expect(trace).toHaveBeenCalledOnce();
		});
		navigate(`${MR}/diffs`);
		released.resolve(TRACE);
		await expect(pending).resolves.toMatchObject({ status: "ready" });
	});
});

const MR1 = "https://gitlab.example.test/team/app/-/merge_requests/1";
const MR2 = "https://gitlab.example.test/team/app/-/merge_requests/2";
const JOB = "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c";

function adminDto(id: string): ReviewContextDTO {
	return { ...dto(id), canInspectReviewDetails: true };
}

const WORK = { id: "101", kind: "scm.pull_request", label: "!1" };
const OTHER_WORK = { id: "999", kind: "scm.pull_request", label: "!9" };
const OBSERVATION = "1a2b3c4d-0000-4000-8000-000000000001";

function ownRow(overrides: Partial<ObservationList> = {}): ObservationList {
	return {
		id: OBSERVATION,
		artifactId: 101,
		artifactKind: "scm.pull_request",
		assessmentStatus: "ASSESSED",
		claimCurrentness: "CURRENT",
		observedAt: "2026-09-26T11:00:00Z",
		origin: "LIVE",
		practiceName: "Descriptive merge request",
		practiceSlug: "descriptive-merge-request",
		outcome: "NEGATIVE",
		severity: "MAJOR",
		summary: "The description never says why.",
		...overrides,
	};
}

/** Two merge requests: MR1 is work 101 whose trace names JOB, MR2 is work 102. */
function adminEnvironment() {
	let url = MR1;
	const byPage: Record<string, ReviewContextDTO> = {
		[MR1]: adminDto("101"),
		[MR2]: adminDto("102"),
	};
	const api = {
		generation: 1,
		assertCurrent: async () => undefined,
		workspaces: async () => [listItem("team", "GITLAB")],
		workspace: async (slug: string) => workspace(slug, "https://gitlab.example.test"),
		resolve: vi.fn<ContextApi["resolve"]>(async (_slug, page) => byPage[page]),
		trace: vi.fn<ContextApi["trace"]>(async () => ({
			...TRACE,
			signals: [
				{
					id: "s1",
					discoveredVia: "MANUAL",
					displayName: "Review requested",
					occurredAt: "2026-09-26T11:00:00Z",
					revision: "abc",
					signal: "manual",
					state: "TRIGGERED",
					reviewId: JOB,
				},
			],
		})),
		ownObservations: vi.fn<DetailsApi["ownObservations"]>(async () => ({
			content: [ownRow()],
			totalElements: 1,
		})),
		workFeedback: vi.fn<DetailsApi["workFeedback"]>(async () => ({
			work: WORK,
			feedback: [],
			hasMore: false,
		})),
	} satisfies DetailsApi;
	const env: ContextEnvironment<typeof api> = {
		instance: {
			origin: "https://heph.example.test",
			apiBase: "https://heph.example.test/api",
			webAppOrigin: "https://heph.example.test",
		},
		api,
		tabUrl: async () => url,
		directory: new WorkspaceDirectory(),
		now: () => new Date("2026-09-26T12:00:00Z"),
	};
	return {
		env,
		api,
		navigate: (next: string) => {
			url = next;
		},
		/** The same pages, answered for an ordinary developer. */
		asDeveloper: () => {
			byPage[MR1] = dto("101");
			byPage[MR2] = dto("102");
		},
	};
}

describe("the reader's own observations on the tab's work", () => {
	it.each([
		["a developer", dto("101")],
		["a workspace admin", adminDto("101")],
	])("asks for %s's own observations with the exact work", async (_reader, answer) => {
		const { env, api } = adminEnvironment();
		api.resolve.mockResolvedValue(answer);
		const page = await observationPage(env, 10, "team");
		expect(api.ownObservations).toHaveBeenCalledWith("team", "scm.pull_request", 101, 25);
		expect(page).toMatchObject({ total: 1 });
		expect(page.rows[0]).toStrictEqual({
			id: OBSERVATION,
			practiceName: "Descriptive merge request",
			practiceSlug: "descriptive-merge-request",
			summary: "The description never says why.",
			outcome: "NEGATIVE",
			severity: "MAJOR",
			assessmentStatus: "ASSESSED",
			claimCurrentness: "CURRENT",
			observedAt: "2026-09-26T11:00:00Z",
		});
	});

	it("refuses the observations of another workspace before asking any server", async () => {
		const { env, api } = adminEnvironment();
		await expect(observationPage(env, 10, "other")).rejects.toMatchObject({ code: "stale" });
		expect(api.ownObservations).not.toHaveBeenCalled();
	});

	it("refuses a list that holds any other work, as a server without the exact filter returns", async () => {
		const { env, api } = adminEnvironment();
		api.ownObservations.mockResolvedValue({
			content: [ownRow(), ownRow({ id: "other", artifactId: 999 })],
			totalElements: 2,
		});
		await expect(observationPage(env, 10, "team")).rejects.toMatchObject({ code: "server" });
	});

	it("drops a list whose tab moved to other work while it loaded", async () => {
		const { env, api, navigate } = adminEnvironment();
		const answer = Promise.withResolvers<Awaited<ReturnType<DetailsApi["ownObservations"]>>>();
		api.ownObservations.mockReturnValueOnce(answer.promise);
		const pending = observationPage(env, 10, "team");
		await vi.waitFor(() => {
			expect(api.ownObservations).toHaveBeenCalledOnce();
		});
		navigate(MR2);
		answer.resolve({ content: [ownRow()], totalElements: 1 });
		await expect(pending).rejects.toMatchObject({ code: "stale" });
	});

	it.each([undefined, -1, 0, 1.5, Number.NaN])(
		"rejects unusable observation total %s instead of claiming completeness",
		async (total) => {
			const { env, api } = adminEnvironment();
			api.ownObservations.mockResolvedValue({ content: [ownRow()], totalElements: total });
			await expect(observationPage(env, 10, "team")).rejects.toMatchObject({ code: "server" });
		},
	);
});

function listRow(url: string) {
	return { kind: "list-row" as const, url };
}

describe("a row of the list the tab shows", () => {
	const LIST = "https://gitlab.example.test/team/app/-/merge_requests?state=opened";

	it("resolves the row's work when the tab really shows that repository's list", async () => {
		const { env, api, navigate } = adminEnvironment();
		navigate(LIST);
		const context = await resolveContext(env, 10, undefined, listRow(MR1));
		expect(context).toMatchObject({ status: "ready", pageUrl: MR1, view: "overview" });
		expect(api.resolve).toHaveBeenCalledWith("team", MR1);
	});

	it.each([
		["another repository", "https://gitlab.example.test/team/other/-/merge_requests/1"],
		["another origin", "https://gitlab.evil.test/team/app/-/merge_requests/1"],
		["another kind", "https://gitlab.example.test/team/app/-/issues/1"],
		["a sub-page", `${MR1}/diffs`],
		["a query", `${MR1}?x=1`],
	])("refuses a row selector for %s without asking any server", async (_name, url) => {
		const { env, api, navigate } = adminEnvironment();
		navigate(LIST);
		await expect(resolveContext(env, 10, undefined, listRow(url))).resolves.toStrictEqual({
			status: "unsupported-page",
		});
		expect(api.resolve).not.toHaveBeenCalled();
	});

	it("refuses a row selector when the tab shows a work page, not a list", async () => {
		const { env, api } = adminEnvironment();
		await expect(resolveContext(env, 10, undefined, listRow(MR2))).resolves.toStrictEqual({
			status: "unsupported-page",
		});
		expect(api.resolve).not.toHaveBeenCalled();
	});

	it("drops the answer when the tab left that list while it loaded", async () => {
		const { env, api, navigate } = adminEnvironment();
		navigate(LIST);
		const trace = Promise.withResolvers<ArtifactTrace | null>();
		api.trace.mockReturnValueOnce(trace.promise);
		const pending = resolveContext(env, 10, undefined, listRow(MR1));
		await vi.waitFor(() => {
			expect(api.trace).toHaveBeenCalledOnce();
		});
		navigate("https://gitlab.example.test/team/app/-/issues");
		trace.resolve(TRACE);
		await expect(pending).rejects.toMatchObject({ code: "stale" });
	});

	it.each([
		["another filter", "https://gitlab.example.test/team/app/-/merge_requests?state=merged"],
		["another page", `${LIST}&page=2`],
	])(
		"drops the answer when the tab moved to %s of the same list while it loaded",
		async (_name, url) => {
			const { env, api, navigate } = adminEnvironment();
			navigate(LIST);
			const answer = Promise.withResolvers<DeliveredWorkFeedback>();
			api.workFeedback.mockReturnValueOnce(answer.promise);
			const pending = workFeedback(env, 10, "team", listRow(MR1));
			await vi.waitFor(() => {
				expect(api.workFeedback).toHaveBeenCalledOnce();
			});
			navigate(url);
			answer.resolve({ work: WORK, feedback: [], hasMore: false });
			await expect(pending).rejects.toMatchObject({ code: "stale" });
		},
	);

	it("keeps the answer when only the list's fragment changed", async () => {
		const { env, api, navigate } = adminEnvironment();
		navigate(LIST);
		const trace = Promise.withResolvers<ArtifactTrace | null>();
		api.trace.mockReturnValueOnce(trace.promise);
		const pending = resolveContext(env, 10, undefined, listRow(MR1));
		await vi.waitFor(() => {
			expect(api.trace).toHaveBeenCalledOnce();
		});
		navigate(`${LIST}#top`);
		trace.resolve(TRACE);
		await expect(pending).resolves.toMatchObject({ status: "ready" });
	});

	it("reads the reader's comments on the row's work, bound to the same list row", async () => {
		const { env, api, navigate } = adminEnvironment();
		navigate(LIST);
		await expect(workFeedback(env, 10, "team", listRow(MR1))).resolves.toMatchObject({
			comments: [],
		});
		expect(api.workFeedback).toHaveBeenCalledWith("team", MR1);
	});
});

const item = (overrides: Partial<DeliveredWorkFeedbackItem> = {}): DeliveredWorkFeedbackItem => ({
	id: "f",
	deliveredAt: "2026-09-26T11:00:00Z",
	practices: [{ slug: "descriptive-merge-request", name: "Descriptive merge request" }],
	placements: [],
	...overrides,
});
const summary = (
	overrides: Partial<DeliveredWorkFeedbackPlacement> = {},
): DeliveredWorkFeedbackPlacement => ({
	id: "5c1f7a90-1b2c-4d3e-8f90-a1b2c3d4e5f6",
	type: "SUMMARY",
	commentRef: "gid://gitlab/Note/1",
	permalink: `${MR1}#note_1`,
	...overrides,
});

describe("the reader's own feedback on the work", () => {
	it("asks for the work by its canonical address", async () => {
		const { env, api } = adminEnvironment();
		await workFeedback(env, 10, "team");
		expect(api.workFeedback).toHaveBeenCalledWith("team", MR1);
	});

	it("counts one comment per provider comment, however many pieces of feedback share it", async () => {
		const { env, api } = adminEnvironment();
		api.workFeedback.mockResolvedValue({
			work: WORK,
			hasMore: false,
			feedback: [
				item({ id: "a", deliveredAt: "2026-09-26T11:00:00Z", placements: [summary()] }),
				item({
					id: "b",
					deliveredAt: "2026-09-26T09:00:00Z",
					practices: [{ slug: "small-changes", name: "Small, focused changes" }],
					placements: [
						// The same summary, edited in place by a later piece of feedback.
						summary({ id: "6d2a8b01-2c3d-4e5f-9a01-b2c3d4e5f607", permalink: undefined }),
						{
							id: "7e3b9c12-3d4e-4f60-8b12-c3d4e5f60718",
							type: "INLINE",
							commentRef: "gid://gitlab/DiffNote/2",
							path: "a.ts",
							startLine: 3,
							side: "NEW",
							permalink: `${MR1}/diffs#note_2`,
						},
						// Not a comment on the work, and a handle that identifies nothing: neither counts.
						summary({ type: "CONVERSATION_TURN", commentRef: "turn-1" }),
						summary({ commentRef: " " }),
					],
				}),
				// Feedback with no recorded comment adds none.
				item({ id: "c", placements: [] }),
			],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.more).toBe(false);
		expect(answer.comments).toStrictEqual([
			{
				kind: "SUMMARY",
				path: undefined,
				startLine: undefined,
				endLine: undefined,
				permalink: `${MR1}#note_1`,
				practices: ["Descriptive merge request", "Small, focused changes"],
				deliveredAt: "2026-09-26T11:00:00Z",
			},
			{
				kind: "INLINE",
				path: "a.ts",
				startLine: 3,
				endLine: undefined,
				permalink: `${MR1}/diffs#note_2`,
				practices: ["Small, focused changes"],
				deliveredAt: "2026-09-26T09:00:00Z",
			},
		]);
		expect(JSON.stringify(answer)).not.toContain("gid://");
	});

	it("keeps an older verified link when a newer record of the same comment has none", async () => {
		const { env, api } = adminEnvironment();
		api.workFeedback.mockResolvedValue({
			work: WORK,
			hasMore: false,
			feedback: [
				item({ id: "newer", placements: [summary({ permalink: undefined })] }),
				item({ id: "older", deliveredAt: "2026-09-26T09:00:00Z", placements: [summary()] }),
			],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.comments).toHaveLength(1);
		expect(answer.comments[0]).toMatchObject({
			permalink: `${MR1}#note_1`,
			deliveredAt: "2026-09-26T11:00:00Z",
		});
	});

	it("names no practice and no time for a comment the server gives neither", async () => {
		const { env, api } = adminEnvironment();
		api.workFeedback.mockResolvedValue({
			work: WORK,
			hasMore: true,
			feedback: [item({ practices: [], deliveredAt: undefined, placements: [summary()] })],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.comments[0]).toMatchObject({ practices: [], deliveredAt: undefined });
		expect(answer.more).toBe(true);
	});

	it("keeps a comment link only when it is an anchor on this work's own pages", async () => {
		const { env, api } = adminEnvironment();
		const links = [
			`${MR1}#note_1`,
			`${MR1}/diffs#note_2`,
			MR1,
			`${MR2}#note_3`,
			"https://gitlab.example.test/team/app/-/issues/1#note_4",
			"https://gitlab.evil.test/team/app/-/merge_requests/1#note_5",
			"http://gitlab.example.test/team/app/-/merge_requests/1#note_6",
			// oxlint-disable-next-line no-script-url -- The worker must drop one.
			"javascript:alert(1)",
			"not a url",
		];
		api.workFeedback.mockResolvedValue({
			work: WORK,
			hasMore: false,
			feedback: [
				item({
					placements: links.map((permalink, index) =>
						summary({ commentRef: `ref-${index}`, permalink }),
					),
				}),
			],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.comments.map((comment) => comment.permalink)).toStrictEqual([
			`${MR1}#note_1`,
			`${MR1}/diffs#note_2`,
			...Array.from({ length: links.length - 2 }, () => undefined),
		]);
	});

	it("accepts GitLab's other address of the same issue", async () => {
		const { env, api, navigate } = adminEnvironment();
		const issue = "https://gitlab.example.test/team/app/-/issues/7";
		navigate(issue);
		api.resolve.mockResolvedValue({
			...adminDto("70"),
			work: { id: "70", kind: "scm.issue", label: "#7" },
		});
		api.workFeedback.mockResolvedValue({
			work: { id: "70", kind: "scm.issue", label: "#7" },
			hasMore: false,
			feedback: [
				item({
					placements: [
						summary({
							permalink: "https://gitlab.example.test/team/app/-/work_items/7#note_9",
						}),
					],
				}),
			],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.comments[0]?.permalink).toBe(
			"https://gitlab.example.test/team/app/-/work_items/7#note_9",
		);
	});

	it("refuses an answer about other work", async () => {
		const { env, api } = adminEnvironment();
		api.workFeedback.mockResolvedValue({ work: OTHER_WORK, hasMore: false, feedback: [] });
		await expect(workFeedback(env, 10, "team")).rejects.toMatchObject({ code: "server" });
	});

	it("drops the answer when the tab moved to other work while it loaded", async () => {
		const { env, api, navigate } = adminEnvironment();
		const answer = Promise.withResolvers<DeliveredWorkFeedback>();
		api.workFeedback.mockReturnValueOnce(answer.promise);
		const pending = workFeedback(env, 10, "team");
		await vi.waitFor(() => {
			expect(api.workFeedback).toHaveBeenCalledOnce();
		});
		navigate(MR2);
		answer.resolve({ work: WORK, feedback: [], hasMore: false });
		await expect(pending).rejects.toMatchObject({ code: "stale" });
	});
});
