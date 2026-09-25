import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import type { PracticeStanding } from "@/api/types.gen";
import { EMPTY_REVIEW_RUN_FEED } from "@/components/profile/review-runs";
import { server } from "@/mocks/server";

import { usePracticeGroupDetail } from "./use-practice-group-detail";

const workspaceSlug = "acme";

/** A practice the workspace files in a group, and one it files in none. */
const standing = (slug: string, groupSlug?: string): PracticeStanding => ({
	name: slug,
	slug,
	groupSlug,
	standing: "NOT_OBSERVED",
	strengths: [],
	toWorkOn: [],
});

const practiceStandings = [standing("scope-one-concern", "packaging"), standing("write-tests")];

function renderDetail(selection: { groupSlug?: string; practiceSlug?: string }) {
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	return renderHook(
		() => usePracticeGroupDetail({ workspaceSlug, ...selection, practiceStandings }),
		{
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		},
	);
}

describe("usePracticeGroupDetail", () => {
	it("reads the open practice's runs once its group is open", async () => {
		const runs = vi.fn(() => HttpResponse.json({ content: [], hasNext: false, page: 0, size: 10 }));
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/review-runs", runs),
		);

		const { result } = renderDetail({
			groupSlug: "packaging",
			practiceSlug: "scope-one-concern",
		});

		await waitFor(() => expect(result.current.feed.status).toBe("ready"));
		expect(runs).toHaveBeenCalledOnce();
		expect(result.current.practice?.slug).toBe("scope-one-concern");
	});

	it("settles the feed without a request when the practice has no group to read runs from", async () => {
		const runs = vi.fn(() => HttpResponse.json({ content: [], hasNext: false, page: 0, size: 10 }));
		server.use(
			http.get("*/workspaces/:workspaceSlug/practice-groups/:groupSlug/review-runs", runs),
		);

		// The level opened alone: nothing is in flight, so a feed that said "loading" would leave a
		// skeleton on screen that no response can ever resolve.
		const { result } = renderDetail({ practiceSlug: "write-tests" });

		expect(result.current.feed).toStrictEqual(EMPTY_REVIEW_RUN_FEED);
		await waitFor(() => expect(runs).not.toHaveBeenCalled());
	});
});
