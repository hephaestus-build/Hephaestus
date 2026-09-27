import { QueryClient, QueryObserver, type QueryKey } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";

import {
	getInAppFeedbackQueryKey,
	getObservationQueryKey,
	listPracticeGroupReviewRunsInfiniteQueryKey,
} from "@/api/@tanstack/react-query.gen";
import { createClient } from "@/api/client";

import { feedbackResponseOptions, refreshAnswerCarriers } from "./response-query";

const path = { workspaceSlug: "team", feedbackId: "feedback" };

function request(response: () => Response) {
	const client = createClient({
		baseUrl: "https://example.org/api",
		fetch: async () => response(),
	});
	const cache = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	return { cache, options: feedbackResponseOptions({ path, client }) };
}

describe("reading a feedback answer", () => {
	it("caches a successful empty response rather than turning an unanswered item into an error", async () => {
		const { cache, options } = request(() => new Response(null, { status: 204 }));
		await expect(cache.query(options)).resolves.toBeNull();
		expect(cache.getQueryState(options.queryKey)?.status).toBe("success");
		expect(cache.getQueryData(options.queryKey)).toBeNull();
		cache.clear();
	});

	it("reads a 204 as unanswered when the fetch still hands over a body stream, as expo/fetch does", async () => {
		// Node's fetch gives a 204 a null body; on a device expo/fetch always gives a stream, which the
		// generated client returns as the data of a response without a Content-Type.
		const { cache, options } = request(() => {
			const empty = new Response(null, { status: 204 });
			Object.defineProperty(empty, "body", { value: new ReadableStream() });
			return empty;
		});
		await expect(cache.query(options)).resolves.toBeNull();
		cache.clear();
	});

	it("keeps a saved answer and the generated date transformation", async () => {
		const { cache, options } = request(() =>
			Response.json({
				feedbackId: "feedback",
				usefulness: "HELPFUL",
				respondedAt: "2026-09-27T08:00:00Z",
			}),
		);
		await expect(cache.query(options)).resolves.toStrictEqual({
			feedbackId: "feedback",
			usefulness: "HELPFUL",
			respondedAt: new Date("2026-09-27T08:00:00Z"),
		});
		cache.clear();
	});

	it("does not mistake failed ownership or delivery checks for an unanswered item", async () => {
		const { cache, options } = request(() => Response.json({ status: 404 }, { status: 404 }));
		await expect(cache.query(options)).rejects.toStrictEqual({ status: 404 });
		expect(cache.getQueryState(options.queryKey)?.status).toBe("error");
		cache.clear();
	});
});

const observation = (workspaceSlug: string, observationId: string) =>
	getObservationQueryKey({ path: { workspaceSlug, observationId } });
const history = (groupSlug: string, practiceSlug?: string) =>
	listPracticeGroupReviewRunsInfiniteQueryKey({
		path: { workspaceSlug: "team", groupSlug },
		query: { size: 10, ...(practiceSlug === undefined ? {} : { practiceSlug }) },
	});

/** A read on screen: it counts how often it is fetched. */
function onScreen(cache: QueryClient, queryKey: QueryKey) {
	let reads = 0;
	const observer = new QueryObserver(cache, {
		queryKey,
		queryFn: () => {
			reads += 1;
			return reads;
		},
	});
	const stop = observer.subscribe(() => undefined);
	return { reads: () => reads, stop };
}

describe("after an answer changes", () => {
	it("reads every observation and review run in the workspace again, whichever caller answered", async () => {
		const cache = new QueryClient();
		const carriers = [
			observation("team", "a"),
			observation("team", "b"),
			history("testing"),
			history("testing", "tests-with-change"),
			history("reviews"),
		];
		for (const key of carriers) {
			cache.setQueryData(key, []);
		}
		const shown = onScreen(cache, observation("team", "shown"));
		await cache.getQueryCache().find({ queryKey: observation("team", "shown") })?.promise;

		await refreshAnswerCarriers(cache, "team");

		for (const key of carriers) {
			expect(cache.getQueryState(key)?.isInvalidated).toBe(true);
		}
		expect(shown.reads()).toBe(2);
		shown.stop();
		cache.clear();
	});

	it("leaves other workspaces alone and never reads the in-app feedback list, which would deliver it", async () => {
		const cache = new QueryClient();
		const elsewhere = observation("other", "a");
		cache.setQueryData(elsewhere, []);
		const inApp = getInAppFeedbackQueryKey({ path: { workspaceSlug: "team" } });
		const list = onScreen(cache, inApp);
		await cache.getQueryCache().find({ queryKey: inApp })?.promise;

		await refreshAnswerCarriers(cache, "team");

		expect(cache.getQueryState(elsewhere)?.isInvalidated).toBe(false);
		expect(cache.getQueryState(inApp)?.isInvalidated).toBe(false);
		expect(list.reads()).toBe(1);
		list.stop();
		cache.clear();
	});
});
