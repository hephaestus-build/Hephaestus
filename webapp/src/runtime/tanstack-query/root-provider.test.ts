import { beforeEach, expect, it, vi } from "vitest";

import { getJwksOptions } from "@/api/@tanstack/react-query.gen";

import { getContext } from "./root-provider";

const { captureException } = vi.hoisted(() => ({ captureException: vi.fn() }));

vi.mock("@/runtime/sentry", () => ({ captureException }));

beforeEach(() => {
	vi.clearAllMocks();
	getContext().queryClient.clear();
});

it("reports rejected queries and mutations without changing their rejection contract", async () => {
	const queryError = new Error("query failed");
	await expect(
		getContext().queryClient.query({
			...getJwksOptions({}),
			queryFn: async () => {
				throw queryError;
			},
		}),
	).rejects.toBe(queryError);
	expect(captureException).toHaveBeenNthCalledWith(1, queryError);

	const mutationError = new Error("mutation failed");
	const mutation = getContext()
		.queryClient.getMutationCache()
		.build(getContext().queryClient, {
			mutationFn: async () => {
				throw mutationError;
			},
		});
	await expect(mutation.execute(undefined)).rejects.toBe(mutationError);
	expect(captureException).toHaveBeenNthCalledWith(2, mutationError);
});
