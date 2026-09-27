import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";

import { createClient } from "@/api/client";

import { feedbackResponseOptions } from "./response-query";

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
