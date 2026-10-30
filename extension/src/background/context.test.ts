import { describe, expect, it, vi } from "vitest";

import type {
	DeliveredWorkFeedback,
	DeliveredWorkFeedbackCopy,
	DeliveredWorkFeedbackItem,
	ItemRef,
	OwnItemTrace,
	PageResponseDtoResultList,
	ResultList,
	ReviewContext as ReviewContextDTO,
	Workspace,
	WorkspaceListItem,
} from "~/api/types.gen";
import {
	type ContextApi,
	type ContextEnvironment,
	type DetailsApi,
	workFeedback,
	resultPage,
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
		practicesEnabled: true,
		providerType,
		publishesPublicActivity: false,
		status: "ACTIVE",
		workspaceSlug: slug,
		workspaceAddress: `https://hephaestus.example.test/w/${slug}`,
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
		publicActivityEnabled: false,
		practiceReviewAutoTriggerEnabled: true,
		practiceReviewManualTriggerEnabled: true,
		practicesEnabled: true,
		providerType: "GITLAB",
		serverUrl,
		status: "ACTIVE",
		updatedAt: "2026-01-01T00:00:00Z",
		workspaceSlug: slug,
		workspaceAddress: `https://hephaestus.example.test/w/${slug}`,
	};
}

function itemRef(id: string, kind = "scm.pull_request"): ItemRef {
	return {
		id,
		kind,
		parentId: null,
		provider: "GITLAB",
		label: `!${id}`,
		title: "Add login",
		url: null,
		container: "team/app",
		path: null,
		removedAt: null,
	};
}

function dto(id: string, kind = "scm.pull_request"): ReviewContextDTO {
	return {
		workspaceId: "1001",
		workspaceSlug: "team",
		item: itemRef(id, kind),
		requestAllowed: true,
		canInspectReviewDetails: false,
		reasonDescription: null,
	};
}

function ownTrace(itemId: string): OwnItemTrace {
	const item = itemRef(itemId);
	return {
		item,
		review: {
			id: "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c",
			kind: "ITEM",
			item,
			itemVersion: "abc",
			origin: "REQUEST",
			status: "COMPLETE",
			createdAt: "2026-09-26T11:00:00Z",
			completedAt: "2026-09-26T11:05:00Z",
		},
		events: [
			{
				name: "Review requested",
				displayName: "Activity recorded",
				occurredAt: "2026-09-26T11:00:00Z",
			},
		],
		results: [],
	};
}

