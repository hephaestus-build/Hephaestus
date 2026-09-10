import { act, fireEvent, screen } from "@testing-library/react";
import { useState } from "react";
import { assert, describe, expect, it, vi } from "vitest";
import type { AgentBinding, PracticeReviewSettings as Settings } from "@/api/types.gen";
import { renderWithRouter } from "@/test/router-harness";
import { PracticeReviewSettings } from "./PracticeReviewSettings";
import { mockReviewSettings } from "./story-mock-data";

const readyBinding: AgentBinding = {
	processingLocation: "UNCLASSIFIED",
	purpose: "PRACTICE_REVIEW",
	enabled: true,
	ready: true,
	instanceModelId: 20,
};
const settings = mockReviewSettings({ deliverToMerged: false, cooldownMinutes: 15 });
const repositories = {
	status: "ready" as const,
	options: [
		{ value: "acme/widgets", label: "acme/widgets" },
		{ value: "acme/gadgets", label: "acme/gadgets" },
	],
};
const people = {
	status: "ready" as const,
	options: [
		{ value: 7, label: "Ada" },
		{ value: 8, label: "Grace" },
	],
};

function renderSettings(props: Partial<React.ComponentProps<typeof PracticeReviewSettings>> = {}) {
	return renderWithRouter(
		<PracticeReviewSettings
			workspaceSlug="acme"
			model={{
				status: "ready",
				binding: readyBinding,
			}}
			workspace={{
				enabled: true,
				autoTriggerEnabled: true,
				manualTriggerEnabled: true,
				isSaving: false,
				onUpdate: vi.fn(),
			}}
			policy={{ settings, isSaving: false, onUpdate: vi.fn(), onReset: vi.fn() }}
			coverage={{
				preview: vi.fn(async () => ({
					current: settings.coverageSummary,
					proposed: settings.coverageSummary,
					widens: true,
				})),
				repositories,
				people,
			}}
			{...props}
		/>,
		"/w/acme/admin/practices",
	);
}

function selectedSettings(overrides: Partial<Settings> = {}): Settings {
	return mockReviewSettings({
		reviewScope: {
			repositoryMode: "SELECTED",
			personMode: "SELECTED",
			repositories: [{ nameWithOwner: "acme/widgets", baseBranches: ["main"] }],
			personUserIds: [7],
		},
		...overrides,
	});
}

