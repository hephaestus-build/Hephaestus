import { describe, expect, it } from "vitest";

import type { ConnectionSyncStatus, IntegrationCatalogEntry, SyncJob } from "@/api/types.gen";

import {
	repositoriesFact,
	setupUnreadable,
	type WorkspaceSetupFacts,
	workspaceSetupRows,
} from "./workspace-setup";

const ready: WorkspaceSetupFacts = {
	repositories: { status: "synced", repositories: 1 },
	practiceReviews: { status: "on", automatic: true, onRequest: false },
	practices: { status: "known", chosen: 3 },
	reviewModel: { status: "known", unserved: [], memberTiers: 2 },
	feedbackMode: {
		status: "known",
		approveFirst: 0,
		automatic: 3,
		off: 0,
		defaultMode: "APPROVE_FIRST",
	},
	firstReview: { status: "none" },
};

const statusOf = (facts: WorkspaceSetupFacts) =>
	Object.fromEntries(workspaceSetupRows(facts).map((row) => [row.id, row.status]));

describe("workspace setup summary", () => {
	it("marks every row done once members can get reviews, except the first review still to do", () => {
		expect(statusOf(ready)).toStrictEqual({
			repositories: "done",
			practiceReviews: "done",
			practices: "done",
			reviewModel: "done",
			feedbackMode: "done",
			firstReview: "to-do",
		});
	});

	it.each([
		["repositories", "to-do", { repositories: { status: "not-connected" } }],
		["repositories", "to-do", { repositories: { status: "empty" } }],
		["repositories", "in-progress", { repositories: { status: "syncing", percent: 40 } }],
		["repositories", "in-progress", { repositories: { status: "waiting" } }],
		["practiceReviews", "to-do", { practiceReviews: { status: "off" } }],
		[
			"practiceReviews",
			"to-do",
			{ practiceReviews: { status: "on", automatic: false, onRequest: false } },
		],
		["practices", "to-do", { practices: { status: "known", chosen: 0 } }],
		[
			"reviewModel",
			"to-do",
			{ reviewModel: { status: "known", unserved: ["IN_HOUSE", "CLOUD"], memberTiers: 2 } },
		],
	] satisfies [keyof WorkspaceSetupFacts, string, Partial<WorkspaceSetupFacts>][])(
		"leaves %s open as %s while members cannot get reviews",
		(id, status, change) => {
			expect(statusOf({ ...ready, ...change })[id]).toBe(status);
		},
	);

	it("marks a fact incomplete where only some members or some work miss out", () => {
		const partial: WorkspaceSetupFacts = {
			...ready,
			practiceReviews: { status: "on", automatic: false, onRequest: true },
			reviewModel: { status: "known", unserved: ["CLOUD"], memberTiers: 2 },
		};
		expect(statusOf(partial)).toMatchObject({
			practiceReviews: "incomplete",
			reviewModel: "incomplete",
		});
	});

	it("never reads an unknown fact as set up", () => {
		const unknown: WorkspaceSetupFacts = { ...ready, repositories: { status: "unknown" } };
		expect(statusOf(unknown).repositories).toBe("unknown");
		expect(setupUnreadable(unknown)).toBe(false);
	});

	it("is unreadable only when no fact could be read", () => {
		const nothing: WorkspaceSetupFacts = {
			repositories: { status: "unknown" },
			practiceReviews: { status: "unknown" },
			practices: { status: "unknown" },
			reviewModel: { status: "unknown" },
			feedbackMode: { status: "unknown" },
			firstReview: { status: "unknown" },
		};
		expect(setupUnreadable(nothing)).toBe(true);
	});

	it("names the feedback mode the first practices get while there are none", () => {
		const row = workspaceSetupRows({
			...ready,
			feedbackMode: {
				status: "known",
				approveFirst: 0,
				automatic: 0,
				off: 0,
				defaultMode: "APPROVE_FIRST",
			},
		}).find((candidate) => candidate.id === "feedbackMode");
		expect(row?.summary).toMatch(/^Approve first\. /u);
		expect(row?.status).toBe("done");
	});

	it("marks feedback off for every practice as incomplete", () => {
		const row = workspaceSetupRows({
			...ready,
			feedbackMode: { status: "known", approveFirst: 0, automatic: 0, off: 3, defaultMode: "OFF" },
		}).find((candidate) => candidate.id === "feedbackMode");
		expect(row?.status).toBe("incomplete");
	});
});

