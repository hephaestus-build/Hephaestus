import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { getPracticeReviewSettingsQueryKey } from "@/api/@tanstack/react-query.gen";
import type { PracticeReviewSettings } from "@/api/types.gen";
import { mockReviewSettings } from "@/components/admin/practices/fixtures";
import { server } from "@/mocks/server";
import { deferred, pending } from "@/test/async";
import { usePracticeReviewSettingsMutation } from "./use-practice-review-settings";

const workspaceSlug = "acme";
const queryKey = getPracticeReviewSettingsQueryKey({ path: { workspaceSlug } });
const settings = mockReviewSettings({ etag: '"v1"', deliveryStatus: "ACTIVE" });

function createClient() {
	return new QueryClient({
		defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
	});
}

function wrapper(client: QueryClient) {
	return function QueryWrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
	};
}

function renderMutation(client: QueryClient) {
	return renderHook(
		() =>
			usePracticeReviewSettingsMutation(workspaceSlug, {
				success: "Updated",
				error: "Failed",
			}),
		{ wrapper: wrapper(client) },
	);
}

function pause(result: ReturnType<typeof renderMutation>["result"]) {
	act(() => {
		result.current.mutate({
			path: { workspaceSlug },
			body: { deliveryStatus: "PAUSED" },
		});
	});
}

describe("usePracticeReviewSettingsMutation", () => {
	it("sends the cached ETag and updates the cache before the response", async () => {
		const client = createClient();
		client.setQueryData(queryKey, settings);
		let ifMatch: string | null = null;
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/review-settings", async ({ request }) => {
				ifMatch = request.headers.get("If-Match");
				return pending();
			}),
		);
		const { result } = renderMutation(client);

		pause(result);

		await waitFor(() => expect(ifMatch).toBe('"v1"'));
		expect(client.getQueryData<PracticeReviewSettings>(queryKey)?.deliveryStatus).toBe("PAUSED");
	});

	it("restores the cached settings when the update fails", async () => {
		const client = createClient();
		client.setQueryData(queryKey, settings);
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/review-settings", () =>
				HttpResponse.json({ status: 500, title: "Failed" }, { status: 500 }),
			),
		);
		const { result } = renderMutation(client);

		pause(result);
		await waitFor(() => expect(result.current.isError).toBe(true));

		expect(client.getQueryData<PracticeReviewSettings>(queryKey)?.deliveryStatus).toBe("ACTIVE");
	});

	it("reloads the current settings after a stale ETag", async () => {
		const client = createClient();
		client.setQueryData(queryKey, settings);
		const invalidate = vi.spyOn(client, "invalidateQueries");
		server.use(
			http.patch("*/workspaces/:workspaceSlug/practices/review-settings", () =>
				HttpResponse.json({ status: 412, title: "Stale" }, { status: 412 }),
			),
		);
		const { result } = renderMutation(client);

		pause(result);
		await waitFor(() => expect(result.current.isError).toBe(true));

		expect(client.getQueryData<PracticeReviewSettings>(queryKey)?.deliveryStatus).toBe("ACTIVE");
		expect(invalidate).toHaveBeenCalledWith({ queryKey });
	});

	it("does not start a second update until the first one settles", async () => {
		const client = createClient();
		client.setQueryData(queryKey, settings);
		let requests = 0;
		const first = deferred<undefined>();
		server.use(
			http.patch(
				"*/workspaces/:workspaceSlug/practices/review-settings",
				async () => {
					requests += 1;
					await first.promise;
					return HttpResponse.json({ ...settings, etag: '"v2"', deliveryStatus: "PAUSED" });
				},
				{ once: true },
			),
			http.patch("*/workspaces/:workspaceSlug/practices/review-settings", () => {
				requests += 1;
				return HttpResponse.json({ ...settings, etag: '"v3"', deliveryStatus: "ACTIVE" });
			}),
		);
		const { result } = renderMutation(client);

		pause(result);
		act(() => {
			result.current.mutate({
				path: { workspaceSlug },
				body: { deliveryStatus: "ACTIVE" },
			});
		});
		await waitFor(() => expect(requests).toBe(1));
		first.resolve(undefined);
		await waitFor(() => expect(requests).toBe(2));
	});
});
