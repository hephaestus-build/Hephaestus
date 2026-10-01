import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import type { UpdatePracticeReviewSettingsRequest } from "@/api/types.gen";
import { renderWithRouter } from "@/test/router-harness";
import { mockReviewSettings } from "./fixtures";
import { GeneratedPathsSettings } from "./GeneratedPathsSettings";

const repositories = {
	status: "ready" as const,
	options: [
		{ value: "owner/repo", label: "owner/repo" },
		{ value: "group/nested/project", label: "group/nested/project" },
	],
};

function SettingsHarness() {
	const [settings, setSettings] = useState(
		mockReviewSettings({
			generatedPaths: { "owner/repo": ["generated/**"], "group/nested/project": ["client/**"] },
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
		<GeneratedPathsSettings
			settings={settings}
			repositories={repositories}
			isSaving={false}
			onSave={save}
		/>
	);
}

describe("GeneratedPathsSettings", () => {
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
		function SavingHarness() {
			const [saving, setSaving] = useState(false);
			return (
				<>
					<button type="button" onClick={() => setSaving(true)}>
						Save other settings
					</button>
					<GeneratedPathsSettings
						settings={mockReviewSettings()}
						repositories={repositories}
						isSaving={saving}
						onSave={async () => undefined}
					/>
				</>
			);
		}
		const { router } = await renderWithRouter(<SavingHarness />, "/w/acme/admin/practices");
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
});
