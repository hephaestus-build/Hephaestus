import { describe, expect, it, vi } from "vitest";

import { type InfiniteQueryLike, pagedListState } from "./infinite-list";
import type { OffsetPage } from "./list-pages";

interface Row {
	id: string;
	status: string;
}

function query(
	overrides: Partial<InfiniteQueryLike<OffsetPage<Row>>>,
): InfiniteQueryLike<OffsetPage<Row>> {
	return {
		isPending: false,
		isError: false,
		isFetchNextPageError: false,
		isRefetchError: false,
		isFetching: false,
		error: null,
		data: {
			pages: [
				{ content: [{ id: "a", status: "QUEUED" }], page: 0, totalElements: 2, totalPages: 2 },
			],
			pageParams: [0],
		},
		hasNextPage: true,
		isFetchingNextPage: false,
		refetch: vi.fn(),
		fetchNextPage: vi.fn(),
		...overrides,
	};
}

describe("pagedListState", () => {
	it("keeps the loaded rows when reading them again fails", () => {
		const state = pagedListState(
			query({ isError: true, isRefetchError: true, error: new TypeError("Failed to fetch") }),
			(row) => row.id,
		);

		expect(state).toMatchObject({ status: "ready", rows: [{ id: "a" }], total: 2 });
		expect("loadMoreError" in state).toBe(false);
	});

	it("fails the list when its first page fails", () => {
		const state = pagedListState(
			query({ isError: true, error: new TypeError("Failed to fetch"), data: undefined }),
			(row) => row.id,
		);

		expect(state.status).toBe("error");
	});

	it("shows a row that two pages hold once, in its first place with its newest data", () => {
		const state = pagedListState(
			query({
				data: {
					pages: [
						{ content: [{ id: "a", status: "QUEUED" }], page: 0, totalElements: 2, totalPages: 2 },
						{
							content: [
								{ id: "a", status: "RUNNING" },
								{ id: "b", status: "COMPLETED" },
							],
							page: 1,
							totalElements: 2,
							totalPages: 2,
						},
					],
					pageParams: [0, 1],
				},
			}),
			(row) => row.id,
		);

		expect(state).toMatchObject({
			rows: [
				{ id: "a", status: "RUNNING" },
				{ id: "b", status: "COMPLETED" },
			],
		});
	});
});
