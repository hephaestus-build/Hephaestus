import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { EMPTY_REVIEW_FEED, type ReviewFeedState } from "@/components/profile/reviews";
import { server } from "@/mocks/server";
import { practiceStandingOf } from "@/stories/practice-profile-story-mock-data";

import { usePracticeGroupDetail } from "./use-practice-group-detail";

const workspaceSlug = "acme";

/** A practice the workspace files in a group, and one it files in none. */
const standing = (slug: string, groupSlug: string | null = null) =>
	practiceStandingOf({ name: slug, slug, groupSlug, standing: "NOT_OBSERVED" });

const REVIEWS = "*/workspaces/:workspaceSlug/practice-groups/:groupSlug/reviews";

const page = (number: number, totalPages = 1) => ({
	content: [],
	page: number,
	size: 10,
	totalElements: 0,
	totalPages,
});

const practiceStandings = [standing("scope-one-concern", "packaging"), standing("write-tests")];

function renderDetail(selection: { groupSlug?: string; practiceSlug?: string }) {
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	return renderHook(
		() =>
			usePracticeGroupDetail({
				workspaceSlug,
				...selection,
				practiceStandings,
				reviewedPractices: [],
			}),
		{
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={client}>{children}</QueryClientProvider>
			),
		},
	);
}

/** The feed once its first page is in; a test that reads it earlier fails here. */
function readyFeed(feed: ReviewFeedState): Extract<ReviewFeedState, { status: "ready" }> {
	if (feed.status !== "ready") {
		throw new Error(`Expected a ready feed, got ${feed.status}.`);
	}
	return feed;
}

function twoPages(pagesAsked: string[]) {
	return ({ request }: { request: Request }) => {
		const asked = new URL(request.url).searchParams.get("page") ?? "0";
		pagesAsked.push(asked);
		return HttpResponse.json(page(Number(asked), 2));
	};
}

describe("usePracticeGroupDetail", () => {
	it("reads the open practice's reviews once its group is open", async () => {
		const reviews = vi.fn(() => HttpResponse.json(page(0)));
		server.use(http.get(REVIEWS, reviews));

		const { result } = renderDetail({
			groupSlug: "packaging",
			practiceSlug: "scope-one-concern",
		});

		await waitFor(() => expect(result.current.feed.status).toBe("ready"));
		expect(reviews).toHaveBeenCalledOnce();
		expect(result.current.practice?.slug).toBe("scope-one-concern");
	});

	it("asks for the page after the last one when the feed loads more", async () => {
		const pagesAsked: string[] = [];
		server.use(http.get(REVIEWS, twoPages(pagesAsked)));

		const { result } = renderDetail({ groupSlug: "packaging", practiceSlug: "scope-one-concern" });
		await waitFor(() => expect(readyFeed(result.current.feed).hasMore).toBe(true));

		act(() => readyFeed(result.current.feed).onLoadMore());

		await waitFor(() => expect(readyFeed(result.current.feed).hasMore).toBe(false));
		expect(pagesAsked).toStrictEqual(["0", "1"]);
	});

	it("reads a practice's reviews through its own group when the level opens on its own", async () => {
		const groupsRead: unknown[] = [];
		server.use(
			http.get(REVIEWS, ({ params }) => {
				groupsRead.push(params.groupSlug);
				return HttpResponse.json(page(0));
			}),
		);

		const { result } = renderDetail({ practiceSlug: "scope-one-concern" });

		await waitFor(() => expect(result.current.feed.status).toBe("ready"));
		expect(groupsRead).toStrictEqual(["packaging"]);
	});

	it("opens a practice in no group, with a settled feed and no request", async () => {
		const reviews = vi.fn(() => HttpResponse.json(page(0)));
		server.use(http.get(REVIEWS, reviews));

		// The level opened alone: nothing is in flight, so a feed that said "loading" would leave a
		// skeleton on screen that no response can ever resolve.
		const { result } = renderDetail({ practiceSlug: "write-tests" });

		expect(result.current.practice?.slug).toBe("write-tests");
		expect(result.current.feed).toStrictEqual(EMPTY_REVIEW_FEED);
		// oxlint-disable-next-line no-restricted-globals, promise/avoid-new -- proves that no read starts on the next turn
		await new Promise<void>((resolve) => setTimeout(resolve, 0));
		expect(reviews).not.toHaveBeenCalled();
	});
});
