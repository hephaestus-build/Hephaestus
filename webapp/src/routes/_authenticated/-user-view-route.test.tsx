import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { clearUserView, getUserViewSession, startUserView } from "@/runtime/user-view/session";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.mock("@/runtime/user-view/session", async (importOriginal) => ({
	...(await importOriginal()),
	startUserView: vi.fn(),
}));

const workspace = {
	id: 1,
	workspaceSlug: "engineering",
	displayName: "Engineering",
};
const users = { content: [{ userId: 11, login: "never-signed-in", name: "Sam" }], totalPages: 1 };

async function submitReason() {
	const user = userEvent.setup();
	await user.click(
		await screen.findByRole("button", { name: "View as user: never-signed-in" }, ROUTE_RENDER_WAIT),
	);
	const dialog = screen.getByRole("dialog", { name: "View as Sam" });
	await user.type(
		within(dialog).getByRole("textbox", { name: "Reason for access" }),
		"Check Sam's profile",
	);
	await user.click(within(dialog).getByRole("button", { name: "View as user" }));
}

describe("view as user entry", () => {
	beforeEach(() => {
		vi.mocked(startUserView).mockClear();
		server.use(
			http.get("*/workspaces", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
			http.get("*/workspaces/:workspaceSlug/user-view/users", () => HttpResponse.json(users)),
		);
	});
	afterEach(clearUserView);

	it("opens the normal app for an accountless user after an audited read", async () => {
		let reasonHeader: string | null = null;
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", ({ request }) => {
				reasonHeader = request.headers.get("X-User-View-Reason");
				return HttpResponse.json(users.content[0]);
			}),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await submitReason();
		await waitFor(() => expect(startUserView).toHaveBeenCalledOnce());
		expect(reasonHeader).toBe("Check%20Sam's%20profile");
		expect(startUserView).toHaveBeenCalledWith({
			operatorAccountId: 42,
			workspaceSlug: "engineering",
			userId: 11,
			login: "never-signed-in",
			name: "Sam",
			hasAccount: false,
			reason: "Check Sam's profile",
		});
		expect(screen.queryByRole("tab", { name: "Conversations" })).toBeNull();
	});

	it("does not start when the audited read fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", () =>
				HttpResponse.json(
					{ status: 503, detail: "User view audit is unavailable" },
					{ status: 503 },
				),
			),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await submitReason();
		await screen.findByText("User view audit is unavailable");
		expect(startUserView).not.toHaveBeenCalled();
	});

	it("asks for a new sign-in when the audited read requires it", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId", () =>
				HttpResponse.json(
					{ status: 403, code: "step_up_required", maxAgeSeconds: 300 },
					{ status: 403 },
				),
			),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await submitReason();
		await screen.findByRole("dialog", { name: "Confirm access" });
		expect(startUserView).not.toHaveBeenCalled();
	});

	it("clears a view left by a different signed-in administrator", async () => {
		sessionStorage.setItem(
			"hephaestus.user-view",
			JSON.stringify({
				operatorAccountId: 99,
				workspaceSlug: "engineering",
				userId: 11,
				login: "never-signed-in",
				name: "Sam",
				hasAccount: false,
				reason: "Check profile",
			}),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await waitFor(() => expect(getUserViewSession()).toBeUndefined());
		expect(startUserView).not.toHaveBeenCalled();
	});
});
