import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import type { WorkspaceMembership } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { useWorkspacePasskeySettings } from "./use-workspace-passkey-settings";

const membership = {
	createdAt: "2026-01-01T00:00:00Z",
	eligibleForPracticeReview: true,
	hidden: false,
	role: "OWNER",
	userId: 1,
	userLogin: "owner",
} satisfies Wire<WorkspaceMembership>;

function setup() {
	const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
	function Wrapper({ children }: { children: ReactNode }) {
		return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
	}
	return renderHook(() => useWorkspacePasskeySettings({ workspaceSlug: "acme" }), {
		wrapper: Wrapper,
	});
}

describe("useWorkspacePasskeySettings", () => {
	it("reports an unverifiable membership and retries both requests", async () => {
		let policyRequests = 0;
		let membershipRequests = 0;
		server.use(
			http.get("*/workspaces/acme/passkey-policy", () => {
				policyRequests += 1;
				return HttpResponse.json({ required: false, instanceRequired: false });
			}),
			http.get(
				"*/workspaces/acme/members/me",
				() => {
					membershipRequests += 1;
					return HttpResponse.json(
						{ status: 403, detail: "Could not verify membership." },
						{ status: 403 },
					);
				},
				{ once: true },
			),
			http.get("*/workspaces/acme/members/me", () => {
				membershipRequests += 1;
				return HttpResponse.json(membership);
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.error).toBe("Could not verify membership."));
		expect(result.current.required).toBeUndefined();
		act(() => result.current.onRetry());
		await waitFor(() => expect(result.current.owner).toBe(true));
		expect(result.current.error).toBeUndefined();
		expect(policyRequests).toBe(2);
		expect(membershipRequests).toBe(2);
	});

	it("sends the workspace policy choice and reloads the server policy", async () => {
		let required = false;
		server.use(
			http.get("*/workspaces/acme/passkey-policy", () =>
				HttpResponse.json({ required, instanceRequired: false }),
			),
			http.get("*/workspaces/acme/members/me", () => HttpResponse.json(membership)),
			http.patch("*/workspaces/acme/passkey-policy", async ({ request }) => {
				await expect(request.json()).resolves.toStrictEqual({ required: true });
				required = true;
				return HttpResponse.json({ required, instanceRequired: false });
			}),
		);
		const { result } = setup();
		await waitFor(() => expect(result.current.owner).toBe(true));
		act(() => result.current.onChange(true));
		await waitFor(() => expect(result.current.required).toBe(true));
		expect(result.current.pending).toBe(false);
	});
});