describe("PracticeReviewSettings", () => {
	it("collects a cumulative draft before previewing or saving", async () => {
		const preview = vi.fn(async () => ({
			current: settings.coverageSummary,
			proposed: settings.coverageSummary,
			widens: true,
		}));
		const onUpdate = vi.fn();
		const persisted = selectedSettings();
		await renderSettings({
			policy: { settings: persisted, isSaving: false, onUpdate, onReset: vi.fn() },
			coverage: { preview, repositories, people },
		});

		fireEvent.click(screen.getByRole("radio", { name: "All monitored repositories" }));
		fireEvent.click(screen.getByRole("radio", { name: "All eligible linked members" }));
		expect(preview).not.toHaveBeenCalled();
		expect(onUpdate).not.toHaveBeenCalled();

		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));
		await screen.findByRole("alertdialog");
		expect(preview).toHaveBeenCalledOnce();
		await act(async () => {
			fireEvent.click(screen.getByRole("button", { name: "Apply wider coverage" }));
		});
		expect(onUpdate).toHaveBeenCalledWith(
			{
				reviewScope: {
					repositoryMode: "ALL_MONITORED",
					personMode: "ALL_ELIGIBLE",
					repositories: [{ nameWithOwner: "acme/widgets", baseBranches: ["main"] }],
					personUserIds: [7],
				},
			},
			persisted.etag,
		);
	});

	it("previews a narrowing once and saves it without a confirmation", async () => {
		const preview = vi.fn(async () => ({
			current: settings.coverageSummary,
			proposed: { ...settings.coverageSummary, coveredRepositories: 0, coveredPeople: 0 },
			widens: false,
		}));
		const onUpdate = vi.fn();
		await renderSettings({
			policy: { settings, isSaving: false, onUpdate, onReset: vi.fn() },
			coverage: { preview, repositories, people },
		});

		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("radio", { name: "Selected people" }));
		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));

		await act(() => Promise.resolve());
		expect(preview).toHaveBeenCalledOnce();
		expect(onUpdate).toHaveBeenCalledOnce();
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	it("preserves an unsaved coverage draft when an unrelated etag changes", async () => {
		const persisted = selectedSettings();
		const policy = { settings: persisted, isSaving: false, onUpdate: vi.fn(), onReset: vi.fn() };
		function EtagHarness() {
			const [current, setCurrent] = useState(persisted);
			return (
				<>
					<button type="button" onClick={() => setCurrent({ ...current, etag: '"1"' })}>
						Apply unrelated update
					</button>
					<PracticeReviewSettings
						workspaceSlug="acme"
						model={{
							status: "ready",
							binding: readyBinding,
						}}
						workspace={{
							enabled: true,
							autoTriggerEnabled: true,
							manualTriggerEnabled: true,
							isSaving: false,
							onUpdate: vi.fn(),
						}}
						policy={{ ...policy, settings: current }}
						coverage={{ preview: vi.fn(), repositories, people }}
					/>
				</>
			);
		}
		await renderWithRouter(<EtagHarness />, "/w/acme/admin/practices");
		fireEvent.click(screen.getByRole("radio", { name: "All monitored repositories" }));
		fireEvent.click(screen.getByRole("button", { name: "Apply unrelated update" }));

		expect(
			screen
				.getByRole("radio", { name: "All monitored repositories" })
				.getAttribute("aria-checked"),
		).toBe("true");
		screen.getByText("You have unsaved coverage changes.");
	});

	it("does not save a dirty draft over coverage changed by another admin", async () => {
		const onUpdate = vi.fn();
		function ConflictHarness() {
			const [current, setCurrent] = useState(settings);
			return (
				<>
					<button type="button" onClick={() => setCurrent({ ...selectedSettings(), etag: '"v2"' })}>
						Publish other coverage
					</button>
					<PracticeReviewSettings
						workspaceSlug="acme"
						model={{ status: "ready", binding: readyBinding }}
						workspace={{
							enabled: true,
							autoTriggerEnabled: true,
							manualTriggerEnabled: true,
							isSaving: false,
							onUpdate: vi.fn(),
						}}
						policy={{ settings: current, isSaving: false, onUpdate, onReset: vi.fn() }}
						coverage={{ preview: vi.fn(), repositories, people }}
					/>
				</>
			);
		}

		await renderWithRouter(<ConflictHarness />, "/w/acme/admin/practices");
		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("button", { name: "Publish other coverage" }));

		await screen.findByText("Coverage changed elsewhere");
		fireEvent.click(screen.getByRole("radio", { name: "Selected people" }));
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Review changes" }).disabled).toBe(
			true,
		);
		expect(onUpdate).not.toHaveBeenCalled();
	});

	it("preserves the draft when an optimistic scope save rolls back", async () => {
		function RollbackHarness() {
			const [current, setCurrent] = useState(settings);
			return (
				<PracticeReviewSettings
					workspaceSlug="acme"
					model={{ status: "ready", binding: readyBinding }}
					workspace={{
						enabled: true,
						autoTriggerEnabled: true,
						manualTriggerEnabled: true,
						isSaving: false,
						onUpdate: vi.fn(),
					}}
					policy={{
						settings: current,
						isSaving: false,
						onReset: vi.fn(),
						onUpdate: async (request) => {
							assert(request.reviewScope);
							setCurrent({ ...settings, reviewScope: request.reviewScope });
							await Promise.resolve();
							setCurrent(settings);
							throw new Error("conflict");
						},
					}}
					coverage={{
						preview: vi.fn(async () => ({
							current: settings.coverageSummary,
							proposed: settings.coverageSummary,
							widens: false,
						})),
						repositories,
						people,
					}}
				/>
			);
		}
		await renderWithRouter(<RollbackHarness />, "/w/acme/admin/practices");
		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("radio", { name: "Selected people" }));
		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));

		await screen.findByText("Couldn't save the coverage. Your draft is unchanged; try again.");
		expect(
			screen.getByRole("radio", { name: "Selected repositories" }).getAttribute("aria-checked"),
		).toBe("true");
		expect(
			screen.getByRole("radio", { name: "Selected people" }).getAttribute("aria-checked"),
		).toBe("true");
	});

	it("rebases a clean draft when newer coverage arrives after a save", async () => {
		function RebaseHarness() {
			const [current, setCurrent] = useState(settings);
			return (
				<>
					<button
						type="button"
						onClick={() =>
							setCurrent({
								...selectedSettings(),
								etag: '"newer"',
							})
						}
					>
						Receive newer coverage
					</button>
					<PracticeReviewSettings
						workspaceSlug="acme"
						model={{ status: "ready", binding: readyBinding }}
						workspace={{
							enabled: true,
							autoTriggerEnabled: true,
							manualTriggerEnabled: true,
							isSaving: false,
							onUpdate: vi.fn(),
						}}
						policy={{
							settings: current,
							isSaving: false,
							onReset: vi.fn(),
							onUpdate: async (request) => {
								assert(request.reviewScope);
								setCurrent({ ...current, reviewScope: request.reviewScope, etag: '"saved"' });
							},
						}}
						coverage={{
							preview: vi.fn(async () => ({
								current: settings.coverageSummary,
								proposed: settings.coverageSummary,
								widens: false,
							})),
							repositories,
							people,
						}}
					/>
				</>
			);
		}

		await renderWithRouter(<RebaseHarness />, "/w/acme/admin/practices");
		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));
		await screen.findByText("Coverage is up to date.");

		fireEvent.click(screen.getByRole("button", { name: "Receive newer coverage" }));
		expect(
			screen.getByRole("radio", { name: "Selected people" }).getAttribute("aria-checked"),
		).toBe("true");
		screen.getByText("Coverage is up to date.");
	});

	it("does not call unavailable persisted targets missing while option lists are unresolved", async () => {
		await renderSettings({
			policy: {
				settings: selectedSettings({
					reviewScope: {
						repositoryMode: "SELECTED",
						personMode: "SELECTED",
						repositories: [{ nameWithOwner: "acme/archived", baseBranches: ["main"] }],
						personUserIds: [99],
					},
				}),
				isSaving: false,
				onUpdate: vi.fn(),
				onReset: vi.fn(),
			},
			coverage: {
				preview: vi.fn(),
				repositories: { status: "loading" },
				people: { status: "loading" },
			},
		});

		await screen.findByText("acme/archived");
		expect(screen.queryByText("Not monitored")).toBeNull();
		expect(screen.queryByTitle(/unavailable/)).toBeNull();
	});

	it("marks persisted targets unavailable only after a successful list excludes them", async () => {
		await renderSettings({
			policy: {
				settings: selectedSettings({
					reviewScope: {
						repositoryMode: "SELECTED",
						personMode: "SELECTED",
						repositories: [{ nameWithOwner: "acme/archived", baseBranches: ["main"] }],
						personUserIds: [99],
					},
				}),
				isSaving: false,
				onUpdate: vi.fn(),
				onReset: vi.fn(),
			},
		});

		await screen.findByText("Not monitored");
		screen.getByTitle("Member 99 (unavailable)");
	});

	it("keeps the coverage draft available while an unrelated policy field saves", async () => {
		await renderSettings({
			policy: { settings, isSaving: true, onUpdate: vi.fn(), onReset: vi.fn() },
		});

		expect(
			screen.getByRole("radio", { name: "Selected repositories" }).getAttribute("aria-disabled"),
		).not.toBe("true");
	});

	it("keeps the send switch accessible name stable across delivery states", async () => {
		const view = await renderSettings();
		const active = await screen.findByRole("switch", { name: "Send feedback" });
		expect(active.getAttribute("aria-checked")).toBe("true");
		expect(screen.queryByRole("switch", { name: /Active/ })).toBeNull();
		view.unmount();
		await renderSettings({
			policy: {
				settings: { ...settings, deliveryStatus: "PAUSED" },
				isSaving: false,
				onUpdate: vi.fn(),
				onReset: vi.fn(),
			},
		});

		expect(screen.getByRole("switch", { name: "Send feedback" }).getAttribute("aria-checked")).toBe(
			"false",
		);
		expect(screen.queryByRole("switch", { name: /Paused/ })).toBeNull();
	});
});
