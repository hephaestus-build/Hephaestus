import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { UpdatePracticeReviewSettingsRequest } from "@/api/types.gen";
import { renderWithRouter } from "@/test/router-harness";
import { reviewSettings } from "./review/fixtures";
import { WorkspaceGeneratedPathsSettings } from "./WorkspaceGeneratedPathsSettings";

const repositories = {
	status: "ready" as const,
	options: [
		{ itemId: "101", name: "owner/repo" },
		{ itemId: "102", name: "group/nested/project" },
	],
};

function SettingsHarness() {
	const [settings, setSettings] = useState(
		reviewSettings({
			generatedPaths: { "101": ["generated/**"], "102": ["client/**"] },
		}),
	);
	const save = async (patch: UpdatePracticeReviewSettingsRequest, etag?: string) => {
		if (etag !== settings.etag) {
			throw new Error("Stale settings");
		}
		setSettings((current) => ({
			...current,
			etag: '"1"',
			generatedPaths: { ...current.generatedPaths, ...patch.generatedPaths },
		}));
	};
	return (
		<WorkspaceGeneratedPathsSettings
			settings={settings}
			repositories={repositories}
			isSaving={false}
			onSave={save}
		/>
	);
}

function SavingHarness() {
	const [saving, setSaving] = useState(false);
	return (
		<>
			<button type="button" onClick={() => setSaving(true)}>
				Save other settings
			</button>
			<WorkspaceGeneratedPathsSettings
				settings={reviewSettings()}
				repositories={repositories}
				isSaving={saving}
				onSave={async () => undefined}
			/>
		</>
	);
}

