import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow, expectPanelContentInset } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { definitionOf, mockDefinitionOptions } from "@/components/admin/practice-editor/fixtures";

import { mockGroups, mockPracticeOf, mockScriptedPractice, practiceScript } from "./fixtures";
import { GUARDED_LEVEL_KINDS, practiceFormLevel } from "./practice-search";
import { PracticeForm } from "./PracticeForm";
import { PracticeFormLevel } from "./PracticeFormLevel";

const createSubmit = fn();
const editSubmit = fn();

const meta = {
	component: PracticeForm,
	parameters: {
		layout: "fullscreen",
		chromatic: { viewports: [1440] },
	},
	tags: ["autodocs"],
	decorators: [withPageBehind],
	args: {
		mode: "create",
		workspaceSlug: "demo",
		groups: mockGroups,
		definitionOptions: mockDefinitionOptions,
		onSubmit: createSubmit,
		isPending: false,
		cancel: <LevelCancel />,
	},
	argTypes: { cancel: { control: false } },
	render: (args) => (
		<Stateful
			initial={[practiceFormLevel(args.mode === "edit" ? args.initialData.slug : undefined)]}
		>
			{(stack, setStack) => (
				<DetailDrawerStack
					stack={stack}
					guardedKinds={GUARDED_LEVEL_KINDS}
					onClose={(depth) => setStack(stack.slice(0, depth))}
				>
					{(entry, level) => (
						<PracticeFormLevel
							nested={level.nested}
							path={{
								behind: [{ label: "Practice setup", depth: 0 }],
								onClose: (depth) => setStack(stack.slice(0, depth)),
							}}
							creating={entry.kind === "practice-new"}
						>
							<PracticeForm {...args} />
						</PracticeFormLevel>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
} satisfies Meta<typeof PracticeForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Create: Story = {
	parameters: {
		viewport: { defaultViewport: "reflow" },
		chromatic: { viewports: [320, 1440] },
	},
	play: async () => {
		// The level is the full viewport at 320px, so the longest form in the app has to fit it by
		// scrolling down and never across.
		await expectNoPanelOverflow(await settledDrawerPanel());
		await expect(screen.queryByText("What the reviews recorded")).not.toBeInTheDocument();
	},
};

export const EscapeLeavesACleanEditor: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		await settledDrawerPanel();

		// Nothing typed, so nothing to ask about: Escape leaves, exactly as it does on a read-only
		// panel. An editor that swallowed the gesture instead would be indistinguishable from a
		// broken drawer. `-adoption-route.test.tsx` owns the half where a draft exists.
		await userEvent.keyboard("{Escape}");
		await waitFor(async () =>
			expect(document.querySelectorAll('[data-slot="drawer-popup"]')).toHaveLength(0),
		);
	},
};

export const Edit: Story = {
	args: { mode: "edit", initialData: mockScriptedPractice, onSubmit: editSubmit },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByText(/the change becomes version 2/u)).toBeVisible();
		await expect(
			screen.getByRole("link", { name: "See review outcomes" }).getAttribute("href"),
		).toMatch(/^\/w\/demo\/admin\/practices\/reviews\/results\?.*commit-discipline/u);
		await expect(screen.getByRole("link", { name: "AI models" })).toHaveAttribute(
			"href",
			"/w/demo/admin/models",
		);
	},
};

/** The shipped instructions of "Keep the change reviewable": long Markdown lines and file paths. */
const SHIPPED_CRITERIA = [
	"Assess the complete practice standard within the captured evidence boundary.",
	"",
	"REVIEW FOCUS: the meaningful portion of this change is batched at a size and spread a reviewer can read end-to-end with confidence.",
	"",
	"## The standard",
	"Judge whether this change is packaged so a reviewer can actually read it end-to-end and approve it responsibly. This is a workflow practice about how work is batched for review — never a code-defect claim about the code itself. A focused change does one coherent thing across a handful of related files; a sprawling one forces the reviewer to skim, and bugs slip through the gaps.",
	"",
	"## Sources",
	"1. The provider counts (`context/metadata.json`): additions, deletions, total and changed-file count as synced. They establish the headline size.",
	"2. The file index (`work/change/files.json`): the per-file spread and which files carry repetitive generated, vendored or lock churn.",
	"3. The annotated diff (`work/change/diff.patch`): ground truth for what was actually changed and how much of it is meaningful.",
].join("\n");

/**
 * The editor as an admin opens it on a desktop: every field and both footer actions sit inside the
 * panel, with nothing to scroll sideways for.
 */
export const EditAtDesktop: Story = {
	args: {
		mode: "edit",
		onSubmit: editSubmit,
		initialData: mockPracticeOf({
			id: "7",
			slug: "keep-the-change-reviewable",
			definition: definitionOf({
				criteria: SHIPPED_CRITERIA,
				script: `${practiceScript()}\n// ${"A long comment line that a code editor must scroll rather than widen the panel for. ".repeat(4)}`,
			}),
		}),
	},
	globals: { viewport: { value: "desktop" } },
	play: async () => {
		const panel = await settledDrawerPanel();
		await expectNoPanelOverflow(panel);
		const bounds = panel.getBoundingClientRect();
		for (const name of ["Cancel", "Save changes"]) {
			const button = within(panel).getByRole("button", { name }).getBoundingClientRect();
			await expect(button.left).toBeGreaterThanOrEqual(bounds.left);
			await expect(button.right).toBeLessThanOrEqual(bounds.right);
		}
	},
};

export const EditConflict: Story = {
	args: {
		mode: "edit",
		initialData: mockScriptedPractice,
		onSubmit: editSubmit,
		notice: (
			<Alert variant="warning" role="alert">
				<AlertTitle>Someone saved a newer version</AlertTitle>
				<AlertDescription>Your changes are still here. Nothing was saved.</AlertDescription>
			</Alert>
		),
	},
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("alert")).toHaveTextContent(/Nothing was saved/u);
	},
};

