import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, waitFor } from "storybook/test";

import { DetailDrawerStack } from "@/components/layout/detail-drawer/DetailDrawerStack";
import { LevelCancel } from "@/components/layout/detail-drawer/LevelCancel";
import {
	mockConversationWorkType,
	mockPracticeDefinitionOptions,
	mockPullRequestWorkType,
} from "@/mocks/fixtures/practice";
import { withPageBehind } from "@/stories/decorators";
import { settledDrawerPanel } from "@/stories/overlay";
import { expectNoPanelOverflow } from "@/stories/reflow";
import { Stateful } from "@/stories/stateful";

import { mockGroups, mockPracticeWithAllTriggers } from "./fixtures";
import { GUARDED_LEVEL_KINDS, practiceFormLevel } from "./practice-search";
import { PracticeForm } from "./PracticeForm";
import { PracticeFormLevel } from "./PracticeFormLevel";

const createSubmit = fn();
const editSubmit = fn();

/**
 * The editor is a level of the practice-setup drawer stack, so the tree a practice belongs to stays
 * on screen while it is written — and so these stories exercise the surface people actually get.
 * The level is guarded: only Cancel and Save leave it.
 */
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
		definitionOptions: mockPracticeDefinitionOptions,
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
						<PracticeFormLevel nested={level.nested} creating={entry.kind === "practice-new"}>
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
		await waitFor(() =>
			expect(document.querySelectorAll('[data-slot="drawer-popup"]')).toHaveLength(0),
		);
	},
};

export const EditWithAdvanced: Story = {
	args: { mode: "edit", initialData: mockPracticeWithAllTriggers, onSubmit: fn() },
};

export const Submitting: Story = {
	args: { isPending: true, onSubmit: fn() },
	play: async () => {
		await settledDrawerPanel();
		await expect(screen.getByRole("textbox", { name: /Name/u })).toBeDisabled();
	},
};

export const EditClearsOptionalGuidance: Story = {
	args: {
		mode: "edit",
		initialData: {
			...mockPracticeWithAllTriggers,
			whyItMatters: "Small commits make review safer.",
			whatGoodLooksLike: "Each commit explains one coherent change.",
		},
		onSubmit: editSubmit,
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		await settledDrawerPanel();
		editSubmit.mockClear();
		await userEvent.clear(screen.getByRole("textbox", { name: "Why it matters" }));
		await userEvent.clear(screen.getByRole("textbox", { name: "What good looks like" }));
		await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

		await expect(editSubmit).toHaveBeenCalledWith(
			"commit-discipline",
			expect.objectContaining({
				clear: ["WHY_IT_MATTERS", "WHAT_GOOD_LOOKS_LIKE"],
			}),
			null,
		);
	},
};

export const ValidationErrors: Story = {
	parameters: { chromatic: { viewports: [320, 1440] } },
	play: async () => {
		await settledDrawerPanel();
		await userEvent.click(screen.getByRole("button", { name: "Create practice" }));
		await expect(screen.getByText("Name must be at least 3 characters")).toBeVisible();
		await expect(screen.queryByText("Select at least one trigger event")).not.toBeInTheDocument();
		await expect(screen.getByRole("textbox", { name: /Name/u })).toHaveAttribute(
			"aria-invalid",
			"true",
		);
	},
};

export const ValidationAndSubmit: Story = {
	...ValidationErrors,
	parameters: { chromatic: { disableSnapshot: true } },
	play: async (context) => {
		createSubmit.mockClear();
		await ValidationErrors.play?.(context);

		await userEvent.type(screen.getByRole("textbox", { name: /Name/u }), "Clear review context");
		await userEvent.type(
			screen.getByRole("textbox", { name: /What to look for/u }),
			"Check whether the reviewed work explains its purpose.",
		);
		await userEvent.click(screen.getByRole("button", { name: "Create practice" }));
		await expect(createSubmit).toHaveBeenCalledWith(
			{
				name: "Clear review context",
				slug: "clear-review-context",
				criteria: "Check whether the reviewed work explains its purpose.",
				bindings: [
					{
						signals: [
							"scm.pull_request.opened",
							"scm.pull_request.ready",
							"scm.pull_request.synchronized",
						],
						needs: mockPullRequestWorkType.recommendedNeeds,
					},
				],
				automatedReviewPolicy: mockPullRequestWorkType.recommendedPolicy,
			},
			null,
		);
	},
};

export const ConversationPractice: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		await settledDrawerPanel();
		createSubmit.mockClear();
		await userEvent.type(screen.getByRole("textbox", { name: /Name/u }), "Helpful discussion");
		await userEvent.click(screen.getByRole("radio", { name: /Conversation/u }));
		// A conversation is settled or it is not, so its one occasion is chosen for the author rather
		// than left as an empty list that cannot be saved.
		await expect(screen.getByRole("checkbox", { name: "Discussion settled" })).toBeChecked();
		await userEvent.type(
			screen.getByRole("textbox", { name: /What to look for/u }),
			"Check whether the conversation stays constructive.",
		);
		await userEvent.click(screen.getByRole("button", { name: "Create practice" }));
		await expect(createSubmit).toHaveBeenCalledWith(
			{
				name: "Helpful discussion",
				slug: "helpful-discussion",
				criteria: "Check whether the conversation stays constructive.",
				bindings: [
					{
						signals: ["chat.conversation_thread.settled"],
						needs: mockConversationWorkType.recommendedNeeds,
					},
				],
				automatedReviewPolicy: mockConversationWorkType.recommendedPolicy,
			},
			null,
		);
	},
};
