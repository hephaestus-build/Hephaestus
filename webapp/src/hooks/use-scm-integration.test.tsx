import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import type { ReactNode } from "react";
import { expect, it } from "vitest";

import type { Workspace } from "@/api/types.gen";
import { Toaster } from "@/components/ui/sonner";
import { problemType } from "@/lib/problem-detail";
import { server } from "@/mocks/server";
import { AuthProvider } from "@/runtime/auth/AuthContext";
import { useScmIntegration } from "./use-scm-integration";

it("refreshes the repository list and reports the pending copy after monitoring stops", async () => {
	let repositories = ["team/example"];
	const date = new Date("2026-01-01T00:00:00Z");
	const workspace = {
		id: 1,
		kind: "GITHUB",
		accountLogin: "team",
		workspaceAddress: "team",
		workspaceSlug: "test-workspace",
		displayName: "Test workspace",
		status: "ACTIVE",
		gitlabWebhookRegistered: false,
		hasPersonalAccessToken: true,
		hasSlackToken: false,
		practiceReviewEventEnabled: false,
		practiceReviewRequestEnabled: false,
		practicesEnabled: false,
		publicActivityEnabled: false,
		createdAt: date,
		updatedAt: date,
	} satisfies Workspace;
	server.use(
		http.get("*/workspaces/:slug", () => HttpResponse.json(workspace)),
		http.get("*/workspaces/:slug/connections/catalog", () => HttpResponse.json([])),
		http.get("*/workspaces/:slug/repositories", () => HttpResponse.json(repositories)),
		http.delete("*/workspaces/:slug/repositories", () => {
			repositories = [];
			return HttpResponse.json(
				{ status: 503, type: problemType("journal-pending") },
				{ status: 503, headers: { "content-type": "application/problem+json" } },
			);
		}),
	);

	const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	function Providers({ children }: { children: ReactNode }) {
		return (
			<QueryClientProvider client={queryClient}>
				<AuthProvider>{children}</AuthProvider>
				<Toaster />
			</QueryClientProvider>
		);
	}

	const { result } = renderHook(() => useScmIntegration("test-workspace"), { wrapper: Providers });
	await waitFor(() =>
		expect(result.current.repositoriesSettingsProps.repositories).toStrictEqual([
			{ nameWithOwner: "team/example" },
		]),
	);

	act(() => result.current.repositoriesSettingsProps.onRemoveRepository("team/example"));

	await screen.findByText("Monitoring stopped. The recovery copy is pending.");
	await waitFor(() =>
		expect(result.current.repositoriesSettingsProps.repositories).toStrictEqual([]),
	);
});