describe("WorkspaceGeneratedPathsSettings", () => {
	it("reads each repository's saved patterns by its item identity", async () => {
		await renderWithRouter(<SettingsHarness />, "/w/acme/admin/practices");
		expect(screen.getByRole("textbox", { name: "owner/repo" })).toHaveProperty(
			"value",
			"generated/**",
		);
		expect(screen.getByRole("textbox", { name: "group/nested/project" })).toHaveProperty(
			"value",
			"client/**",
		);
	});
	it("clears one repository without changing another repository", async () => {
		await renderWithRouter(<SettingsHarness />, "/w/acme/admin/practices");
		fireEvent.change(screen.getByRole("textbox", { name: "owner/repo" }), {
			target: { value: "" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save generated paths for owner/repo" }));
		await waitFor(() =>
			expect(
				screen.getByRole("button", { name: "Save generated paths for owner/repo" }),
			).toHaveProperty("disabled", true),
		);
		expect(screen.getByRole("textbox", { name: "owner/repo" })).toHaveProperty("value", "");
		expect(screen.getByRole("textbox", { name: "group/nested/project" })).toHaveProperty(
			"value",
			"client/**",
		);
	});

	it("keeps a repository's unsaved draft when a save in another repository changes the settings version", async () => {
		await renderWithRouter(<SettingsHarness />, "/w/acme/admin/practices");
		fireEvent.change(screen.getByRole("textbox", { name: "group/nested/project" }), {
			target: { value: "new-client/**" },
		});
		fireEvent.change(screen.getByRole("textbox", { name: "owner/repo" }), {
			target: { value: "new-generated/**" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save generated paths for owner/repo" }));
		await waitFor(() =>
			expect(
				screen.getByRole("button", { name: "Save generated paths for owner/repo" }),
			).toHaveProperty("disabled", true),
		);
		expect(screen.getByRole("textbox", { name: "group/nested/project" })).toHaveProperty(
			"value",
			"new-client/**",
		);
		fireEvent.click(
			screen.getByRole("button", { name: "Save generated paths for group/nested/project" }),
		);
		await waitFor(() =>
			expect(
				screen.getByRole("button", { name: "Save generated paths for group/nested/project" }),
			).toHaveProperty("disabled", true),
		);
		expect(screen.getByRole("textbox", { name: "group/nested/project" })).toHaveProperty(
			"value",
			"new-client/**",
		);
	});

	it("guards an unsaved repository draft while another repository is saving", async () => {
		const { router } = await renderWithRouter(<SavingHarness />, "/w/acme/admin/practices");
		// Nothing is saved for it, so its row starts closed.
		fireEvent.click(screen.getByRole("button", { name: "Generated paths for owner/repo" }));
		fireEvent.change(screen.getByRole("textbox", { name: "owner/repo" }), {
			target: { value: "generated/**" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Save other settings" }));
		act(() => {
			void router.navigate({ to: "/" });
		});
		await screen.findByRole("alertdialog", { name: "Discard unsaved changes?" });
		fireEvent.click(screen.getByRole("button", { name: "Keep editing" }));
		await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
		expect(screen.getByRole("textbox", { name: "owner/repo" })).toHaveProperty(
			"value",
			"generated/**",
		);
	});

	it("keeps a draft through collapsing and searching, and keeps its row listed", async () => {
		await renderWithRouter(<SettingsHarness />, "/w/acme/admin/practices");
		fireEvent.change(screen.getByRole("textbox", { name: "owner/repo" }), {
			target: { value: "draft/**" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Generated paths for owner/repo" }));
		await waitFor(() => expect(screen.queryByRole("textbox", { name: "owner/repo" })).toBeNull());

		fireEvent.change(screen.getByRole("searchbox", { name: "Search repositories" }), {
			target: { value: "nested" },
		});
		screen.getByText("1 of 2 loaded repositories match", { exact: false });
		// The clean row that does not match is hidden; the row with a draft stays and says so.
		expect(rowOf("group/nested/project").hidden).toBe(false);
		expect(rowOf("owner/repo").hidden).toBe(false);
		expect(rowOf("owner/repo").textContent).toContain("Unsaved changes");

		fireEvent.change(screen.getByRole("searchbox", { name: "Search repositories" }), {
			target: { value: "" },
		});
		fireEvent.click(screen.getByRole("button", { name: "Generated paths for owner/repo" }));
		expect(screen.getByRole("textbox", { name: "owner/repo" })).toHaveProperty("value", "draft/**");
	});

	it("hides a clean row that does not match, and says when nothing matches", async () => {
		await renderWithRouter(<SettingsHarness />, "/w/acme/admin/practices");
		fireEvent.change(screen.getByRole("searchbox", { name: "Search repositories" }), {
			target: { value: "102" },
		});
		expect(rowOf("owner/repo").hidden).toBe(true);
		expect(rowOf("group/nested/project").hidden).toBe(false);

		fireEvent.change(screen.getByRole("searchbox", { name: "Search repositories" }), {
			target: { value: "absent" },
		});
		screen.getByText("No loaded repository matches “absent”.", { exact: false });
		fireEvent.click(screen.getByRole("button", { name: "Clear search" }));
		expect(rowOf("owner/repo").hidden).toBe(false);
	});

	it("names a removed or unnamed repository by what is recorded, and saves no removed one", async () => {
		await renderWithRouter(
			<WorkspaceGeneratedPathsSettings
				settings={reviewSettings({ generatedPaths: { "103": ["old/**"] } })}
				repositories={{
					status: "ready",
					options: [
						{ itemId: "103", name: "owner/retired", removedAt: new Date("2026-09-01") },
						{ itemId: "104", name: "Repository", nameKnown: false },
						{ itemId: "105", name: "Repository", nameKnown: false },
					],
				}}
				isSaving={false}
				onSave={async () => undefined}
			/>,
			"/w/acme/admin/practices",
		);
		expect(rowOf("owner/retired").textContent).toContain("Removed");
		expect(screen.getByRole("textbox", { name: "owner/retired" })).toHaveProperty("readOnly", true);
		expect(
			screen.queryByRole("button", { name: "Save generated paths for owner/retired" }),
		).toBeNull();
		// Two items with no repository record keep apart by their item IDs.
		expect(rowOf("Repository 104").textContent).toContain("Name unavailable");
		screen.getByRole("button", { name: "Generated paths for Repository 105" });
	});

	it("loads more repositories and keeps the loaded ones when the next page fails", async () => {
		const loads = { count: 0 };
		function PagedHarness() {
			const [failed, setFailed] = useState(false);
			return (
				<>
					<button type="button" onClick={() => setFailed(true)}>
						Fail the next page
					</button>
					<WorkspaceGeneratedPathsSettings
						settings={reviewSettings()}
						repositories={{ ...repositories, total: 140, more: nextPage(failed, loads) }}
						isSaving={false}
						onSave={async () => undefined}
					/>
				</>
			);
		}
		await renderWithRouter(<PagedHarness />, "/w/acme/admin/practices");
		screen.getByText("2 of 140 repositories loaded", { exact: false });
		fireEvent.click(screen.getByRole("button", { name: "Show more repositories" }));
		expect(loads.count).toBe(1);

		fireEvent.click(screen.getByRole("button", { name: "Fail the next page" }));
		await screen.findByText("We could not load more repositories");
		expect(rowOf("owner/repo").hidden).toBe(false);
		expect(screen.queryByRole("button", { name: "Show more repositories" })).toBeNull();
	});
});

/** The listed row of a repository, found by its title whether or not it is hidden. */
function rowOf(name: string): HTMLElement {
	const title = screen.getAllByText(name, { selector: "span" })[0];
	const row = title?.closest<HTMLElement>('[role="listitem"]');
	if (row === null || row === undefined) {
		throw new Error(`No row for ${name}`);
	}
	return row;
}

/** One more page to load; after a failed load, the reason it failed. */
function nextPage(failed: boolean, loads: { count: number }) {
	return {
		hasMore: true,
		isLoadingMore: false,
		onLoadMore: () => {
			loads.count += 1;
		},
		loadMoreError: failed ? new Error("Service unavailable") : undefined,
	};
}
