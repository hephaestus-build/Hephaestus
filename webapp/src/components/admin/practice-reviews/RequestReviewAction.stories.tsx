import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, screen, waitFor } from "storybook/test";

import { Stateful } from "@/stories/stateful";

import { REQUEST_BLOCKED, RequestReviewAction } from "./RequestReviewAction";

const meta = {
	component: RequestReviewAction,
	parameters: { layout: "centered" },
	tags: ["autodocs"],
	args: { request: { work: "Pull request #1423", onRequest: fn() } },
	// The route marks the ask in flight once it is sent; here a press does the same.
	render: (args) => (
		<Stateful initial={args.request.requesting ?? false}>
			{(requesting, setRequesting) => {
				const { onRequest } = args.request;
				return (
					<RequestReviewAction
						{...args}
						request={{
							...args.request,
							requesting,
							onRequest:
								onRequest &&
								(() => {
									onRequest();
									setRequesting(true);
								}),
						}}
					/>
				);
			}}
		</Stateful>
	),
} satisfies Meta<typeof RequestReviewAction>;

export default meta;
type Story = StoryObj<typeof meta>;

/** A row's icon button asks on one press, with no dialog, and then waits for the answer. */
export const InARow: Story = {
	play: async ({ args, canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: "Request review of pull request #1423" });
		await userEvent.click(button);
		await expect(args.request.onRequest).toHaveBeenCalledOnce();
		await expect(screen.queryByRole("alertdialog")).toBeNull();
		await expect(button).toHaveAttribute("aria-disabled", "true");
		// The pointer is still on the button from the press: it leaves and comes back to read why.
		await userEvent.unhover(button);
		await userEvent.hover(button);
		await waitFor(async () => expect(screen.getByText("Requesting review…")).toBeVisible());
	},
};

/** A sheet's labelled button asks on one press and says it is requesting. */
export const InASheet: Story = {
	args: { label: "Request another review" },
	play: async ({ args, canvas, userEvent }) => {
		await userEvent.click(canvas.getByRole("button", { name: "Request another review" }));
		await expect(args.request.onRequest).toHaveBeenCalledOnce();
		await expect(screen.queryByRole("alertdialog")).toBeNull();
		await expect(canvas.getByRole("button", { name: "Requesting review…" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
	},
};

/** While a review of the work is queued or running, it stays in place and says why. */
export const BlockedWhileActive: Story = {
	args: {
		label: "Request review",
		request: { work: "Pull request #1423", onRequest: fn(), blocked: REQUEST_BLOCKED.active },
	},
	play: async ({ args, canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: "Request review" });
		await expect(button).toHaveAttribute("aria-disabled", "true");
		await userEvent.click(button);
		await expect(args.request.onRequest).not.toHaveBeenCalled();
		await userEvent.hover(button);
		await waitFor(async () => expect(screen.getByText(REQUEST_BLOCKED.active)).toBeVisible());
	},
};

/** Work of a kind nobody can ask about names the kinds that can be asked about. */
export const KindNobodyCanAskAbout: Story = {
	args: { request: { work: "Commit 3f2a9c1" } },
	play: async ({ canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: "Request review of commit 3f2a9c1" });
		await expect(button).toHaveAttribute("aria-disabled", "true");
		await userEvent.hover(button);
		await waitFor(async () => expect(screen.getByText(REQUEST_BLOCKED.kind)).toBeVisible());
	},
};
