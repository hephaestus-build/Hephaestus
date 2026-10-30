import { act, fireEvent, screen } from "@testing-library/react";
import { useState } from "react";
import { assert, describe, expect, it, vi } from "vitest";
import type { AgentBinding, PracticeReviewSettings as Settings } from "@/api/types.gen";
import { renderWithRouter } from "@/test/router-harness";
import { PracticeReviewSettings } from "./PracticeReviewSettings";
import { reviewSettings } from "./review/fixtures";

const readyBinding: AgentBinding = {
	dataHandlingTier: "IN_HOUSE",
	purpose: "PRACTICE_REVIEW",
	enabled: true,
	ready: true,
	instanceModelId: 20,
	servedTiers: ["IN_HOUSE", "CLOUD"],
};
const settings = reviewSettings({ deliverToMerged: false, cooldownMinutes: 15 });
const repositories = {
	status: "ready" as const,
	options: [
		{ itemId: "101", name: "acme/widgets" },
		{ itemId: "102", name: "acme/gadgets" },
	],
};
const people = {
	status: "ready" as const,
	options: [
		{ value: "7", label: "Ada" },
		{ value: "8", label: "Grace" },
	],
};

async function renderSettings(
	props: Partial<React.ComponentProps<typeof PracticeReviewSettings>> = {},
) {
	return renderWithRouter(
		<PracticeReviewSettings
			workspaceSlug="acme"
			model={{
				status: "ready",
				binding: readyBinding,
			}}
			workspace={{
				enabled: true,
				startsFromActivity: true,
				startsOnRequest: true,
				isSaving: false,
				onUpdate: vi.fn(),
			}}
			policy={{ settings, isSaving: false, onUpdate: vi.fn(), onReset: vi.fn() }}
			scope={{
				preview: vi.fn(async () => ({
					current: settings.scopeSummary,
					proposed: settings.scopeSummary,
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
	return reviewSettings({
		reviewScope: {
			repositories: "SELECTED",
			people: "SELECTED",
			selectedRepositories: [{ repositoryItemId: "101", baseBranches: ["main"] }],
			accountIds: ["7"],
		},
		...overrides,
	});
}

function RollbackHarness() {
	const [current, setCurrent] = useState(settings);
	return (
		<PracticeReviewSettings
			workspaceSlug="acme"
			model={{ status: "ready", binding: readyBinding }}
			workspace={{
				enabled: true,
				startsFromActivity: true,
				startsOnRequest: true,
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
			scope={{
				preview: vi.fn(async () => ({
					current: settings.scopeSummary,
					proposed: settings.scopeSummary,
					widens: false,
				})),
				repositories,
				people,
			}}
		/>
	);
}

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
				Receive a newer review scope
			</button>
			<PracticeReviewSettings
				workspaceSlug="acme"
				model={{ status: "ready", binding: readyBinding }}
				workspace={{
					enabled: true,
					startsFromActivity: true,
					startsOnRequest: true,
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
				scope={{
					preview: vi.fn(async () => ({
						current: settings.scopeSummary,
						proposed: settings.scopeSummary,
						widens: false,
					})),
					repositories,
					people,
				}}
			/>
		</>
	);
}

describe("PracticeReviewSettings", () => {
	it("collects a cumulative draft before previewing or saving", async () => {
		const preview = vi.fn(async () => ({
			current: settings.scopeSummary,
			proposed: settings.scopeSummary,
			widens: true,
		}));
		const onUpdate = vi.fn();
		const persisted = selectedSettings();
		await renderSettings({
			policy: { settings: persisted, isSaving: false, onUpdate, onReset: vi.fn() },
			scope: { preview, repositories, people },
		});

		fireEvent.click(screen.getByRole("radio", { name: "All monitored repositories" }));
		fireEvent.click(screen.getByRole("radio", { name: "All eligible members" }));
		expect(preview).not.toHaveBeenCalled();
		expect(onUpdate).not.toHaveBeenCalled();

		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));
		await screen.findByRole("alertdialog");
		expect(preview).toHaveBeenCalledOnce();
		await act(async () => {
			fireEvent.click(screen.getByRole("button", { name: "Widen review scope" }));
		});
		expect(onUpdate).toHaveBeenCalledWith(
			{
				reviewScope: {
					repositories: "ALL",
					people: "ALL",
					selectedRepositories: [{ repositoryItemId: "101", baseBranches: ["main"] }],
					accountIds: ["7"],
				},
			},
			persisted.etag,
		);
	});

	it("previews a narrowing once and saves it without a confirmation", async () => {
		const preview = vi.fn(async () => ({
			current: settings.scopeSummary,
			proposed: { ...settings.scopeSummary, selectedRepositories: 0, selectedPeople: 0 },
			widens: false,
		}));
		const onUpdate = vi.fn();
		await renderSettings({
			policy: { settings, isSaving: false, onUpdate, onReset: vi.fn() },
			scope: { preview, repositories, people },
		});

		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("radio", { name: "Selected people" }));
		screen.getByText(/With no repositories selected, Hephaestus reviews no repository work/u);
		screen.getByText(/With no people selected, Hephaestus reviews nobody’s work/u);
		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));

		await act(async () => {
			await Promise.resolve();
		});
		expect(preview).toHaveBeenCalledOnce();
		expect(onUpdate).toHaveBeenCalledOnce();
		expect(screen.queryByRole("alertdialog")).toBeNull();
	});

	it("preserves an unsaved review scope draft when an unrelated etag changes", async () => {
		const persisted = selectedSettings();
		const policy = {
			settings: persisted,
			isSaving: false,
			onUpdate: vi.fn(),
			onReset: vi.fn(),
		};
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
							startsFromActivity: true,
							startsOnRequest: true,
							isSaving: false,
							onUpdate: vi.fn(),
						}}
						policy={{ ...policy, settings: current }}
						scope={{ preview: vi.fn(), repositories, people }}
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
		screen.getByText("You have unsaved review scope changes.");
	});

	it("does not save a dirty draft over a review scope changed by another admin", async () => {
		const onUpdate = vi.fn();
		function ConflictHarness() {
			const [current, setCurrent] = useState(settings);
			return (
				<>
					<button type="button" onClick={() => setCurrent({ ...selectedSettings(), etag: '"v2"' })}>
						Publish another review scope
					</button>
					<PracticeReviewSettings
						workspaceSlug="acme"
						model={{ status: "ready", binding: readyBinding }}
						workspace={{
							enabled: true,
							startsFromActivity: true,
							startsOnRequest: true,
							isSaving: false,
							onUpdate: vi.fn(),
						}}
						policy={{ settings: current, isSaving: false, onUpdate, onReset: vi.fn() }}
						scope={{ preview: vi.fn(), repositories, people }}
					/>
				</>
			);
		}

		await renderWithRouter(<ConflictHarness />, "/w/acme/admin/practices");
		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("button", { name: "Publish another review scope" }));

		await screen.findByText("Review scope changed elsewhere");
		fireEvent.click(screen.getByRole("radio", { name: "Selected people" }));
		expect(screen.getByRole<HTMLButtonElement>("button", { name: "Review changes" }).disabled).toBe(
			true,
		);
		expect(onUpdate).not.toHaveBeenCalled();
	});

	it("preserves the draft when an optimistic scope save rolls back", async () => {
		await renderWithRouter(<RollbackHarness />, "/w/acme/admin/practices");
		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("radio", { name: "Selected people" }));
		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));

		const alert = await screen.findByRole("alert");
		expect(alert.textContent).toMatch(/^We could not save the review scope\./u);
		expect(
			screen.getByRole("radio", { name: "Selected repositories" }).getAttribute("aria-checked"),
		).toBe("true");
		expect(
			screen.getByRole("radio", { name: "Selected people" }).getAttribute("aria-checked"),
		).toBe("true");
	});

	it("rebases a clean draft when a newer review scope arrives after a save", async () => {
		await renderWithRouter(<RebaseHarness />, "/w/acme/admin/practices");
		fireEvent.click(screen.getByRole("radio", { name: "Selected repositories" }));
		fireEvent.click(screen.getByRole("button", { name: "Review changes" }));
		await screen.findByText("Review scope is up to date.");

		fireEvent.click(screen.getByRole("button", { name: "Receive a newer review scope" }));
		expect(
			screen.getByRole("radio", { name: "Selected people" }).getAttribute("aria-checked"),
		).toBe("true");
		screen.getByText("Review scope is up to date.");
	});

	it("does not call unavailable persisted targets missing while option lists are unresolved", async () => {
		await renderSettings({
			policy: {
				settings: selectedSettings({
					reviewScope: {
						repositories: "SELECTED",
						people: "SELECTED",
						selectedRepositories: [{ repositoryItemId: "199", baseBranches: ["main"] }],
						accountIds: ["99"],
					},
				}),
				isSaving: false,
				onUpdate: vi.fn(),
				onReset: vi.fn(),
			},
			scope: {
				preview: vi.fn(),
				repositories: { status: "loading" },
				people: { status: "loading" },
			},
		});

		await screen.findByText("Repository 199");
		expect(screen.queryByText("Source unavailable")).toBeNull();
		expect(screen.queryByTitle(/unavailable/u)).toBeNull();
	});

	it("marks persisted targets unavailable only after a successful list excludes them", async () => {
		await renderSettings({
			policy: {
				settings: selectedSettings({
					reviewScope: {
						repositories: "SELECTED",
						people: "SELECTED",
						selectedRepositories: [{ repositoryItemId: "199", baseBranches: ["main"] }],
						accountIds: ["99"],
					},
				}),
				isSaving: false,
				onUpdate: vi.fn(),
				onReset: vi.fn(),
			},
		});

		await screen.findByText("Source unavailable");
		screen.getByTitle("Member 99 (unavailable)");
	});

	it("keeps the review scope draft available while an unrelated policy field saves", async () => {
		await renderSettings({
			policy: { settings, isSaving: true, onUpdate: vi.fn(), onReset: vi.fn() },
		});

		expect(
			screen.getByRole("radio", { name: "Selected repositories" }).getAttribute("aria-disabled"),
		).not.toBe("true");
	});
});
