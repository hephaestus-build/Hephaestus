import { act, screen, waitFor } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { getProvidersQueryKey } from "@/api/@tanstack/react-query.gen";
import { currentUser } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { ObserverStub } from "@/test/observers";
import { ROUTE_RENDER_WAIT, renderRouteAtWithRouter } from "@/test/router-harness";

// A case mounts the whole app chrome, whose route modules are imported lazily.
vi.setConfig({ testTimeout: 30_000 });

type PolicyRead = "failing" | "SELF_SERVICE" | "ADMIN_ONLY";

/** Answers the creation policy with whatever the test last chose. */
function serveCreationPolicy(initial: PolicyRead) {
	const read = { answer: initial };
	server.use(
		http.get("*/workspaces/providers", () =>
			read.answer === "failing"
				? HttpResponse.json({ status: 503 }, { status: 503 })
				: HttpResponse.json({ creationPolicy: read.answer }),
		),
	);
	return read;
}

const ASK_ADMIN = /Ask an instance admin to create a workspace or add you\./u;

describe("No workspace", () => {
	beforeEach(() => {
		vi.stubGlobal("IntersectionObserver", ObserverStub);
		// Not an instance admin, so the policy alone decides.
		server.use(
			http.get("*/user", () =>
				HttpResponse.json({ ...currentUser, appRole: "USER", roles: ["ROLE_USER"] }),
			),
			http.get("*/workspaces", () => HttpResponse.json([])),
		);
	});

	it("offers no step while the creation policy cannot be read, and Create workspace once it allows one", async () => {
		const policy = serveCreationPolicy("failing");
		const { queryClient } = renderRouteAtWithRouter("/");

		await screen.findByRole("heading", { level: 1, name: "No workspace" }, ROUTE_RENDER_WAIT);
		await screen.findByRole("heading", { level: 2, name: "No workspace" }, ROUTE_RENDER_WAIT);
		// Unread is neither allowed nor forbidden: no link, and no claim about who may create one.
		await waitFor(() =>
			expect(screen.getAllByText("You are not a member of any workspace yet.")).toHaveLength(2),
		);
		expect(screen.queryByRole("link", { name: "Create workspace" })).toBeNull();
		expect(screen.queryByText(ASK_ADMIN)).toBeNull();

		policy.answer = "SELF_SERVICE";
		await act(async () => {
			await queryClient.invalidateQueries({ queryKey: getProvidersQueryKey() });
		});
		await waitFor(() =>
			expect(screen.getAllByRole("link", { name: "Create workspace" })).toHaveLength(2),
		);
	});

	it("names the instance admin where only an instance admin may create a workspace", async () => {
		serveCreationPolicy("ADMIN_ONLY");
		renderRouteAtWithRouter("/");

		await waitFor(() => expect(screen.getAllByText(ASK_ADMIN)).toHaveLength(2), ROUTE_RENDER_WAIT);
		expect(screen.queryByRole("link", { name: "Create workspace" })).toBeNull();
	});
});
