import { fireEvent, screen } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import { adminGetReleaseOptions } from "@/api/@tanstack/react-query.gen";
import type { ReleaseStatus } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const status = {
	running: {
		version: "1.2.3",
		commit: "a".repeat(40),
		channel: "stable",
		identityStatus: "UNKNOWN",
		roles: ["server"],
		images: {},
	},
	status: "NEVER_CHECKED",
	enabled: true,
	backupRestoreStatus: "UNKNOWN",
	upgradeGuideUrl: "https://docs.hephaestus.build/admin/install#upgrades",
} satisfies Wire<ReleaseStatus>;

describe("instance overview release information", () => {
	it("uses the generated check response and retains failure instead of claiming current", async () => {
		server.use(
			http.get("*/admin/release", () => HttpResponse.json(status)),
			http.post("*/admin/release/checks", () =>
				HttpResponse.json({
					...status,
					status: "CHECK_FAILED",
					failureReason: "RATE_LIMITED",
					lastAttempt: "2026-09-08T00:00:00Z",
				}),
			),
		);
		renderRouteAt("/admin");
		await screen.findByText("Never checked", {}, ROUTE_RENDER_WAIT);
		fireEvent.click(screen.getByRole("button", { name: "Check for updates" }));
		await screen.findByText("Update check failed", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByText("Current release")).toBeNull();
		expect(screen.getByRole("status").textContent).toBe(
			"Update check completed: Update check failed.",
		);
	});
	it("does not offer a manual bypass when update checks are disabled", async () => {
		server.use(
			http.get("*/admin/release", () =>
				HttpResponse.json({ ...status, status: "DISABLED", enabled: false }),
			),
		);
		renderRouteAt("/admin");
		await screen.findByText("Update checks disabled", {}, ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("button", { name: "Check for updates" })).toBeNull();
	});
	it("retains running identity when a background refresh fails", async () => {
		server.use(http.get("*/admin/release", () => HttpResponse.json(status)));
		const queryClient = renderRouteAt("/admin");
		await screen.findByText("Never checked", {}, ROUTE_RENDER_WAIT);
		server.use(
			http.get("*/admin/release", () => HttpResponse.json({ status: 503 }, { status: 503 })),
		);
		await queryClient.invalidateQueries({ queryKey: adminGetReleaseOptions().queryKey });
		await screen.findByText("Could not refresh release information", {}, ROUTE_RENDER_WAIT);
		expect(screen.getByText("1.2.3 · stable")).not.toBeNull();
		expect(screen.getByText("Never checked")).not.toBeNull();
	});
	it("does not let an older background response overwrite a completed manual check", async () => {
		server.use(
			http.get("*/admin/release", () => HttpResponse.json(status)),
			http.post("*/admin/release/checks", () =>
				HttpResponse.json({ ...status, status: "CHECK_FAILED", failureReason: "RATE_LIMITED" }),
			),
		);
		const queryClient = renderRouteAt("/admin");
		await screen.findByText("Never checked", {}, ROUTE_RENDER_WAIT);
		let markStarted = () => {};
		const started = new Promise<void>((resolve) => {
			markStarted = resolve;
		});
		let releaseResponse = () => {};
		const response = new Promise<void>((resolve) => {
			releaseResponse = resolve;
		});
		server.use(
			http.get("*/admin/release", async () => {
				markStarted();
				await response;
				return HttpResponse.json(status);
			}),
		);
		const refresh = queryClient.invalidateQueries({ queryKey: adminGetReleaseOptions().queryKey });
		await started;
		try {
			fireEvent.click(screen.getByRole("button", { name: "Check for updates" }));
			await screen.findByText("Update check failed", {}, ROUTE_RENDER_WAIT);
		} finally {
			releaseResponse();
		}
		await refresh;
		expect(screen.queryByText("Never checked")).toBeNull();
		expect(screen.getByText("Update check failed")).not.toBeNull();
	});
});
