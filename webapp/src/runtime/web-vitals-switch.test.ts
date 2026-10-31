import { noop, QueryClient } from "@tanstack/react-query";
import type { AnyRouter } from "@tanstack/react-router";
import { HttpResponse, http } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { mockFeatureFlags } from "@/runtime/feature-flags/testing";
import { USER_VIEW_STORAGE_KEY } from "@/runtime/user-view/session";

import { reportVisitVitals } from "./web-vitals";

// `web-vitals` observes resource timings as soon as its module loads, so off means not loaded.
const loaded = vi.hoisted(() => ({ times: 0 }));
vi.mock("web-vitals/attribution", () => {
	loaded.times += 1;
	return { onLCP: noop, onINP: noop, onCLS: noop, onTTFB: noop };
});

const PROFILE = [
	"__root__",
	"/_authenticated",
	"/_authenticated/w/$workspaceSlug/practice-profile",
];

/** Starts the reporter, as `main.tsx` does, and waits until it has decided whether to measure. */
async function land(...routeIds: string[]) {
	let resolved: () => void = noop;
	const router = {
		state: { matches: routeIds.map((routeId) => ({ routeId })) },
		subscribe: (_event: string, listener: () => void) => {
			resolved = listener;
			return noop;
		},
	};
	const queryClient = new QueryClient();
	// oxlint-disable-next-line typescript/no-unsafe-type-assertion -- A stand-in for the two router members the reporter reads.
	reportVisitVitals(router as unknown as AnyRouter, queryClient);
	resolved();
	await vi.waitFor(() => expect(queryClient.isFetching()).toBe(0));
}

afterEach(() => {
	sessionStorage.clear();
});

describe("reportVisitVitals with page experience metrics off", () => {
	it.each([
		["off", HttpResponse.json(mockFeatureFlags({ PAGE_EXPERIENCE_METRICS: false }))],
		["missing", HttpResponse.json({ ADMIN: false })],
		["refused", new HttpResponse(null, { status: 403 })],
	])("does not load web-vitals when the operator's switch is %s", async (_case, answer) => {
		server.use(http.get("*/user/features", () => answer.clone()));

		await land(...PROFILE);

		expect(loaded.times).toBe(0);
	});

	it("does not load web-vitals outside sign-in or in a user view", async () => {
		server.use(
			http.get("*/user/features", () =>
				HttpResponse.json(mockFeatureFlags({ PAGE_EXPERIENCE_METRICS: true })),
			),
		);
		sessionStorage.setItem(
			USER_VIEW_STORAGE_KEY,
			JSON.stringify({
				operatorAccountId: 1,
				workspaceSlug: "acme",
				workspaceName: "Acme",
				accountId: "2",
				providerLogin: "ada",
				name: "Ada",
				unclaimed: false,
				reason: "Support request",
			}),
		);

		await land("__root__", "/login");
		await land(...PROFILE);

		expect(loaded.times).toBe(0);
	});
});
