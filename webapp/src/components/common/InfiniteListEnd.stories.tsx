import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect, fn, userEvent, waitFor } from "storybook/test";

import { Skeleton } from "@/components/ui/skeleton";

import { InfiniteListEnd } from "./InfiniteListEnd";

const meta = {
	component: InfiniteListEnd,
	tags: ["autodocs"],
	parameters: { layout: "padded" },
	args: {
		hasMore: true,
		isLoadingMore: false,
		onLoadMore: fn(),
		moreLabel: "View earlier reviews",
		failedLabel: "We could not load earlier reviews.",
		loadingRow: <Skeleton className="h-24 w-full" />,
	},
	argTypes: { loadingRow: { control: false } },
	decorators: [
		(Story) => (
			<div className="flex max-w-xl flex-col gap-2.5">
				<Skeleton className="h-24 w-full" />
				<Story />
			</div>
		),
	],
} satisfies Meta<typeof InfiniteListEnd>;

export default meta;
type Story = StoryObj<typeof meta>;

/** More to load and the end in view: the next page is asked for once, with no press. */
export const LoadsMoreInView: Story = {
	play: async ({ args, canvas }) => {
		await waitFor(async () => expect(args.onLoadMore).toHaveBeenCalledOnce());
		await expect(canvas.getByRole("button", { name: "View earlier reviews" })).toBeVisible();
	},
};

/** The next page on its way: a row in the list's shape stands in for it, and the press waits. */
export const LoadingMore: Story = {
	args: { isLoadingMore: true },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("button", { name: "Loading…" })).toBeDisabled();
		await expect(args.onLoadMore).not.toHaveBeenCalled();
	},
};

/** Nothing left to load: the list ends with nothing after its last row. */
export const EndOfList: Story = {
	args: { hasMore: false },
	play: async ({ args, canvas }) => {
		await expect(canvas.queryByRole("button")).toBeNull();
		await expect(args.onLoadMore).not.toHaveBeenCalled();
	},
};

/**
 * The last page failed: the list says so, asks for nothing by itself, and the press asks again.
 */
export const Failed: Story = {
	args: { loadMoreError: new Error("offline") },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("alert")).toHaveTextContent("We could not load earlier reviews.");
		await expect(args.onLoadMore).not.toHaveBeenCalled();
		await userEvent.click(canvas.getByRole("button", { name: "Retry" }));
		await expect(args.onLoadMore).toHaveBeenCalledOnce();
	},
};
