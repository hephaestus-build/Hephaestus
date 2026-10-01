import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent } from "storybook/test";
import { Button } from "@/components/ui/button";
import { Stateful } from "@/stories/stateful";
import { pending } from "@/test/async";
import { mockReviewSettings } from "./fixtures";
import { GeneratedPathsSettings } from "./GeneratedPathsSettings";

const meta = {
	component: GeneratedPathsSettings,
	tags: ["autodocs"],
	parameters: {
		layout: "padded",
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	args: {
		settings: mockReviewSettings(),
		repositories: {
			status: "ready",
			options: [
				{ value: "owner/repo", label: "owner/repo" },
				{ value: "group/nested/project", label: "group/nested/project" },
			],
		},
		isSaving: false,
		onSave: fn(async () => undefined),
	},
} satisfies Meta<typeof GeneratedPathsSettings>;
export default meta;
type Story = StoryObj<typeof meta>;

export const EmptyPatterns: Story = {};
export const Configured: Story = {
	args: {
		settings: mockReviewSettings({
			generatedPaths: {
				"owner/repo": ["webapp/src/api/**", "**/*.generated.ts"],
				"group/nested/project": ["client/**"],
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
export const FailedRepositories: Story = {
	args: { repositories: { status: "error", error: new Error("Unavailable"), onRetry: fn() } },
};
export const Saving: Story = {
	args: { onSave: fn(pending) },
	play: async ({ canvas }) => {
		await userEvent.type(canvas.getByRole("textbox", { name: "owner/repo" }), "generated/**");
		await userEvent.click(
			canvas.getByRole("button", { name: "Save generated paths for owner/repo" }),
		);
		await expect(canvas.getByRole("button", { name: "Saving…" })).toBeDisabled();
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
		await userEvent.type(canvas.getByRole("textbox", { name: "owner/repo" }), "generated/**");
		await userEvent.click(
			canvas.getByRole("button", { name: "Save generated paths for owner/repo" }),
		);
		await expect(canvas.getByText(/Couldn't save generated paths/u)).toBeVisible();
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue("generated/**");
	},
};
export const InvalidPatternLength: Story = {
	play: async ({ canvas }) => {
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
					<GeneratedPathsSettings {...args} settings={settings} />
					<Button
						onClick={() =>
							setSettings({
								...settings,
								etag: '"1"',
								generatedPaths: { ...settings.generatedPaths, "owner/repo": ["other/**"] },
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
		await userEvent.type(canvas.getByRole("textbox", { name: "owner/repo" }), "draft/**");
		await userEvent.click(canvas.getByRole("button", { name: "Update saved patterns" }));
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue("draft/**");
		await expect(
			canvas.getByText(
				"Saved patterns changed while you were editing. Your draft has not been saved.",
			),
		).toBeVisible();
		await expect(
			canvas.getByRole("button", { name: "Save generated paths for owner/repo" }),
		).toBeDisabled();
		await userEvent.click(canvas.getByRole("button", { name: "Use saved patterns" }));
		await expect(canvas.getByRole("textbox", { name: "owner/repo" })).toHaveValue("other/**");
	},
};
