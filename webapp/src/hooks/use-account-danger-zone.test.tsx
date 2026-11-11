// Behavioral tests for the GDPR danger-zone settings (ADR 0017). HTTP is intercepted at the MSW
// boundary; we assert on observable DOM and that the destructive delete is gated behind the typed
// confirmation phrase.

import { Blob as FetchBlob } from "node:buffer";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse, http } from "msw";
import type { ReactNode } from "react";
import { afterEach, assert, describe, expect, it, vi } from "vitest";

import { problemType } from "@/lib/problem-detail";
import { currentUser, exportReady } from "@/mocks/fixtures/auth";
import { server } from "@/mocks/server";
import { AuthProvider } from "@/runtime/auth/AuthContext";

import { DangerZoneSection } from "@/components/settings/DangerZoneSection";
import { Toaster } from "@/components/ui/sonner";

import { useAccountDangerZone } from "./use-account-danger-zone";

/** The settings page's wiring of the Danger zone: the hook reads and writes, the section shows. */
function DangerZone({ onAccountDeleted }: { onAccountDeleted: () => void }) {
	return <DangerZoneSection {...useAccountDangerZone({ onAccountDeleted })} />;
}

function renderWithClient(node: ReactNode) {
	const queryClient = new QueryClient({
		defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
	});
	return render(
		<QueryClientProvider client={queryClient}>
			<AuthProvider>{node}</AuthProvider>
		</QueryClientProvider>,
	);
}

function captureExportDownloads() {
	const createObjectURL = vi.fn<typeof URL.createObjectURL>(() => "blob:account-export");
	vi.stubGlobal(
		"URL",
		class extends URL {
			static override createObjectURL = createObjectURL;
			static override revokeObjectURL = vi.fn<typeof URL.revokeObjectURL>();
		},
	);
	const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(vi.fn());
	return { createObjectURL, click };
}

describe("useAccountDangerZone — data export", () => {
	afterEach(() => {
		vi.useRealTimers();
		vi.restoreAllMocks();
		vi.unstubAllGlobals();
	});

	it.each([403, 500])("reports a refused download (%i) without saving a file", async (status) => {
		const { createObjectURL } = captureExportDownloads();
		server.use(
			http.get("*/user/exports/:id", () => HttpResponse.json(exportReady)),
			http.get("*/user/exports/:id/download", () => new HttpResponse(null, { status })),
		);
		renderWithClient(
			<>
				<DangerZone onAccountDeleted={vi.fn()} />
				<Toaster />
			</>,
		);
		fireEvent.click(screen.getByRole("button", { name: /Request export/u }));
		const download = await screen.findByRole<HTMLButtonElement>("button", { name: /Download/u });
		fireEvent.click(download);
		await screen.findByText("We could not download your export. Try again.");
		expect(createObjectURL).not.toHaveBeenCalled();
		await waitFor(() => expect(download.disabled).toBe(false));
	});

	it("can download the export after a failed attempt", async () => {
		vi.useFakeTimers({ shouldAdvanceTime: true });
		// Node's fetch returns its own Blob; a browser uses the same Blob for fetch and the page.
		vi.stubGlobal("Blob", FetchBlob);
		const { createObjectURL, click } = captureExportDownloads();
		server.use(
			http.get("*/user/exports/:id", () => HttpResponse.json(exportReady)),
			http.get("*/user/exports/:id/download", () => new HttpResponse(null, { status: 500 })),
		);
		renderWithClient(
			<>
				<DangerZone onAccountDeleted={vi.fn()} />
				<Toaster />
			</>,
		);
		fireEvent.click(screen.getByRole("button", { name: /Request export/u }));
		const download = await screen.findByRole<HTMLButtonElement>("button", { name: /Download/u });
		fireEvent.click(download);
		await screen.findByText("We could not download your export. Try again.");
		await waitFor(() => expect(download.disabled).toBe(false));
		const payload = { account: { id: currentUser.id } };
		server.use(http.get("*/user/exports/:id/download", () => HttpResponse.json(payload)));
		fireEvent.click(download);
		await waitFor(() => expect(createObjectURL).toHaveBeenCalledOnce());
		expect(click).toHaveBeenCalledOnce();
		const savedCall = createObjectURL.mock.calls.at(0);
		assert(savedCall);
		const [file] = savedCall;
		assert(file instanceof FetchBlob);
		await expect(file.text()).resolves.toBe(JSON.stringify(payload));
		await waitFor(() => expect(download.disabled).toBe(false));
	});

	it("requests an export, polls PENDING -> READY, then surfaces a Download affordance", async () => {
		// `shouldAdvanceTime` lets MSW's promise-based responses keep resolving while we still
		// control the 2s `refetchInterval` poll deterministically (no real wall-clock wait).
		vi.useFakeTimers({ shouldAdvanceTime: true });
		renderWithClient(<DangerZone onAccountDeleted={vi.fn()} />);

		fireEvent.click(screen.getByRole("button", { name: /Request export/u }));

		// First status poll returns PENDING; the in-progress copy proves we're polling.
		await waitFor(() => screen.getByText(/Preparing your export/iu));

		// Drive the 2s poll interval forward; the next poll lands READY.
		await act(async () => vi.advanceTimersByTimeAsync(2000));

		await waitFor(() => screen.getByRole("button", { name: /Download/u }));
		screen.getByText(/ready to download/iu);
	});
});

