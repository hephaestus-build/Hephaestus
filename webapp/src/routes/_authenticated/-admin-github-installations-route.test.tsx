import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { describe, expect, it, vi } from "vitest";

import type { GitHubInstallationRequest, InstanceSettings } from "@/api/types.gen";
import type { Wire } from "@/lib/dates";
import { toast } from "@/lib/toast";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// Mounting the real route pulls in the whole admin layout and its lazy modules.
vi.setConfig({ testTimeout: 20_000 });

// The route harness renders no toaster, so the outcome a toast reports is read from its call.
vi.mock("@/lib/toast", () => ({
	toast: Object.assign(vi.fn(), {
		success: vi.fn(),
		error: vi.fn(),
		warning: vi.fn(),
		info: vi.fn(),
	}),
}));

const pending: Wire<GitHubInstallationRequest> = {
	installationId: 101,
	accountLogin: "octo-org",
	accountKind: "ORGANIZATION",
	status: "PENDING",
	receivedAt: "2026-11-01T00:00:00Z",
};

const declined: Wire<GitHubInstallationRequest> = {
	installationId: 102,
	accountLogin: "mona",
	accountKind: "USER",
	status: "DECLINED",
	receivedAt: "2026-11-01T00:00:00Z",
	decidedAt: "2026-11-02T00:00:00Z",
};

/** Serves the installation list and records each decision that the page sends. */
function serveInstallations(rows: Wire<GitHubInstallationRequest>[]) {
	const served = { rows, decisions: [] as string[], refuse: undefined as number | undefined };
	server.use(
		// The admin layout reads the instance settings; a healthy answer keeps its own alert away.
		http.get("*/admin/settings", () =>
			HttpResponse.json({ etag: "1", silentModeEngaged: false } satisfies InstanceSettings),
		),
		http.get("*/admin/github-installations", () => HttpResponse.json(served.rows)),
		http.post("*/admin/github-installations/:id/:decision", ({ params }) => {
			served.decisions.push(`${String(params.decision)} ${String(params.id)}`);
			if (served.refuse !== undefined) {
				return HttpResponse.json(
					{
						status: served.refuse,
						title: "Conflict",
						detail: "GitHub no longer has this installation. It was uninstalled.",
					},
					{ status: served.refuse },
				);
			}
			const id = Number(params.id);
			const row = served.rows.find((candidate) => candidate.installationId === id);
			if (row === undefined) {
				return HttpResponse.json({ status: 404 }, { status: 404 });
			}
			const decided: Wire<GitHubInstallationRequest> =
				params.decision === "adopt"
					? {
							...row,
							status: "ADOPTED",
							decidedAt: "2026-11-03T00:00:00Z",
							workspaceSlug: row.accountLogin,
						}
					: { ...row, status: "DECLINED", decidedAt: "2026-11-03T00:00:00Z" };
			served.rows = served.rows.map((candidate) =>
				candidate.installationId === id ? decided : candidate,
			);
			return HttpResponse.json(decided);
		}),
	);
	return served;
}

describe("instance GitHub installations route", () => {
	it("adopts a waiting installation and lists it with its workspace", async () => {
		const served = serveInstallations([pending, declined]);
		renderRouteAt("/admin/github-installations");
		const adopt = await screen.findByRole(
			"button",
			{ name: "Adopt the installation on octo-org" },
			ROUTE_RENDER_WAIT,
		);

		await userEvent.setup().click(adopt);

		await screen.findByRole("link", { name: "Open workspace for octo-org" });
		expect(served.decisions).toStrictEqual(["adopt 101"]);
		expect(toast.success).toHaveBeenCalledWith("Adopted the installation on octo-org");
	});

	it("declines a waiting installation and keeps it adoptable", async () => {
		const served = serveInstallations([pending]);
		renderRouteAt("/admin/github-installations");
		const decline = await screen.findByRole(
			"button",
			{ name: "Decline the installation on octo-org" },
			ROUTE_RENDER_WAIT,
		);

		await userEvent.setup().click(decline);

		await waitFor(() =>
			expect(
				screen.queryByRole("button", { name: "Decline the installation on octo-org" }),
			).toBeNull(),
		);
		expect(served.decisions).toStrictEqual(["decline 101"]);
		expect(
			screen.getByRole("button", { name: "Adopt the installation on octo-org" }),
		).toHaveProperty("disabled", false);
	});

	it("says why an adoption was refused and keeps the installation waiting", async () => {
		const served = serveInstallations([pending]);
		served.refuse = 409;
		renderRouteAt("/admin/github-installations");
		const adopt = await screen.findByRole(
			"button",
			{ name: "Adopt the installation on octo-org" },
			ROUTE_RENDER_WAIT,
		);

		await userEvent.setup().click(adopt);

		await waitFor(() =>
			expect(toast.error).toHaveBeenCalledWith(
				"GitHub no longer has this installation. It was uninstalled.",
			),
		);
		expect(served.decisions).toStrictEqual(["adopt 101"]);
		screen.getByText("Waiting for approval");
	});
});