/** A read behind the form failed to refresh. The draft stays open beside the retry. */
export const RefreshFailed: Story = {
	args: {
		mode: "edit",
		initialData: mockScriptedPractice,
		onSubmit: fn(),
		refreshAlert: (
			<QueryErrorAlert
				error={{ status: 503 }}
				title="We could not refresh the practice editor"
				onRetry={fn()}
			/>
		),
	},
	play: async () => {
		await expectPanelContentInset(await settledDrawerPanel());
		await expect(screen.getByText("We could not refresh the practice editor")).toBeVisible();
		await expect(screen.getByRole("button", { name: "Retry" })).toBeEnabled();
		await expect(screen.getByRole("textbox", { name: /Name/u })).toHaveValue(
			mockScriptedPractice.currentVersion.definition?.name,
		);
	},
};

export const Submitting: Story = {
	args: { isPending: true, onSubmit: fn() },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("textbox", { name: /Name/u })).toBeDisabled();
	},
};

export const CreateSends: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		await settledDrawerPanel();
		createSubmit.mockClear();
		await userEvent.type(screen.getByRole("textbox", { name: /Name/u }), "Clear review context");
		await userEvent.type(
			screen.getByRole("textbox", { name: /What to look for/u }),
			"Check whether the reviewed work explains its purpose.",
		);
		await userEvent.click(screen.getByRole("button", { name: "Choose sources" }));
		await userEvent.click(
			within(
				screen.getByRole("radiogroup", { name: "How Pull request details is read" }),
			).getByRole("radio", { name: "Required" }),
		);
		await userEvent.click(screen.getByRole("button", { name: "Create practice" }));
		await expect(createSubmit).toHaveBeenCalledOnce();
		await expect(createSubmit.mock.lastCall?.[0]).toMatchObject({
			slug: "clear-review-context",
			kind: "scm.pull_request",
			position: null,
			definition: {
				name: "Clear review context",
				criteria: "Check whether the reviewed work explains its purpose.",
				reads: {
					contract: "3.0.0",
					sources: [{ source: "scm.pull-request.core", need: "REQUIRED" }],
				},
			},
		});
	},
};