const catalog: IntegrationCatalogEntry[] = [
	{ kind: "GITHUB", displayName: "GitHub", connected: true, connectionId: 3 },
];
const job = (status: SyncJob["status"]): SyncJob => ({
	id: 1,
	status,
	type: "INITIAL",
	trigger: "LIFECYCLE",
	cancelRequested: false,
	createdAt: new Date("2026-10-01T00:00:00Z"),
});
const sync = (overrides: Partial<ConnectionSyncStatus>): ConnectionSyncStatus => ({
	connectionId: 3,
	kind: "GITHUB",
	connectionState: "ACTIVE",
	health: "HEALTHY",
	backfillSupported: true,
	resourceCounts: { total: 4, pending: 0, errored: 0, stale: 0 },
	...overrides,
});
const synced = new Date("2026-10-01T00:00:00Z");

describe("the repositories fact from the server's sync status", () => {
	it("says synced only when every repository has synced and none fails", () => {
		expect(
			repositoriesFact(catalog, "GITHUB", sync({ lastSuccessfulSyncAt: synced })),
		).toStrictEqual({
			status: "synced",
			repositories: 4,
		});
		// A reconciliation running over synced repositories does not reopen the row.
		expect(
			repositoriesFact(
				catalog,
				"GITHUB",
				sync({ lastSuccessfulSyncAt: synced, activeJob: job("RUNNING") }),
			),
		).toStrictEqual({ status: "synced", repositories: 4 });
	});

	it("says waiting for a queued job, and for a connection that never synced", () => {
		const neverSynced = { total: 4, pending: 4, errored: 0, stale: 0 };
		expect(
			repositoriesFact(
				catalog,
				"GITHUB",
				sync({ resourceCounts: neverSynced, activeJob: job("PENDING") }),
			),
		).toStrictEqual({ status: "waiting" });
		expect(
			repositoriesFact(catalog, "GITHUB", sync({ resourceCounts: neverSynced })),
		).toStrictEqual({
			status: "waiting",
		});
		// No repository discovered before the first sync is not "none added".
		expect(
			repositoriesFact(
				catalog,
				"GITHUB",
				sync({ resourceCounts: { total: 0, pending: 0, errored: 0, stale: 0 } }),
			),
		).toStrictEqual({ status: "waiting" });
	});

	it("says syncing only while a job runs, with the backfill percent the server gives", () => {
		expect(
			repositoriesFact(
				catalog,
				"GITHUB",
				sync({
					resourceCounts: { total: 4, pending: 3, errored: 0, stale: 0 },
					activeJob: job("RUNNING"),
					backfill: { state: "RUNNING", percent: 40 },
				}),
			),
		).toStrictEqual({ status: "syncing", percent: 40 });
	});

	it("says empty only once a finished sync found no repository", () => {
		expect(
			repositoriesFact(
				catalog,
				"GITHUB",
				sync({
					lastSuccessfulSyncAt: synced,
					resourceCounts: { total: 0, pending: 0, errored: 0, stale: 0 },
				}),
			),
		).toStrictEqual({ status: "empty" });
	});

	it.each([
		["a repository added after the last sync", { total: 4, pending: 1, errored: 0, stale: 0 }],
		[
			"a repository whose sync failed, after a job with warnings",
			{ total: 4, pending: 0, errored: 1, stale: 1 },
		],
	])("cannot establish completion with %s", (_, resourceCounts) => {
		expect(
			repositoriesFact(
				catalog,
				"GITHUB",
				sync({
					lastSuccessfulSyncAt: synced,
					lastJob: job("SUCCEEDED_WITH_WARNINGS"),
					resourceCounts,
				}),
			),
		).toStrictEqual({ status: "unknown" });
	});

	it("says not connected only from a catalog it could read", () => {
		expect(repositoriesFact([], "GITHUB", undefined)).toStrictEqual({ status: "not-connected" });
		expect(repositoriesFact(undefined, "GITHUB", undefined)).toStrictEqual({ status: "unknown" });
		expect(repositoriesFact(catalog, "GITHUB", undefined)).toStrictEqual({ status: "unknown" });
	});
});
