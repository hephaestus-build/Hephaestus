import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, userEvent, within } from "storybook/test";

import { expectSettledVisible } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";

import { ProductFeedbackDialog } from "./ProductFeedbackDialog";
import { ProductFeedbackReceipt } from "./ProductFeedbackReceipt";

const context = {
	pagePath: "/w/$workspaceSlug/practice-profile",
	userAgent: "Mozilla/5.0 (X11; Linux x86_64) Firefox/140.0",
};

const meta = {
	component: ProductFeedbackDialog,
	args: {
		open: true,
		onOpenChange: fn(),
		kind: "FEEDBACK",
		onKindChange: fn(),
		context,
		sharing: { status: "off" },
		isSubmitting: false,
		onSubmit: fn(async () => true),
	},
	render: (args) => (
		<Stateful initial={args.kind}>
			{(kind, setKind) => (
				<ProductFeedbackDialog
					{...args}
					kind={kind}
					onKindChange={(next) => {
						args.onKindChange(next);
						setKind(next);
					}}
				/>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof ProductFeedbackDialog>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

/** Feedback attaches nothing unless the sender ticks the box. */
export const SendsFeedbackWithoutContext: Story = {
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(
			dialog.getByRole("checkbox", { name: "Attach the page and browser you’re on" }),
		).not.toBeChecked();
		await userEvent.type(
			dialog.getByRole("textbox", { name: "Your feedback" }),
			"The product feedback form is clear.",
		);
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith({
			kind: "FEEDBACK",
			message: "The product feedback form is clear.",
			pagePath: undefined,
			userAgent: undefined,
		});
	},
};

/** A bug report starts with the details ticked, and shows exactly what they are. */
export const BugReport: Story = {
	args: { kind: "BUG" },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(
			dialog.getByRole("checkbox", { name: "Attach the page and browser you’re on" }),
		).toBeChecked();
		await expectSettledVisible(dialog.getByText(context.pagePath));
		await expectSettledVisible(dialog.getByText(context.userAgent));
		await expectSettledVisible(dialog.getByRole("heading", { name: "Report a bug" }));
		await userEvent.type(
			dialog.getByRole("textbox", { name: "What happened?" }),
			"The list jumps.",
		);
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith({
			kind: "BUG",
			message: "The list jumps.",
			pagePath: context.pagePath,
			userAgent: context.userAgent,
		});
	},
};

/** Choosing a kind renames the dialog and the field, so the ask matches what was picked. */
export const Idea: Story = {
	args: { kind: "IDEA" },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("heading", { name: "Share an idea" }));
		await expect(
			dialog.getByRole("checkbox", { name: "Attach the page and browser you’re on" }),
		).not.toBeChecked();
		await userEvent.type(dialog.getByRole("textbox", { name: "Your idea" }), "Pin a practice.");
		await userEvent.click(dialog.getByRole("radio", { name: "Bug" }));
		await expect(dialog.getByRole("heading", { name: "Report a bug" })).toBeVisible();
		await expect(dialog.getByRole("textbox", { name: "What happened?" })).toHaveValue(
			"Pin a practice.",
		);
		await expect(args.onKindChange).toHaveBeenCalledWith("BUG");
	},
};

/** The counter appears only when the limit is near; before that it would only be noise. */
export const NearTheLimit: Story = {
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		const field = dialog.getByRole("textbox", { name: "Your feedback" });
		await expect(dialog.queryByText(/left$/u)).toBeNull();
		await userEvent.click(field);
		await userEvent.paste("x".repeat(4600));
		await expectSettledVisible(dialog.getByText("400 characters left"));
	},
};

export const Sending: Story = {
	args: { isSubmitting: true },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByRole("button", { name: "Sending…" }));
		await expect(dialog.getByRole("textbox", { name: "Your feedback" })).toBeDisabled();
	},
};

