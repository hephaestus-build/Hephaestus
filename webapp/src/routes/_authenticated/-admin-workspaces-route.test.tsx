import type { QueryClient } from "@tanstack/react-query";
import { act, cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { adminListWorkspacesQueryKey } from "@/api/@tanstack/react-query.gen";
import type { AdminWorkspaceView, InstanceSettings } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

const aet: Wire<AdminWorkspaceView> = {
	id: 1,
	workspaceSlug: "aet",
	displayName: "AET",
	status: "ACTIVE",
	accountLogin: "aet-org",
	providerType: "GITHUB",
	ownerLogin: "octocat",
	memberCount: 42,
	createdAt: "2026-01-15T00:00:00Z",
};

const introCourse: Wire<AdminWorkspaceView> = {
	id: 2,
	workspaceSlug: "intro-course",
	displayName: "Intro Course",
	status: "SUSPENDED",
	accountLogin: "ase/ios",
	providerType: "GITLAB",
	memberCount: 0,
	createdAt: "2026-03-01T00:00:00Z",
};

const designLab: Wire<AdminWorkspaceView> = {
	id: 3,
	workspaceSlug: "design-lab",
	displayName: "Design Lab",
	status: "ACTIVE",
	accountLogin: "design-lab",
	providerType: "GITHUB",
	ownerLogin: "grace",
	memberCount: 5,
	createdAt: "2026-05-01T00:00:00Z",
};

const LIST_FAILURE = {
	403: "Only instance administrators can list workspaces.",
	503: "The workspace directory is temporarily unavailable.",
} as const;

/**
 * Serves the instance workspace list. Each GET answers with the status the test last chose, after
 * any hold, and is counted when its answer is sent.
 */
function serveWorkspaces(rows: Wire<AdminWorkspaceView>[]) {
	const served = {
		rows,
		status: 200 as 200 | 403 | 503,
		hold: undefined as Promise<void> | undefined,
		answered: 0,
	};
	server.use(
		// The admin layout reads the instance settings; a healthy answer keeps its own alert away.
		http.get("*/admin/settings", () =>
			HttpResponse.json({ etag: "1", silentModeEngaged: false } satisfies InstanceSettings),
		),
		http.get("*/admin/workspaces", async () => {
			await served.hold;
			served.answered += 1;
			const { status } = served;
			return status === 200
				? HttpResponse.json(served.rows)
				: HttpResponse.json(
						{ status, title: "Workspace list failed", detail: LIST_FAILURE[status] },
						{ status },
					);
		}),
	);
	return served;
}

/** Reads the list again through its own key and waits until that read has settled. */
async function rereadWorkspaces(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey: adminListWorkspacesQueryKey() });
	});
}

const viewAsUser = (name: string) => ({ name: `View as user in ${name}` });

describe("instance workspaces route", () => {
	it("keeps the listed workspaces and their support actions through a failed refresh, then retries", async () => {
		const served = serveWorkspaces([aet, introCourse]);
		const queryClient = renderRouteAt("/admin/workspaces");
		await screen.findByRole("button", viewAsUser("AET"), ROUTE_RENDER_WAIT);
		const answeredBefore = served.answered;

		served.status = 503;
		await rereadWorkspaces(queryClient);
		expect(served.answered).toBe(answeredBefore + 1);
		await screen.findByText("These workspaces may be out of date");
		expect(screen.queryByText(/^We could not load workspaces/u)).toBeNull();
		expect(screen.getByRole("button", viewAsUser("AET"))).toHaveProperty("disabled", false);
		expect(screen.getByRole("button", viewAsUser("Intro Course"))).toHaveProperty("disabled", true);

		const retry = deferred();
		served.hold = retry.promise;
		const user = userEvent.setup();
		try {
			await user.click(screen.getByRole("button", { name: "Retry" }));
			const retrying = await screen.findByRole("button", { name: "Retrying…" });
			expect(retrying).toHaveProperty("disabled", true);
			screen.getByRole("button", viewAsUser("AET"));
			expect(served.answered).toBe(answeredBefore + 1);

			served.status = 200;
			served.rows = [{ ...aet, memberCount: 43 }, introCourse, designLab];
			retry.resolve();
			await screen.findByRole("button", viewAsUser("Design Lab"));
			expect(served.answered).toBe(answeredBefore + 2);
			expect(screen.queryByText("These workspaces may be out of date")).toBeNull();
			screen.getByText("43");
		} finally {
			retry.resolve();
		}

		await user.type(screen.getByRole("searchbox", { name: "Search workspaces" }), "intro");
		await waitFor(() => expect(screen.queryByRole("button", viewAsUser("AET"))).toBeNull());
		expect(screen.queryByRole("button", viewAsUser("Design Lab"))).toBeNull();
		expect(screen.getByRole("button", viewAsUser("Intro Course"))).toHaveProperty("disabled", true);
	});

	it("takes refused workspaces away and lists only what a later authorized read returns", async () => {
		const served = serveWorkspaces([aet]);
		const queryClient = renderRouteAt("/admin/workspaces");
		await screen.findByRole("button", viewAsUser("AET"), ROUTE_RENDER_WAIT);
		const answeredBefore = served.answered;

		served.status = 403;
		await rereadWorkspaces(queryClient);
		expect(served.answered).toBe(answeredBefore + 1);
		await waitFor(() => expect(screen.queryByRole("button", viewAsUser("AET"))).toBeNull());
		await screen.findByText("You do not have access to workspaces");
		expect(screen.queryByText("AET")).toBeNull();
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();

		served.status = 503;
		await rereadWorkspaces(queryClient);
		await screen.findByText("We could not load workspaces");
		expect(screen.queryByText("AET")).toBeNull();
		expect(screen.queryByText("These workspaces may be out of date")).toBeNull();

		served.status = 200;
		served.rows = [designLab];
		await userEvent.setup().click(screen.getByRole("button", { name: "Retry" }));
		await screen.findByRole("button", viewAsUser("Design Lab"));
		expect(served.answered).toBe(answeredBefore + 3);
		expect(screen.queryByText("AET")).toBeNull();
		expect(screen.queryByText("We could not load workspaces")).toBeNull();

		// A first read that is refused says so, and lists nothing to retry or reach.
		cleanup();
		served.status = 403;
		renderRouteAt("/admin/workspaces");
		await screen.findByText("You do not have access to workspaces", undefined, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
		expect(screen.queryByText("No workspaces yet")).toBeNull();
		expect(screen.queryByRole("button", viewAsUser("Design Lab"))).toBeNull();
	});
});