/** The trigger button (collapsed) is labelled "Delete account"; opening reveals the confirm input. */
function openDeleteDialog() {
	fireEvent.click(screen.getByRole("button", { name: "Delete account" }));
}

describe("useAccountDangerZone — account deletion", () => {
	it("keeps deletion disabled until the exact confirmation phrase is typed", async () => {
		renderWithClient(<DangerZone onAccountDeleted={vi.fn()} />);
		openDeleteDialog();

		const dialog = await screen.findByRole("alertdialog");
		const confirmButton = within(dialog).getByRole<HTMLButtonElement>("button", {
			name: "Delete account",
		});
		expect(confirmButton.disabled).toBe(true);

		const input = within(dialog).getByLabelText("Confirmation phrase");
		fireEvent.change(input, { target: { value: "delete my acc" } });
		expect(confirmButton.disabled).toBe(true);

		// Enables only once the phrase matches AND the session (account id) has resolved.
		fireEvent.change(input, { target: { value: "delete my account" } });
		await waitFor(() => expect(confirmButton.disabled).toBe(false));
	});

	it("sends the account id in X-Confirm-Delete and fires onAccountDeleted once confirmed", async () => {
		const onAccountDeleted = vi.fn();
		let sentHeader: string | null = null;
		server.use(
			http.delete("*/user", ({ request }) => {
				// Captured rather than rejected with a 400, so the assertion names the value actually sent.
				sentHeader = request.headers.get("X-Confirm-Delete");
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderWithClient(<DangerZone onAccountDeleted={onAccountDeleted} />);
		openDeleteDialog();

		const dialog = await screen.findByRole("alertdialog");
		const confirmButton = within(dialog).getByRole<HTMLButtonElement>("button", {
			name: "Delete account",
		});
		// Padded and mixed-case on purpose: the match trims and ignores case.
		fireEvent.change(within(dialog).getByLabelText("Confirmation phrase"), {
			target: { value: "  Delete My Account  " },
		});
		// Button only enables once the session (account id) has resolved.
		await waitFor(() => expect(confirmButton.disabled).toBe(false));
		fireEvent.click(confirmButton);

		await waitFor(() => expect(onAccountDeleted).toHaveBeenCalledOnce());
		expect(sentHeader).toBe(String(currentUser.id));
	});

	it("asks for a recent sign-in instead of reporting a failure, and replaces the confirmation", async () => {
		const onAccountDeleted = vi.fn();
		server.use(
			http.delete("*/user", () =>
				HttpResponse.json(
					{
						status: 403,
						title: "Confirm access",
						detail: "This action requires a recent sign-in.",
						type: problemType("step-up-required"),
						maxAgeSeconds: 300,
					},
					{ status: 403 },
				),
			),
		);

		renderWithClient(<DangerZone onAccountDeleted={onAccountDeleted} />);
		openDeleteDialog();
		const dialog = await screen.findByRole("alertdialog");
		const confirmButton = within(dialog).getByRole<HTMLButtonElement>("button", {
			name: "Delete account",
		});
		fireEvent.change(within(dialog).getByLabelText("Confirmation phrase"), {
			target: { value: "delete my account" },
		});
		await waitFor(() => expect(confirmButton.disabled).toBe(false));
		fireEvent.click(confirmButton);

		const ask = await screen.findByRole("dialog", { name: "Confirm access" });
		expect(ask.textContent).toContain("sign-in from the last 5 minutes");
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(screen.queryByText(/could not delete your account/u)).toBeNull();
		expect(onAccountDeleted).not.toHaveBeenCalled();
	});

	it("does not call the deletion endpoint when the phrase is wrong (button stays disabled)", async () => {
		const onAccountDeleted = vi.fn();
		let deleteHit = false;
		server.use(
			http.delete("*/user", () => {
				deleteHit = true;
				return new HttpResponse(null, { status: 204 });
			}),
		);

		renderWithClient(<DangerZone onAccountDeleted={onAccountDeleted} />);
		openDeleteDialog();

		const dialog = await screen.findByRole("alertdialog");
		const input = within(dialog).getByLabelText("Confirmation phrase");
		const confirmButton = within(dialog).getByRole<HTMLButtonElement>("button", {
			name: "Delete account",
		});
		// The phrase, not an unfinished session lookup, must be what blocks deletion.
		fireEvent.change(input, { target: { value: "delete my account" } });
		await waitFor(() => expect(confirmButton.disabled).toBe(false));
		fireEvent.change(input, { target: { value: "nope" } });
		expect(confirmButton.disabled).toBe(true);
		fireEvent.click(confirmButton);

		expect(deleteHit).toBe(false);
		expect(onAccountDeleted).not.toHaveBeenCalled();
	});
});
