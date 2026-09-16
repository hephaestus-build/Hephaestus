import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import { toast } from "sonner";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { listWorkspacesQueryKey } from "@/api/@tanstack/react-query.gen";
import { server } from "@/mocks/server";
import { renderWithRouter } from "@/test/router-harness";

import { AdminDangerZoneSettings } from "./AdminDangerZoneSettings";

vi.mock("sonner", () => ({
	toast: { success: vi.fn(), error: vi.fn() },
}));

const WAIT = { timeout: 8000 };

function membershipHandler(role: "OWNER" | "ADMIN") {
	return http.get("*/workspaces/demo/members/me", () =>
		HttpResponse.json({ role, userLogin: "ada" }),
	);
}

async function renderContainer() {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	const rendered = await renderWithRouter(
		<QueryClientProvider client={queryClient}>
			<AdminDangerZoneSettings workspaceSlug="demo" />
		</QueryClientProvider>,
		"/w/demo/admin/settings",
	);
	return { ...rendered, queryClient };
}

function deleteButton() {
	return screen.getByRole("button", { name: /^delete workspace$/iu });
}

/** Waits out the permission check that gates the owner-only control. */
function findDeleteButton() {
	return screen.findByRole("button", { name: /^delete workspace$/iu }, WAIT);
}

async function openDialog() {
	await findDeleteButton();
	fireEvent.click(deleteButton());
	return screen.findByLabelText(/to confirm/iu);
}

function confirmButton() {
	return within(screen.getByRole("alertdialog")).getByRole("button", {
		name: /^delete workspace$/iu,
	});
}

beforeEach(() => {
	vi.clearAllMocks();
});

describe("AdminDangerZoneSettings", () => {
	it("deletes the workspace and leaves its route", async () => {
		server.use(
			membershipHandler("OWNER"),
			http.delete("*/workspaces/demo", () => new HttpResponse(null, { status: 204 })),
		);
		const { router, queryClient } = await renderContainer();
		queryClient.setQueryData(listWorkspacesQueryKey(), [
			{ workspaceSlug: "demo", displayName: "Demo" },
			{ workspaceSlug: "other", displayName: "Other" },
		]);

		fireEvent.change(await openDialog(), { target: { value: "demo" } });
		fireEvent.click(confirmButton());

		await waitFor(() => expect(router.state.location.pathname).toBe("/"), WAIT);
		expect(queryClient.getQueryData(listWorkspacesQueryKey())).toStrictEqual([
			{ workspaceSlug: "other", displayName: "Other" },
		]);
		expect(toast.success).toHaveBeenCalledWith("Workspace deleted");
	});

	it("leaves the route when the workspace list has never loaded", async () => {
		server.use(
			membershipHandler("OWNER"),
			http.delete("*/workspaces/demo", () => new HttpResponse(null, { status: 204 })),
		);
		const { router, queryClient } = await renderContainer();

		fireEvent.change(await openDialog(), { target: { value: "demo" } });
		fireEvent.click(confirmButton());

		await waitFor(() => expect(router.state.location.pathname).toBe("/"), WAIT);
		expect(queryClient.getQueryData(listWorkspacesQueryKey())).toBeUndefined();
	});

	it("keeps the dialog open and surfaces the server reason on failure", async () => {
		server.use(
			membershipHandler("OWNER"),
			http.delete("*/workspaces/demo", () =>
				HttpResponse.json(
					{
						title: "Workspace lifecycle violation",
						detail: "Workspace has an active sync.",
					},
					{ status: 409 },
				),
			),
		);
		await renderContainer();

		fireEvent.change(await openDialog(), { target: { value: "demo" } });
		fireEvent.click(confirmButton());

		await waitFor(
			() =>
				expect(toast.error).toHaveBeenCalledWith("Failed to delete workspace", {
					description: "Workspace has an active sync.",
				}),
			WAIT,
		);
		screen.getByRole("alertdialog");
	});

	it("does not guess the role while permissions load", async () => {
		let resolveRole = () => {};
		const roleReady = new Promise<void>((resolve) => {
			resolveRole = resolve;
		});
		server.use(
			http.get("*/workspaces/demo/members/me", async () => {
				await roleReady;
				return HttpResponse.json({ role: "OWNER", userLogin: "ada" });
			}),
		);
		await renderContainer();

		expect(screen.queryByText(/only the workspace owner/iu)).toBeNull();
		screen.getByText(/checking your permissions/iu);

		resolveRole();
		await findDeleteButton();
	});

	it("retries a failed permission check", async () => {
		server.use(
			http.get(
				"*/workspaces/demo/members/me",
				() => HttpResponse.json({ status: 403, detail: "Forbidden" }, { status: 403 }),
				{ once: true },
			),
			http.get("*/workspaces/demo/members/me", () =>
				HttpResponse.json({ role: "OWNER", userLogin: "ada" }),
			),
		);
		await renderContainer();

		const retry = await screen.findByRole("button", { name: /^retry$/iu }, WAIT);
		fireEvent.click(retry);

		await findDeleteButton();
		expect(screen.queryByRole("button", { name: /^retry$/iu })).toBeNull();
	});

	it("hides deletion from a non-owner admin", async () => {
		server.use(membershipHandler("ADMIN"));
		await renderContainer();

		await screen.findByText(/only the workspace owner/iu, undefined, WAIT);
		expect(screen.queryByRole("button", { name: /^delete workspace$/iu })).toBeNull();
	});
});
