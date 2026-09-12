import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

vi.setConfig({ testTimeout: 20_000 });

const users = [
	{ userId: 11, login: "never-signed-in", name: "Sam" },
	{ userId: 12, login: "other-user", name: "Alex" },
];
const usersPage = { content: users, totalPages: 1, number: 0 };
const summary = { groups: [], groupStandings: [], standings: [], practices: [] };
const workspace = {
	id: 1,
	workspaceSlug: "engineering",
	displayName: "Engineering",
	practicesEnabled: false,
	mentorEnabled: true,
};
const REASON = "Investigate missing feedback";
const BANNER = { name: "Viewing Sam in Engineering — read-only" };

interface PrivateRead {
	path: string;
	reason: string;
}

function privateReads() {
	const reads: PrivateRead[] = [];
	const record = (request: Request) => {
		reads.push({
			path: new URL(request.url).pathname,
			reason: request.headers.get("X-User-View-Reason") ?? "",
		});
	};
	server.use(
		http.get("*/workspaces/:workspaceSlug/user-view/users/:userId/practices", ({ request }) => {
			record(request);
			return HttpResponse.json(summary);
		}),
		http.get("*/workspaces/:workspaceSlug/user-view/users/:userId/conversations", ({ request }) => {
			record(request);
			return HttpResponse.json({ content: [], totalPages: 0, number: 0 });
		}),
	);
	return reads;
}

async function openUserView(login: string) {
	const user = userEvent.setup();
	await user.click(
		await screen.findByRole("button", { name: `View as user: ${login}` }, ROUTE_RENDER_WAIT),
	);
	await user.type(screen.getByRole("textbox", { name: "Reason for access" }), REASON);
	await user.click(screen.getByRole("button", { name: "View as user" }));
	return user;
}

describe("read-only user view", () => {
	beforeEach(() => {
		server.use(
			// The administrator is not a member: the chrome's list is empty and only the direct read answers.
			http.get("*/workspaces", () => HttpResponse.json([])),
			http.get("*/workspaces/:workspaceSlug", () => HttpResponse.json(workspace)),
			http.get("*/workspaces/:workspaceSlug/user-view/users", () => HttpResponse.json(usersPage)),
		);
	});

	it("names the viewed user and the workspace the administrator is not a member of", async () => {
		privateReads();
		renderRouteAt("/admin/workspaces/engineering/users");
		await openUserView("never-signed-in");
		await screen.findByRole("region", BANNER);
	});

	it("says when the viewed user has no account", async () => {
		privateReads();
		renderRouteAt("/admin/workspaces/engineering/users");
		await openUserView("never-signed-in");
		const banner = await screen.findByRole("region", BANNER);
		within(banner).getByText(/No linked Hephaestus account/);
	});

	it("says what the view shows that the user's own profile hides", async () => {
		privateReads();
		renderRouteAt("/admin/workspaces/engineering/users");
		await openUserView("never-signed-in");
		await screen.findByText(/Practices are disabled in this workspace/);
		expect(screen.queryByText(/Heph is disabled/)).toBeNull();
	});

	it("carries the encoded reason and the user id on every private read", async () => {
		const reads = privateReads();
		renderRouteAt("/admin/workspaces/engineering/users");
		const user = await openUserView("never-signed-in");
		await user.click(await screen.findByRole("tab", { name: "Conversations" }));
		await screen.findByText("No existing conversations");
		expect(reads).toStrictEqual([
			{
				path: "/workspaces/engineering/user-view/users/11/practices",
				reason: "Investigate%20missing%20feedback",
			},
			{
				path: "/workspaces/engineering/user-view/users/11/conversations",
				reason: "Investigate%20missing%20feedback",
			},
		]);
	});

	it("unmounts the view on exit", async () => {
		privateReads();
		renderRouteAt("/admin/workspaces/engineering/users");
		const user = await openUserView("never-signed-in");
		await screen.findByRole("region", BANNER);
		await user.click(screen.getByRole("button", { name: "Exit user view" }));
		await waitFor(() => expect(screen.queryByRole("region", BANNER)).toBeNull());
	});

	it("asks a new selection for a fresh reason", async () => {
		privateReads();
		renderRouteAt("/admin/workspaces/engineering/users");
		const user = await openUserView("never-signed-in");
		await screen.findByRole("region", BANNER);
		await user.click(screen.getByRole("button", { name: "Exit user view" }));
		await user.click(await screen.findByRole("button", { name: "View as user: other-user" }));
		expect(screen.getByRole("textbox", { name: "Reason for access" })).toHaveProperty("value", "");
	});

	it("discloses nothing when audit persistence fails", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId/practices", () =>
				HttpResponse.json(
					{ status: 503, detail: "User view audit is unavailable" },
					{ status: 503 },
				),
			),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await openUserView("never-signed-in");
		await screen.findByText(/User view audit is unavailable/);
		expect(screen.queryByRole("dialog", { name: "Confirm access" })).toBeNull();
	});

	it("opens the confirm-access ask when a private read needs a fresh sign-in", async () => {
		server.use(
			http.get("*/workspaces/:workspaceSlug/user-view/users/:userId/practices", () =>
				HttpResponse.json(
					{
						status: 403,
						title: "Confirm access",
						detail: "This action requires a recent sign-in.",
						code: "step_up_required",
						maxAgeSeconds: 300,
					},
					{ status: 403 },
				),
			),
		);
		renderRouteAt("/admin/workspaces/engineering/users");
		await openUserView("never-signed-in");
		const ask = await screen.findByRole("dialog", { name: "Confirm access" });
		within(ask).getByText(/last 5 minutes/);
		expect(screen.queryByText(/You don't have permission/)).toBeNull();
		await screen.findByText("Confirm your sign-in to keep viewing");
	});

	it("returns to the viewed user after a sign-in round trip: reopens the reason dialog", async () => {
		privateReads();
		renderRouteAt("/admin/workspaces/engineering/users?user=11&group=review-ready-work");
		const dialog = await screen.findByRole("dialog", { name: "View as Sam" }, ROUTE_RENDER_WAIT);
		expect(within(dialog).getByRole("textbox", { name: "Reason for access" })).toHaveProperty(
			"value",
			"",
		);
		expect(screen.queryByRole("region", BANNER)).toBeNull();
	});
});
