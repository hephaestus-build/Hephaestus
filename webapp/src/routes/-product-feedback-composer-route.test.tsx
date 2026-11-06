import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, expect, it, vi } from "vitest";

import { ownMembership, workspaceListItem } from "@/mocks/fixtures/workspaces";
import { server } from "@/mocks/server";
import { ROUTE_RENDER_WAIT, renderRouteAt } from "@/test/router-harness";

// The composer's code does not load, as when a deploy replaced the file.
const { importFailure } = vi.hoisted(() => ({
	importFailure: new Error("Feedback composer chunk unavailable"),
}));
vi.mock("./-ProductFeedbackComposer", () => {
	throw importFailure;
});

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

beforeEach(() => {
	server.use(
		http.get("*/workspaces", () =>
			HttpResponse.json([workspaceListItem("acme", { displayName: "Acme" })]),
		),
		http.get("*/workspaces/:workspaceSlug/members/me", () =>
			HttpResponse.json(ownMembership("MEMBER")),
		),
	);
});

it("says the feedback form could not open and offers a page load", async () => {
	const user = userEvent.setup();
	renderRouteAt("/w/acme/activity");

	await user.click(
		await screen.findByRole(
			"button",
			{ name: "Send feedback about Hephaestus" },
			ROUTE_RENDER_WAIT,
		),
	);

	await screen.findByText("We could not open the feedback form.", undefined, ROUTE_RENDER_WAIT);
	screen.getByRole("button", { name: "Reload page" });
	screen.getByRole("button", { name: "Send feedback about Hephaestus" });
	expect(screen.queryByRole("dialog")).toBeNull();
});
