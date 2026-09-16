// Behavioral tests for the active-sessions settings surface; asserts on observable DOM (roles,
// names, button state) rather than mock internals.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";

import { sessions } from "@/mocks/fixtures/auth";
import { noSessions, sessionsError } from "@/mocks/handlers";
import { server } from "@/mocks/server";

import { SessionsSection } from "./SessionsSection";

function renderWithClient(node: ReactNode) {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>);
}

/** A session row, located by its accessible name (the device's user-agent label). */
function rowByDevice(label: string): HTMLElement {
	return screen.getByRole("listitem", { name: label });
}

describe("SessionsSection", () => {
	it("lists every session from listSessions and badges the current device", async () => {
		renderWithClient(<SessionsSection />);

		await screen.findByText("Chrome 124 on macOS");
		// One row per session (the visual per-device rendering is covered by the Storybook story).
		expect(screen.getAllByRole("listitem")).toHaveLength(sessions.length);

		// The current session is badged and offers a disabled "Current" control, not a Revoke one.
		const currentRow = rowByDevice("Chrome 124 on macOS");
		within(currentRow).getByText("This device");
		const currentButton = within(currentRow).getByRole<HTMLButtonElement>("button", {
			name: "Current session",
		});
		expect(currentButton.disabled).toBe(true);
	});

	it("revokes a non-current session and refetches the list (the row disappears)", async () => {
		server.use(
			http.get("*/user/sessions", () => HttpResponse.json(sessions), { once: true }),
			http.get("*/user/sessions", () =>
				HttpResponse.json(sessions.filter((s) => s.jti !== "sess-other-002")),
			),
			http.delete("*/user/sessions/:jti", () => new HttpResponse(null, { status: 204 })),
		);

		renderWithClient(<SessionsSection />);
		await screen.findByText("Firefox 126 on Ubuntu");

		const targetRow = rowByDevice("Firefox 126 on Ubuntu");
		fireEvent.click(within(targetRow).getByRole("button", { name: "Revoke this session" }));

		await waitFor(() => {
			expect(screen.queryByText("Firefox 126 on Ubuntu")).toBeNull();
		});
		// Other non-current row survived; current device untouched.
		screen.getByText("Mobile Safari on iOS 18");
		screen.getByText("Chrome 124 on macOS");
	});

	it("only the clicked row shows a pending spinner (guards the per-row pending scope)", async () => {
		// Hold the DELETE open so the pending state is observable; we assert that the OTHER
		// non-current row's Revoke button is NOT disabled during the in-flight revoke.
		let releaseDelete: () => void = () => {};
		const deletePromise = new Promise<void>((resolve) => {
			releaseDelete = resolve;
		});
		server.use(
			http.delete("*/user/sessions/:jti", async () => {
				await deletePromise;
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderWithClient(<SessionsSection />);
		await screen.findByText("Firefox 126 on Ubuntu");

		const clickedRow = rowByDevice("Firefox 126 on Ubuntu");
		const otherRow = rowByDevice("Mobile Safari on iOS 18");
		const clickedBtn = within(clickedRow).getByRole<HTMLButtonElement>("button", {
			name: "Revoke this session",
		});
		const otherBtn = within(otherRow).getByRole<HTMLButtonElement>("button", {
			name: "Revoke this session",
		});

		fireEvent.click(clickedBtn);

		await waitFor(() => expect(clickedBtn.disabled).toBe(true));
		// Per-row scoping: the unrelated row must remain actionable.
		expect(otherBtn.disabled).toBe(false);

		releaseDelete();
	});

	it("renders an alert on a sessions load error", async () => {
		server.use(sessionsError);
		renderWithClient(<SessionsSection />);
		const alert = await screen.findByRole("alert");
		expect(alert.textContent).toContain("Failed to load sessions");
	});

	it("shows the empty state and hides 'Sign out everywhere else' when no sessions exist", async () => {
		server.use(noSessions);
		renderWithClient(<SessionsSection />);
		await screen.findByText("No active sessions found.");
		expect(screen.queryByRole("button", { name: /Sign out everywhere else/u })).toBeNull();
	});
});