export const Error: Story = {
	args: {
		error: "We could not send that. Your draft is still here.",
		onSubmit: fn(async () => false),
	},
	play: async () => {
		await expectSettledVisible(within(await screen.findByRole("dialog")).getByRole("alert"));
	},
};

/** Without a page, the context choice is not offered at all. */
export const NoContext: Story = {
	args: { context: undefined },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.queryByRole("checkbox")).toBeNull();
	},
};

/**
 * The first report on an instance with a public repository: neither option is picked, and Send
 * waits for one, because a default here would be a choice the person did not make.
 */
export const FirstTimeChoice: Story = {
	args: { sharing: { status: "ask" } },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(dialog.getByRole("textbox", { name: "Your feedback" }), "Pin a practice.");
		const share = dialog.getByRole("radio", { name: "Yes, share it publicly." });
		const keep = dialog.getByRole("radio", { name: "No, keep it private." });
		await expect(share).not.toBeChecked();
		await expect(keep).not.toBeChecked();
		await expect(dialog.getByRole("button", { name: "Send" })).toBeDisabled();
		await userEvent.click(keep);
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith(
			expect.objectContaining({ message: "Pin a practice.", share: false }),
		);
	},
};

/** After the first choice, one switch starts from it and changes this report only. */
export const RememberedChoice: Story = {
	args: { sharing: { status: "remembered", allowPublic: true } },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		const toggle = dialog.getByRole("switch", { name: "Public GitHub issue" });
		await expect(toggle).toBeChecked();
		await expect(dialog.getByRole("link", { name: "Change default" })).toHaveAttribute(
			"href",
			"/settings#product-feedback",
		);
		await userEvent.click(toggle);
		await userEvent.type(dialog.getByRole("textbox", { name: "Your feedback" }), "Keep this one.");
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith(expect.objectContaining({ share: false }));
	},
};

/** An instance with only a private repository asks nothing and says where the report goes. */
export const PrivateRepositoryOnly: Story = {
	args: { sharing: { status: "private" } },
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expect(dialog.getByText(/private GitHub issue from your report/u)).toBeVisible();
		await expect(dialog.queryByRole("switch", { name: "Public GitHub issue" })).toBeNull();
		await expect(dialog.queryByRole("radio", { name: /share it publicly/u })).toBeNull();
		await userEvent.type(dialog.getByRole("textbox", { name: "Your feedback" }), "Private note.");
		await userEvent.click(dialog.getByRole("button", { name: "Send" }));
		await expect(args.onSubmit).toHaveBeenCalledWith(expect.not.objectContaining({ share: true }));
		await expect(args.onSubmit).toHaveBeenCalledWith(expect.not.objectContaining({ share: false }));
	},
};

/** Send waits while the choice is read, so a report is never sent without the question it needs. */
export const SharingLoading: Story = {
	args: { sharing: { status: "loading" } },
	play: async () => {
		const dialog = within(await screen.findByRole("dialog"));
		await userEvent.type(dialog.getByRole("textbox", { name: "Your feedback" }), "An idea.");
		await expect(dialog.getByRole("button", { name: "Send" })).toBeDisabled();
	},
};

/** Once sent, the confirmation takes the form's place until the dialog closes. */
export const Confirmation: Story = {
	args: {
		confirmation: (
			<ProductFeedbackReceipt
				report={{
					id: "a1",
					state: "SENT_TO_ADMINS",
					route: "EMAIL",
					canKeepPrivate: false,
					canEdit: false,
				}}
				onKeepPrivate={fn()}
				onEdit={fn(async () => true)}
			/>
		),
	},
	play: async ({ args }) => {
		const dialog = within(await screen.findByRole("dialog"));
		await expectSettledVisible(dialog.getByText("Thanks. The admins of this instance have it."));
		await expect(dialog.queryByRole("textbox")).toBeNull();
		await userEvent.click(dialog.getByRole("button", { name: "Done" }));
		await expect(args.onOpenChange).toHaveBeenCalledWith(false);
	},
};
