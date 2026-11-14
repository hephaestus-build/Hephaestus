import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";

import { useLegalContent } from "./use-legal-content";

function wrapper() {
	const client = new QueryClient();
	return function QueryWrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
	};
}

const markdown = (body: string) =>
	new HttpResponse(body, { headers: { "Content-Type": "text/markdown" } });

describe("useLegalContent", () => {
	it("reads the operator's page when the deployment mounts one", async () => {
		server.use(http.get("*/legal-overrides/imprint.md", () => markdown("# Operator")));
		const { result } = renderHook(() => useLegalContent("imprint"), { wrapper: wrapper() });
		expect(result.current).toStrictEqual({ status: "loading" });
		await waitFor(() =>
			expect(result.current).toStrictEqual({
				status: "ready",
				content: { markdown: "# Operator", source: "override" },
			}),
		);
	});

	it("warns once per page, however often the page mounts", async () => {
		using warn = vi.spyOn(console, "warn").mockReturnValue(undefined);
		server.use(
			http.get("*/legal-overrides/privacy.md", () => new HttpResponse(null, { status: 404 })),
			http.get("*/legal/_disclaimer/privacy.md", () => markdown("# fallback")),
		);
		for (const _mount of [1, 2]) {
			const { result, unmount } = renderHook(() => useLegalContent("privacy"), {
				wrapper: wrapper(),
			});
			await waitFor(() => expect(result.current.status).toBe("ready"));
			unmount();
		}
		expect(warn).toHaveBeenCalledExactlyOnceWith(
			"[legal] Disclaimer fallback served for page=privacy. Mount /legal-overrides/. See docs/admin/legal-pages.",
		);
	});

	it("reports an error when no page, not even the fallback, can be read", async () => {
		server.use(
			http.get("*/legal-overrides/imprint.md", () => new HttpResponse(null, { status: 404 })),
			http.get("*/legal/_disclaimer/imprint.md", () => new HttpResponse(null, { status: 404 })),
		);
		const { result } = renderHook(() => useLegalContent("imprint"), { wrapper: wrapper() });
		await waitFor(() => expect(result.current).toStrictEqual({ status: "error" }));
	});
});
