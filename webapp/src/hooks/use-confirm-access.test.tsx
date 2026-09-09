import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { server } from "@/mocks/server";
import { useConfirmAccess } from "./use-confirm-access";

describe("confirm access provider choices", () => {
	it("offers only linked exact instances, including an institutional provider hidden from public sign-in", async () => {
		server.use(
			http.get("*/user/identity-providers", () =>
				HttpResponse.json([
					{
						registrationId: "gitlab-lrz",
						providerType: "GITLAB",
						baseUrl: "https://gitlab.lrz.de",
					},
					{
						registrationId: "gitlab-other",
						providerType: "GITLAB",
						baseUrl: "https://gitlab.example.com",
					},
					{
						registrationId: "institution",
						providerType: "OIDC",
						baseUrl: "https://identity.example.com/realms/team/",
					},
					{
						registrationId: "institution-other",
						providerType: "OIDC",
						baseUrl: "https://identity.example.com/realms/other/",
					},
					{ registrationId: "slack", providerType: "SLACK", baseUrl: "https://slack.com" },
					{ registrationId: "github", providerType: "GITHUB", baseUrl: "https://github.com" },
					{
						registrationId: "future",
						providerType: "FUTURE",
						baseUrl: "https://future.example.com",
					},
				]),
			),
			http.get("*/user/identities", () =>
				HttpResponse.json([
					{ id: 1, providerType: "GITLAB", serverUrl: "https://gitlab.lrz.de", subject: "31" },
					{
						id: 2,
						providerType: "OIDC",
						serverUrl: "https://identity.example.com/realms/team/",
						subject: "opaque",
					},
					{ id: 3, providerType: "SLACK", serverUrl: "https://slack.com", subject: "U31" },
					{ id: 4, providerType: "GITHUB", subject: "42" },
					{ id: 5, providerType: "FUTURE", serverUrl: "https://future.example.com", subject: "42" },
				]),
			),
		);
		const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
		const { result, unmount } = renderHook(() => useConfirmAccess(true), {
			wrapper: ({ children }: { children: ReactNode }) => (
				<QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
			),
		});
		await waitFor(() => expect(result.current.loading).toBe(false));
		expect(result.current.providers.map((provider) => provider.registrationId)).toStrictEqual([
			"gitlab-lrz",
			"institution",
		]);
		unmount();
		queryClient.clear();
	});
});
