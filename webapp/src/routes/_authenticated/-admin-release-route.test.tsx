import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { ReleaseStatus } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { server } from "@/mocks/server";
import { renderRouteAt, ROUTE_RENDER_WAIT } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const status = {
	running: {
		version: "1.2.3",
		channel: "RELEASE",
		commit: "a".repeat(40),
		image: `ghcr.io/hephaestus-build/application-server@sha256:${"b".repeat(64)}`,
		roles: ["SERVER"],
	},
	status: "NEVER_CHECKED",
} satisfies Wire<ReleaseStatus>;

describe("instance overview release card", () => {
	it("shows the check endpoint's answer and never folds a failure into up to date", async () => {
		let checks = 0;
		server.use(
			http.get("*/admin/release", () => HttpResponse.json(status)),
			http.post("*/admin/release/checks", () => {
				checks += 1;
				return HttpResponse.json({
					...status,
					status: "FAILED",
					failure: "RATE_LIMITED",
					lastAttempt: "2026-09-08T00:00:00Z",
					nextCheck: "2999-01-01T00:00:00Z",
					retryUntil: "2999-01-01T00:00:00Z",
				});
			}),
		);
		const user = userEvent.setup();
		renderRouteAt("/admin");
		await screen.findByText("Not checked yet", {}, ROUTE_RENDER_WAIT);
		await user.click(screen.getByRole("button", { name: "Check now" }));
		await screen.findByText(/GitHub rate-limited the request/, {}, ROUTE_RENDER_WAIT);
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
});
