import { screen, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { PracticePrecomputeSummary } from "@/api/types.gen";
import { mockPractices } from "@/components/admin/practices/fixtures";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import type { Wire } from "@/lib/dates";
import { stringifySearch } from "@/lib/router-search";
import { mockPracticeDefinitionOptions } from "@/mocks/fixtures/practice";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const [base] = mockPractices;
if (base === undefined) {
	throw new Error("The shared practice fixtures no longer hold a practice to show");
}
const scripted = {
	...base,
	slug: "comment-quality",
	name: "Comment quality",
	precomputeScript: "x",
};

const summaries: Wire<PracticePrecomputeSummary>[] = [
	{
		practiceSlug: "another-practice",
		practiceName: "Another practice",
		asOf: { jobId: "11111111-1111-1111-1111-111111111111", finishedAt: "2026-10-01T09:00:00Z" },
		scriptChanged: false,
		needs: [{ purpose: "PRACTICE_EMBEDDING", need: "OPTIONAL", unmetTiers: ["CLOUD"] }],
	},
	{
		practiceSlug: "comment-quality",
		practiceName: "Comment quality",
		asOf: { jobId: "22222222-2222-2222-2222-222222222222", finishedAt: "2026-10-03T09:00:00Z" },
		scriptChanged: false,
		needs: [{ purpose: "PRACTICE_DECISION", need: "REQUIRED", unmetTiers: ["IN_HOUSE"] }],
	},
];

describe("a practice's precompute needs on practice setup", () => {
	const needsRequested = vi.fn<(workspaceSlug: string) => void>();

	beforeEach(() => {
		needsRequested.mockReset();
		server.use(
			http.get("*/workspaces/:workspaceSlug/members/me", () =>
				HttpResponse.json({ role: "ADMIN", userId: 1, userLogin: "ada", userName: "Ada" }),
			),
			http.get("*/workspaces/:workspaceSlug/practice-groups", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices", () => HttpResponse.json([scripted])),
			// Declared before the `:practiceSlug` handler, which would otherwise match these paths.
			http.get("*/workspaces/:workspaceSlug/practices/definition-options", () =>
				HttpResponse.json(mockPracticeDefinitionOptions),
			),
			http.get("*/workspaces/:workspaceSlug/practices/releases", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug/practices/precompute", ({ params }) => {
				needsRequested(String(params.workspaceSlug));
				return HttpResponse.json(summaries);
			}),
			http.get("*/workspaces/:workspaceSlug/practices/:practiceSlug", () =>
				HttpResponse.json(scripted),
			),
		);
	});

	it("shows the open practice's own needs, read for this workspace", async () => {
		renderRouteAt(
			`/w/acme/admin/practices${stringifySearch(detailSearch({ kind: "practice", id: "comment-quality" }))}`,
		);

		// The unmet required model stops the script, so the panel says so before anything else.
		const alert = await screen.findByRole("note", {}, ROUTE_RENDER_WAIT);
		expect(alert.textContent).toBe(
			"The precompute script does not run for In-house members’ work: no decision model is assigned for them. Assign a decision model",
		);
		expect(
			within(alert).getByRole("link", { name: "Assign a decision model" }).getAttribute("href"),
		).toBe("/w/acme/admin/models?purpose=PRACTICE_DECISION");
		// Another practice's needs are not this one's.
		expect(screen.queryByText(/embedding model/u)).toBeNull();
		expect(needsRequested).toHaveBeenCalledWith("acme");
	});

	it("does not ask for needs while no practice is open", async () => {
		renderRouteAt("/w/acme/admin/practices");

		await screen.findByRole("link", { name: "Comment quality" }, ROUTE_RENDER_WAIT);
		expect(needsRequested).not.toHaveBeenCalled();
	});
});
