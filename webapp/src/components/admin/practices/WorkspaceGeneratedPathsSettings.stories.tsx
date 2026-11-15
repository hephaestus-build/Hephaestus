import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Button } from "@/components/ui/button";
import { expectListPlaceholders } from "@/stories/loading-more";
import { Stateful } from "@/stories/stateful";
import { pending } from "@/test/async";
import { reviewSettings } from "./review/fixtures";
import { WorkspaceGeneratedPathsSettings } from "./WorkspaceGeneratedPathsSettings";

const meta = {
	component: WorkspaceGeneratedPathsSettings,
	tags: ["autodocs"],
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	args: {
		settings: reviewSettings(),
		repositories: {
			status: "ready",
			options: [
				{ itemId: "101", name: "owner/repo" },
				{ itemId: "102", name: "group/nested/project" },
			],
		},
		isSaving: false,
		onSave: fn(async () => undefined),
	},
} satisfies Meta<typeof WorkspaceGeneratedPathsSettings>;
export default meta;
type Story = StoryObj<typeof meta>;

export const EmptyPatterns: Story = {};
export const Configured: Story = {
	args: {
		settings: reviewSettings({
			generatedPaths: {
				"101": ["webapp/src/api/**", "**/*.generated.ts"],
				"102": ["client/**"],
			},
		}),
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue(
			"webapp/src/api/**\n**/*.generated.ts",
		);
		await expect(canvas.getByRole("textbox", { name: "group/nested/project" })).toHaveValue(
			"client/**",
		);
	},
};
export const NoRepositories: Story = { args: { repositories: { status: "ready", options: [] } } };
export const LoadingRepositories: Story = { args: { repositories: { status: "loading" } } };
/** The next page's placeholder rows continue the list of repositories, in their shape, while it is busy. */
export const LoadingMoreRepositories: Story = {
	args: {
		repositories: {
			status: "ready",
			options: [
				{ itemId: "101", name: "owner/repo" },
				{ itemId: "102", name: "group/nested/project" },
			],
			total: 40,
			more: { hasMore: true, isLoadingMore: true, onLoadMore: fn() },
		},
	},
	play: async ({ canvas }) => {
		const list = canvas.getByRole("list");
		// The name, the summary and the press of a repository row.
		await expectListPlaceholders(list, { bars: 3 });
		await expect(list.lastElementChild).toHaveAttribute("data-loading-row");
		await expect(canvas.getByRole("button", { name: "Loading repositories…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};
export const FailedRepositories: Story = {
	args: { repositories: { status: "error", error: new Error("Unavailable"), onRetry: fn() } },
};
export const Saving: Story = {
	args: { onSave: fn(pending) },
	play: async ({ canvas }) => {
		// Nothing is saved for it, so its row starts closed.
		await userEvent.click(canvas.getByRole("button", { name: "Generated paths for owner/repo" }));
		await userEvent.type(canvas.getByRole("textbox", { name: "owner/repo" }), "generated/**");
		await userEvent.click(
			canvas.getByRole("button", { name: "Save generated paths for owner/repo" }),
		);
		await expect(canvas.getByRole("button", { name: "Saving…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toBeDisabled();
	},
};
export const SaveFailed: Story = {
	args: {
		onSave: fn(async () => {
			throw new Error("Settings changed");
		}),
	},
	play: async ({ canvas }) => {
		// Nothing is saved for it, so its row starts closed.
		await userEvent.click(canvas.getByRole("button", { name: "Generated paths for owner/repo" }));
		await userEvent.type(canvas.getByRole("textbox", { name: "owner/repo" }), "generated/**");
		await userEvent.click(
			canvas.getByRole("button", { name: "Save generated paths for owner/repo" }),
		);
		await expect(canvas.getByText(/We could not save generated paths/u)).toBeVisible();
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue("generated/**");
	},
};
export const InvalidPatternLength: Story = {
	play: async ({ canvas }) => {
		// Nothing is saved for it, so its row starts closed.
		await userEvent.click(canvas.getByRole("button", { name: "Generated paths for owner/repo" }));
		await userEvent.type(canvas.getByRole("textbox", { name: "owner/repo" }), "a".repeat(513));
		await expect(
			canvas.getByText("Use at most 100 patterns, each no longer than 512 characters."),
		).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Save generated paths for owner/repo" }),
		).toBeDisabled();
	},
};

export const SavedPatternsChanged: Story = {
	render: (args) => (
		<Stateful initial={args.settings}>
			{(settings, setSettings) => (
				<>
					<WorkspaceGeneratedPathsSettings {...args} settings={settings} />
					<Button
						onClick={() =>
							setSettings({
								...settings,
								etag: '"1"',
								generatedPaths: { ...settings.generatedPaths, "101": ["other/**"] },
							})
						}
					>
						Update saved patterns
					</Button>
				</>
			)}
		</Stateful>
	),
	play: async ({ canvas }) => {
		// Nothing is saved for it, so its row starts closed.
		await userEvent.click(canvas.getByRole("button", { name: "Generated paths for owner/repo" }));
		await userEvent.type(canvas.getByRole("textbox", { name: "owner/repo" }), "draft/**");
		await userEvent.click(canvas.getByRole("button", { name: "Update saved patterns" }));
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue("draft/**");
		await expect(
			canvas.getByText(
				"Saved patterns changed while you were editing. Your draft is not saved. Use the saved patterns, then make your changes again.",
			),
		).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Save generated paths for owner/repo" }),
		).toBeDisabled();
		await userEvent.click(canvas.getByRole("button", { name: "Use saved patterns" }));
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue("other/**");
	},
};

const RETAINED = Array.from({ length: 74 }, (_, index) => ({
	itemId: String(200 + index),
	name: "Repository",
	nameKnown: false,
	removedAt: index % 3 === 0 ? new Date("2026-09-01T00:00:00Z") : null,
}));

/**
 * One repository with saved patterns among 74 retained items with no repository record: only the
 * saved one opens, every other row is a closed line named by its item ID, and a search finds one.
 */
export const ManyRetainedRepositories: Story = {
	args: {
		settings: reviewSettings({ generatedPaths: { "101": ["client/**"] } }),
		repositories: {
			status: "ready",
			options: [{ itemId: "101", name: "owner/repo" }, ...RETAINED],
			total: 75,
		},
	},
	play: async ({ canvas }) => {
		await expect(canvas.getByText(/^75 repositories loaded/u)).toBeVisible();
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue("client/**");
		await expect(canvas.queryByRole("textbox", { name: "Repository 201" })).toBeNull();
		await expect(canvas.getAllByText("Name unavailable")).toHaveLength(74);

		await userEvent.type(canvas.getByRole("searchbox", { name: "Search repositories" }), "273");
		await expect(canvas.getByText(/^1 of 75 loaded repositories match/u)).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Generated paths for Repository 273" }),
		).toBeVisible();
		await expect(
			canvas.queryByRole("button", { name: "Generated paths for Repository 201" }),
		).toBeNull();
	},
};
