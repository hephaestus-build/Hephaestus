import type { QueryClient } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeAll, assert, describe, expect, it } from "vitest";

import { adminGetReleaseQueryKey } from "@/api/@tanstack/react-query.gen";
import type { ReleaseStatus } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { deferred } from "@/test/async";
import { loadRouteCode, renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

beforeAll(() => loadRouteCode("/admin"), ROUTE_RENDER_WAIT.timeout);

const status = {
	running: {
		version: "1.2.3",
		channel: "RELEASE",
		environment: "production",
		commit: "a".repeat(40),
		image: `ghcr.io/hephaestus-build/application-server@sha256:${"b".repeat(64)}`,
		roles: ["SERVER"],
	},
	status: "NEVER_CHECKED",
	runningSince: "2026-09-08T00:00:00Z",
	history: [
		{
			version: "1.2.3",
			channel: "RELEASE",
			environment: "production",
			startedAt: "2026-09-08T00:00:00Z",
		},
	],
} satisfies Wire<ReleaseStatus>;

const upgraded = {
	...status,
	running: { ...status.running, version: "1.2.4" },
} satisfies Wire<ReleaseStatus>;

type ReleaseRead = { status: 200; body: Wire<ReleaseStatus> } | { status: 403 | 503 };

/** Answers every release read with the answer the test last chose, held until `hold` settles. */
function serveReleaseReads() {
	const reads: { count: number; answer: ReleaseRead; hold: Promise<void> } = {
		count: 0,
		answer: { status: 200, body: status },
		hold: Promise.resolve(),
	};
	server.use(
		http.get("*/admin/release", async () => {
			reads.count += 1;
			await reads.hold;
			const { answer } = reads;
			return answer.status === 200
				? HttpResponse.json(answer.body)
				: HttpResponse.json(
						{ status: answer.status, title: "Request failed" },
						{ status: answer.status },
					);
		}),
	);
	return reads;
}

/** Reads the release again through its own key, as returning to the page does. */
async function rereadRelease(queryClient: QueryClient) {
	await act(async () => {
		await queryClient.invalidateQueries({ queryKey: adminGetReleaseQueryKey() });
	});
}

/** The overview's other cards have no API here and show alerts of their own; this is the release's. */
async function findReleaseAlert(title: string) {
	await screen.findByText(title);
	const alert = screen.getAllByRole("alert").find((node) => node.textContent.includes(title));
	assert(alert);
	return alert;
}

describe("instance overview release card", () => {
	it("shows the check endpoint's answer and never folds a failure into up to date", async () => {
		let checks = 0;
		// The server keeps what a check found, so a later read returns it.
		let current: Wire<ReleaseStatus> = status;
		server.use(
			http.get("*/admin/release", () => HttpResponse.json(current)),
			http.post("*/admin/release/checks", () => {
				checks += 1;
				current = {
					...status,
					status: "FAILED",
					failure: "RATE_LIMITED",
					lastAttempt: "2026-09-08T00:00:00Z",
					nextCheck: "2999-01-01T00:00:00Z",
					retryUntil: "2999-01-01T00:00:00Z",
				};
				return HttpResponse.json(current);
			}),
		);
		const user = userEvent.setup();
		renderRouteAt("/admin");
		await screen.findByText("Not checked yet", {}, ROUTE_RENDER_WAIT);
		expect(screen.getByText(/running in/iu).textContent).toMatch(
			/^Running in production, started .+ ago\.$/u,
		);
		await user.click(screen.getByRole("button", { name: "Check now" }));
		await screen.findByText(/GitHub rate-limited the request/u, {}, ROUTE_RENDER_WAIT);
		expect(checks).toBe(1);
		expect(screen.queryByText("Up to date")).toBeNull();
		expect(screen.getByRole("status").textContent).toBe("Check failed");
		expect(screen.getByRole("button", { name: "Check now" }).hasAttribute("disabled")).toBe(true);
	});

	it("keeps the rest of the overview when release information is unavailable", async () => {
		server.use(http.get("*/admin/release", () => HttpResponse.json({}, { status: 503 })));
		renderRouteAt("/admin");
		await screen.findByText("Release information is unavailable", {}, ROUTE_RENDER_WAIT);
		expect(screen.getByText("Delivery")).not.toBeNull();
	});

	it("keeps the last release with a notice when a refresh fails, then reads it again", async () => {
		const reads = serveReleaseReads();
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin");
		await screen.findByText("v1.2.3", {}, ROUTE_RENDER_WAIT);

		reads.answer = { status: 503 };
		await rereadRelease(queryClient);
		expect(reads.count).toBe(2);
		const notice = await findReleaseAlert("Release information may be out of date");
		screen.getByText("v1.2.3");
		screen.getByRole("button", { name: "Check now" });

		const fresh = deferred();
		reads.answer = { status: 200, body: upgraded };
		reads.hold = fresh.promise;
		try {
			await user.click(within(notice).getByRole("button", { name: "Retry" }));
			const retrying = await within(notice).findByRole("button", { name: "Retrying…" });
			expect(retrying.hasAttribute("disabled")).toBe(true);
		} finally {
			fresh.resolve();
		}
		await screen.findByText("v1.2.4");
		expect(reads.count).toBe(3);
		expect(screen.queryByText("v1.2.3")).toBeNull();
		expect(screen.queryByText("Release information may be out of date")).toBeNull();
	});

	it("hides a refused release, does not bring it back on an outage, and shows a fresh read", async () => {
		const reads = serveReleaseReads();
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin");
		await screen.findByText("v1.2.3", {}, ROUTE_RENDER_WAIT);

		reads.answer = { status: 403 };
		await rereadRelease(queryClient);
		expect(reads.count).toBe(2);
		const refused = await findReleaseAlert("Release information is unavailable");
		expect(refused.textContent).toContain("Ask a workspace admin or instance admin for access.");
		expect(within(refused).queryByRole("button", { name: "Retry" })).toBeNull();
		expect(screen.queryByText("v1.2.3")).toBeNull();
		expect(screen.queryByRole("button", { name: "Check now" })).toBeNull();

		reads.answer = { status: 503 };
		await rereadRelease(queryClient);
		expect(reads.count).toBe(3);
		const outage = await findReleaseAlert("Release information is unavailable");
		await within(outage).findByText(/Try again in a moment\./u);
		expect(screen.queryByText("v1.2.3")).toBeNull();
		expect(screen.queryByText("Release information may be out of date")).toBeNull();

		reads.answer = { status: 200, body: upgraded };
		await user.click(within(outage).getByRole("button", { name: "Retry" }));
		await screen.findByText("v1.2.4");
		expect(reads.count).toBe(4);
		expect(screen.queryByText("v1.2.3")).toBeNull();
		screen.getByRole("button", { name: "Check now" });
	});

	it("does not show a check's answer once reading the release is refused", async () => {
		const reads = serveReleaseReads();
		const checked = deferred();
		server.use(
			http.post("*/admin/release/checks", async () => {
				await checked.promise;
				return HttpResponse.json({
					...status,
					status: "CURRENT",
					lastAttempt: "2026-09-08T00:00:00Z",
					lastSuccess: "2026-09-08T00:00:00Z",
				});
			}),
		);
		const user = userEvent.setup();
		const queryClient = renderRouteAt("/admin");
		try {
			await user.click(await screen.findByRole("button", { name: "Check now" }, ROUTE_RENDER_WAIT));
			await screen.findByRole("button", { name: "Checking…" });

			reads.answer = { status: 403 };
			await rereadRelease(queryClient);
			await findReleaseAlert("Release information is unavailable");
			expect(screen.queryByText("v1.2.3")).toBeNull();

			checked.resolve();
			await waitFor(() => expect(queryClient.isMutating()).toBe(0));
			reads.answer = { status: 503 };
			await rereadRelease(queryClient);
			const outage = await findReleaseAlert("Release information is unavailable");
			await within(outage).findByText(/Try again in a moment\./u);
			expect(screen.queryByText("v1.2.3")).toBeNull();
			expect(screen.queryByText("Up to date")).toBeNull();

			reads.answer = { status: 200, body: upgraded };
			await user.click(within(outage).getByRole("button", { name: "Retry" }));
			await screen.findByText("v1.2.4");
			expect(reads.count).toBe(5);
			expect(screen.queryByText("Up to date")).toBeNull();
		} finally {
			checked.resolve();
		}
	});
});