const TRACE = ownTrace("4009");

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
			origin: "https://hephaestus.example.test",
			apiBase: "https://hephaestus.example.test/api",
			webAppOrigin: "https://hephaestus.example.test",
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

	it("reports an answer that names no item as work nobody can see", async () => {
		const { env, trace } = environment({
			url: ISSUE,
			resolved: { team: { ...dto("4"), item: null } },
		});
		await expect(resolveContext(env, 1)).resolves.toStrictEqual({
			status: "not-found",
			instanceHost: "hephaestus.example.test",
			workLabel: "team/app #4",
		});
		expect(trace).not.toHaveBeenCalled();
	});

	it("reports work nobody can see without saying why", async () => {
		const { env } = environment({ url: ISSUE, resolved: {} });
		await expect(resolveContext(env, 1)).resolves.toStrictEqual({
			status: "not-found",
			instanceHost: "hephaestus.example.test",
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

	it("answers with the item, the reader's standing and own trace as the server gave them", async () => {
		const { env } = environment({
			url: MR,
			resolved: { team: { ...dto("4009"), requestAllowed: false } },
		});
		await expect(resolveContext(env, 1)).resolves.toMatchObject({
			status: "ready",
			item: itemRef("4009"),
			requestAllowed: false,
			trace: TRACE,
			pageUrl: MR,
		});
	});

	it("links a developer into the web app, not the API, with no review details", async () => {
		const { env } = environment({ url: MR, resolved: { team: dto("4009") } });
		const context = await resolveContext(env, 1);
		expect(context).toHaveProperty("canInspectReviewDetails", false);
		expect(context).toHaveProperty("links", {
			trace: "https://hephaestus.example.test/w/team/feedback/4009",
		});
	});

	it("uses the server's permission to link to review details", async () => {
		const { env } = environment({
			url: MR,
			resolved: { team: { ...dto("4009"), canInspectReviewDetails: true } },
		});
		await expect(resolveContext(env, 1)).resolves.toMatchObject({
			canInspectReviewDetails: true,
			links: {
				trace: "https://hephaestus.example.test/w/team/feedback/4009",
				reviewDetails:
					"https://hephaestus.example.test/w/team/admin/practices/reviews/?itemId=4009&detail=item%3A4009",
			},
		});
	});

	it("keeps own records readable when their review trace is unavailable", async () => {
		const { env, trace } = environment({ url: MR, resolved: { team: dto("4009") } });
		trace.mockResolvedValue(null);
		await expect(resolveContext(env, 1)).resolves.toMatchObject({ status: "ready", trace: null });
	});

	it("drops the answer for page A when the tab moved on to page B meanwhile", async () => {
		const { env, trace, navigate } = environment({ url: MR, resolved: { team: dto("4009") } });
		const released = Promise.withResolvers<OwnItemTrace | null>();
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
		const released = Promise.withResolvers<OwnItemTrace | null>();
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

const ITEM = itemRef("101");
const OTHER_ITEM = itemRef("999");
const RESULT = "1a2b3c4d-0000-4000-8000-000000000001";

function ownRow(overrides: Partial<ResultList> = {}): ResultList {
	return {
		id: RESULT,
		item: ITEM,
		reviewId: "0b7c1a52-0d3e-4c55-8a0b-1d2e3f4a5b6c",
		origin: "EVENT",
		acceptedAt: null,
		markedIncorrectAt: null,
		currentness: "CURRENT",
		recordedAt: "2026-09-26T11:00:00Z",
		practiceName: "Descriptive merge request",
		practiceSlug: "descriptive-merge-request",
		outcome: "NOT_MET",
		severity: "MAJOR",
		summary: "The description never says why.",
		problems: [
			{
				id: "2b3c4d5e-0000-4000-8000-000000000001",
				severity: "MAJOR",
				summary: "The description never says which issue it closes.",
			},
			{
				id: "2b3c4d5e-0000-4000-8000-000000000002",
				severity: "MINOR",
				summary: "The description lists files instead of the change.",
			},
		],
		...overrides,
	};
}

function resultResponse(
	content: ResultList[],
	totalElements = content.length,
): PageResponseDtoResultList {
	const sort = { empty: false, sorted: true, unsorted: false };
	return {
		content,
		totalElements,
		totalPages: Math.ceil(totalElements / 10),
		number: 0,
		size: 10,
		numberOfElements: content.length,
		first: true,
		last: totalElements <= 10,
		empty: content.length === 0,
		sort,
		pageable: { offset: 0, pageNumber: 0, pageSize: 10, paged: true, unpaged: false, sort },
	};
}

function detailsEnvironment() {
	let url = MR1;
	const byPage: Record<string, ReviewContextDTO> = {
		[MR1]: dto("101"),
		[MR2]: dto("102"),
	};
	const api = {
		generation: 1,
		assertCurrent: async () => undefined,
		workspaces: async () => [listItem("team", "GITLAB")],
		workspace: async (slug: string) => workspace(slug, "https://gitlab.example.test"),
		resolve: vi.fn<ContextApi["resolve"]>(async (_slug, page) => byPage[page]),

		trace: vi.fn<ContextApi["trace"]>(async (_slug, itemId) => ownTrace(itemId)),
		ownResults: vi.fn<DetailsApi["ownResults"]>(async () => resultResponse([ownRow()])),
		workFeedback: vi.fn<DetailsApi["workFeedback"]>(async () => ({
			item: ITEM,
			feedback: [],
		})),
	} satisfies DetailsApi;
	const env: ContextEnvironment<typeof api> = {
		instance: {
			origin: "https://hephaestus.example.test",
			apiBase: "https://hephaestus.example.test/api",
			webAppOrigin: "https://hephaestus.example.test",
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
	};
}

describe("the reader's own results on the tab's item", () => {
	it.each([false, true])(
		"asks only for own results when review inspection is %s",
		async (canInspectReviewDetails) => {
			const { env, api } = detailsEnvironment();
			api.resolve.mockResolvedValue({ ...dto("101"), canInspectReviewDetails });
			const page = await resultPage(env, 10, "team");
			expect(api.ownResults).toHaveBeenCalledWith("team", "101", 25);
			expect(page).toMatchObject({ total: 1 });
			expect(page.rows[0]).toStrictEqual({
				id: RESULT,
				practiceName: "Descriptive merge request",
				practiceSlug: "descriptive-merge-request",
				summary: "The description never says why.",
				outcome: "NOT_MET",
				severity: "MAJOR",
				currentness: "CURRENT",
				recordedAt: "2026-09-26T11:00:00Z",
				problems: [
					{
						id: "2b3c4d5e-0000-4000-8000-000000000001",
						severity: "MAJOR",
						summary: "The description never says which issue it closes.",
					},
					{
						id: "2b3c4d5e-0000-4000-8000-000000000002",
						severity: "MINOR",
						summary: "The description lists files instead of the change.",
					},
				],
			});
		},
	);

	it("keeps only the id, severity and one line of each problem", async () => {
		const { env, api } = detailsEnvironment();
		const wider = {
			id: "2b3c4d5e-0000-4000-8000-000000000003",
			severity: "CRITICAL",
			summary: "The change drops the error.",
			explanation: "A longer text that a list row never carries.",
		} as const;
		api.ownResults.mockResolvedValue(resultResponse([ownRow({ problems: [wider] })]));
		const page = await resultPage(env, 10, "team");
		expect(page.rows[0]?.problems).toStrictEqual([
			{
				id: "2b3c4d5e-0000-4000-8000-000000000003",
				severity: "CRITICAL",
				summary: "The change drops the error.",
			},
		]);
	});

	it("refuses the results of another workspace before asking any server", async () => {
		const { env, api } = detailsEnvironment();
		await expect(resultPage(env, 10, "other")).rejects.toMatchObject({ code: "stale" });
		expect(api.ownResults).not.toHaveBeenCalled();
	});

	it("refuses a list that holds any other item, as a server without the exact filter returns", async () => {
		const { env, api } = detailsEnvironment();
		api.ownResults.mockResolvedValue(
			resultResponse([ownRow(), ownRow({ id: "other", item: OTHER_ITEM })]),
		);
		await expect(resultPage(env, 10, "team")).rejects.toMatchObject({ code: "server" });
	});

	it("drops a list whose tab moved to another item while it loaded", async () => {
		const { env, api, navigate } = detailsEnvironment();
		const answer = Promise.withResolvers<Awaited<ReturnType<DetailsApi["ownResults"]>>>();
		api.ownResults.mockReturnValueOnce(answer.promise);
		const pending = resultPage(env, 10, "team");
		await vi.waitFor(() => {
			expect(api.ownResults).toHaveBeenCalledOnce();
		});
		navigate(MR2);
		answer.resolve(resultResponse([ownRow()]));
		await expect(pending).rejects.toMatchObject({ code: "stale" });
	});

	it.each([-1, 0, 1.5, Number.NaN])(
		"rejects unusable result total %s instead of claiming completeness",
		async (total) => {
			const { env, api } = detailsEnvironment();
			api.ownResults.mockResolvedValue(resultResponse([ownRow()], total));
			await expect(resultPage(env, 10, "team")).rejects.toMatchObject({ code: "server" });
		},
	);
});

function listRow(url: string) {
	return { kind: "list-row" as const, url };
}

describe("a row of the list the tab shows", () => {
	const LIST = "https://gitlab.example.test/team/app/-/merge_requests?state=opened";

	it("resolves the row's work when the tab really shows that repository's list", async () => {
		const { env, api, navigate } = detailsEnvironment();
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
		const { env, api, navigate } = detailsEnvironment();
		navigate(LIST);
		await expect(resolveContext(env, 10, undefined, listRow(url))).resolves.toStrictEqual({
			status: "unsupported-page",
		});
		expect(api.resolve).not.toHaveBeenCalled();
	});

	it("refuses a row selector when the tab shows a work page, not a list", async () => {
		const { env, api } = detailsEnvironment();
		await expect(resolveContext(env, 10, undefined, listRow(MR2))).resolves.toStrictEqual({
			status: "unsupported-page",
		});
		expect(api.resolve).not.toHaveBeenCalled();
	});

	it("drops the answer when the tab left that list while it loaded", async () => {
		const { env, api, navigate } = detailsEnvironment();
		navigate(LIST);
		const trace = Promise.withResolvers<OwnItemTrace | null>();
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
			const { env, api, navigate } = detailsEnvironment();
			navigate(LIST);
			const answer = Promise.withResolvers<DeliveredWorkFeedback>();
			api.workFeedback.mockReturnValueOnce(answer.promise);
			const pending = workFeedback(env, 10, "team", listRow(MR1));
			await vi.waitFor(() => {
				expect(api.workFeedback).toHaveBeenCalledOnce();
			});
			navigate(url);
			answer.resolve({ item: ITEM, feedback: [] });
			await expect(pending).rejects.toMatchObject({ code: "stale" });
		},
	);

	it("keeps the answer when only the list's fragment changed", async () => {
		const { env, api, navigate } = detailsEnvironment();
		navigate(LIST);
		const trace = Promise.withResolvers<OwnItemTrace | null>();
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
		const { env, api, navigate } = detailsEnvironment();
		navigate(LIST);
		await expect(workFeedback(env, 10, "team", listRow(MR1))).resolves.toMatchObject({
			comments: [],
		});
		expect(api.workFeedback).toHaveBeenCalledWith("team", MR1);
	});
});

function delivered(overrides: Partial<DeliveredWorkFeedbackItem> = {}): DeliveredWorkFeedbackItem {
	return {
		id: "f",
		deliveredAt: "2026-09-26T11:00:00Z",
		practices: [{ slug: "descriptive-merge-request", name: "Descriptive merge request" }],
		copies: [],
		...overrides,
	};
}

function summary(overrides: Partial<DeliveredWorkFeedbackCopy> = {}): DeliveredWorkFeedbackCopy {
	return {
		id: "5c1f7a90-1b2c-4d3e-8f90-a1b2c3d4e5f6",
		kind: "SUMMARY",
		externalRef: "gid://gitlab/Note/1",
		externalUrl: `${MR1}#note_1`,
		path: null,
		startLine: null,
		endLine: null,
		side: null,
		postedAt: "2026-09-26T11:00:00Z",
		...overrides,
	};
}

describe("the reader's own feedback on the item", () => {
	it("asks for the item by its page's canonical address", async () => {
		const { env, api } = detailsEnvironment();
		await workFeedback(env, 10, "team");
		expect(api.workFeedback).toHaveBeenCalledWith("team", MR1);
	});

	it("counts one comment per provider comment, however many copies share it", async () => {
		const { env, api } = detailsEnvironment();
		api.workFeedback.mockResolvedValue({
			item: ITEM,
			feedback: [
				delivered({ id: "a", copies: [summary()] }),
				delivered({
					id: "b",
					deliveredAt: "2026-09-26T09:00:00Z",
					practices: [{ slug: "small-changes", name: "Small, focused changes" }],
					copies: [
						summary({
							id: "6d2a8b01-2c3d-4e5f-9a01-b2c3d4e5f607",
							externalUrl: null,
							postedAt: "2026-09-26T11:30:00Z",
						}),
						summary({
							id: "7e3b9c12-3d4e-4f60-8b12-c3d4e5f60718",
							kind: "LINE_NOTE",
							externalRef: "gid://gitlab/DiffNote/2",
							externalUrl: `${MR1}/diffs#note_2`,
							path: "a.ts",
							startLine: 3,
							side: "NEW",
							postedAt: "2026-09-26T09:00:00Z",
						}),
						summary({
							id: "8f4cad23-4e5f-4071-9c23-d4e5f6071829",
							kind: "LINE_NOTE",
							externalRef: "gid://gitlab/DiffNote/3",
							externalUrl: `${MR1}/diffs#note_3`,
							path: "b.ts",
							startLine: 5,
							endLine: 7,
							side: "NEW",
							postedAt: "2026-09-26T09:00:00Z",
						}),
						summary({ kind: "CHAT_TURN", externalRef: "turn-1" }),
						summary({ externalRef: " " }),
						summary({ externalRef: null }),
					],
				}),
				delivered({ id: "c", copies: [] }),
			],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer).toStrictEqual({
			comments: [
				{
					kind: "SUMMARY",
					path: undefined,
					startLine: undefined,
					endLine: undefined,
					permalink: `${MR1}#note_1`,
					practices: ["Descriptive merge request", "Small, focused changes"],
					postedAt: "2026-09-26T11:30:00Z",
				},
				{
					kind: "LINE_NOTE",
					path: "a.ts",
					startLine: 3,
					endLine: undefined,
					permalink: `${MR1}/diffs#note_2`,
					practices: ["Small, focused changes"],
					postedAt: "2026-09-26T09:00:00Z",
				},
				{
					kind: "LINE_NOTE",
					path: "b.ts",
					startLine: 5,
					endLine: 7,
					permalink: `${MR1}/diffs#note_3`,
					practices: ["Small, focused changes"],
					postedAt: "2026-09-26T09:00:00Z",
				},
			],
			fetchedAt: "2026-09-26T12:00:00.000Z",
		});
		expect(JSON.stringify(answer)).not.toContain("gid://");
		expect(JSON.stringify(answer)).not.toContain("turn-1");
	});

	it("skips a conversation turn, even one with a handle and a link on this item", async () => {
		const { env, api } = detailsEnvironment();
		api.workFeedback.mockResolvedValue({
			item: ITEM,
			feedback: [delivered({ copies: [summary({ kind: "CHAT_TURN", externalRef: "turn-1" })] })],
		});
		await expect(workFeedback(env, 10, "team")).resolves.toMatchObject({ comments: [] });
	});

	it("keeps a file comment's path without inventing lines", async () => {
		const { env, api } = detailsEnvironment();
		api.workFeedback.mockResolvedValue({
			item: ITEM,
			feedback: [
				delivered({
					copies: [
						summary({
							kind: "FILE_NOTE",
							externalRef: "gid://gitlab/DiffNote/4",
							externalUrl: `${MR1}/diffs#note_4`,
							path: "c.ts",
						}),
					],
				}),
			],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.comments).toStrictEqual([
			{
				kind: "FILE_NOTE",
				path: "c.ts",
				startLine: undefined,
				endLine: undefined,
				permalink: `${MR1}/diffs#note_4`,
				practices: ["Descriptive merge request"],
				postedAt: "2026-09-26T11:00:00Z",
			},
		]);
	});

	it("keeps an older verified link when a newer copy of the same comment has none", async () => {
		const { env, api } = detailsEnvironment();
		api.workFeedback.mockResolvedValue({
			item: ITEM,
			feedback: [
				delivered({ id: "newer", copies: [summary({ externalUrl: null })] }),
				delivered({
					id: "older",
					deliveredAt: "2026-09-26T09:00:00Z",
					copies: [summary({ postedAt: "2026-09-26T09:00:00Z" })],
				}),
			],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.comments).toHaveLength(1);
		expect(answer.comments[0]).toMatchObject({
			permalink: `${MR1}#note_1`,
			postedAt: "2026-09-26T11:00:00Z",
		});
	});

	it("names no practice for a comment whose feedback was posted only in part", async () => {
		const { env, api } = detailsEnvironment();
		api.workFeedback.mockResolvedValue({
			item: ITEM,
			feedback: [delivered({ practices: [], copies: [summary()] })],
		});
		const answer = await workFeedback(env, 10, "team");
		expect(answer.comments).toHaveLength(1);
		expect(answer.comments[0]).toMatchObject({ practices: [] });
	});

	it("keeps a comment link only when it is an anchor on this item's own pages", async () => {
		const { env, api } = detailsEnvironment();
		const links = [
			`${MR1}#note_1`,
			`${MR1}/diffs#note_2`,
			MR1,
			`${MR2}#note_3`,
			"https://gitlab.example.test/team/app/-/issues/1#note_4",
			"https://gitlab.evil.test/team/app/-/merge_requests/1#note_5",
			"http://gitlab.example.test/team/app/-/merge_requests/1#note_6",
			"javascript:alert(1)",
			"not a url",
		];
		api.workFeedback.mockResolvedValue({
			item: ITEM,
			feedback: [
				delivered({
					copies: links.map((externalUrl, index) =>
						summary({ externalRef: `ref-${index}`, externalUrl }),
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
		const { env, api, navigate } = detailsEnvironment();
		const issue = "https://gitlab.example.test/team/app/-/issues/7";
		navigate(issue);
		api.resolve.mockResolvedValue(dto("70", "scm.issue"));
		api.workFeedback.mockResolvedValue({
			item: itemRef("70", "scm.issue"),
			feedback: [
				delivered({
					copies: [
						summary({
							externalUrl: "https://gitlab.example.test/team/app/-/work_items/7#note_9",
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

	it.each([
		["another item", OTHER_ITEM],
		["no item", null],
	])("refuses an answer about %s", async (_name, item) => {
		const { env, api } = detailsEnvironment();
		api.workFeedback.mockResolvedValue({ item, feedback: [delivered({ copies: [summary()] })] });
		await expect(workFeedback(env, 10, "team")).rejects.toMatchObject({ code: "server" });
	});

	it("drops the answer when the tab moved to another item while it loaded", async () => {
		const { env, api, navigate } = detailsEnvironment();
		const answer = Promise.withResolvers<DeliveredWorkFeedback>();
		api.workFeedback.mockReturnValueOnce(answer.promise);
		const pending = workFeedback(env, 10, "team");
		await vi.waitFor(() => {
			expect(api.workFeedback).toHaveBeenCalledOnce();
		});
		navigate(MR2);
		answer.resolve({ item: ITEM, feedback: [] });
		await expect(pending).rejects.toMatchObject({ code: "stale" });
	});
});
