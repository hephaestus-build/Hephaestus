import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { Toaster, toast } from "sonner";
import { afterEach, beforeEach, expect, it, vi } from "vitest";

import { type ReviewedPullRequest, ReviewsPopover } from "./ReviewsPopover";

const originalClipboard = Object.getOwnPropertyDescriptor(navigator, "clipboard");
const writeText = vi.fn<Clipboard["writeText"]>();
const reviews = [
	{
		id: 1,
		number: 1,
		htmlUrl: "https://example.com/repo/pull/1",
		title: "Review work",
		isDraft: false,
		isMerged: false,
		state: "OPEN",
	},
] satisfies ReviewedPullRequest[];

beforeEach(() => {
	writeText.mockReset();
	Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
	vi.stubGlobal("ClipboardItem", undefined);
});

afterEach(() => {
	toast.dismiss();
	vi.unstubAllGlobals();
	if (originalClipboard) {
		Object.defineProperty(navigator, "clipboard", originalClipboard);
	} else {
		Reflect.deleteProperty(navigator, "clipboard");
	}
});

async function openCopyAction() {
	render(
		<>
			<ReviewsPopover reviewedPullRequests={reviews} />
			<Toaster />
		</>,
	);
	fireEvent.click(screen.getByRole("button", { name: "Show 1 reviewed pull request" }));
	return screen.findByRole<HTMLButtonElement>("button", {
		name: "Copy links to reviewed pull requests",
	});
}

it("announces success only after the clipboard write completes", async () => {
	const finishWrite = vi.fn<() => void>();
	const pending = new Promise<void>((resolve) => {
		finishWrite.mockImplementation(resolve);
	});
	writeText.mockReturnValue(pending);
	const copy = await openCopyAction();
	fireEvent.click(copy);
	expect(copy.disabled).toBe(true);
	expect(copy.getAttribute("aria-label")).toBe("Copying review links…");
	expect(screen.queryByText("Review links copied")).toBeNull();
	await act(async () => {
		finishWrite();
	});
	await screen.findByText("Review links copied");
	expect(copy.disabled).toBe(false);
});

it("reports denied access without success and permits retry", async () => {
	writeText.mockRejectedValueOnce(new Error("Permission denied")).mockResolvedValueOnce();
	const copy = await openCopyAction();
	fireEvent.click(copy);
	await screen.findByText("Could not copy review links");
	expect(screen.queryByText("Review links copied")).toBeNull();
	await waitFor(() => expect(copy.disabled).toBe(false));
	fireEvent.click(copy);
	await screen.findByText("Review links copied");
});

it("leaves work without a safe URL readable but not navigable or copyable", async () => {
	render(
		<ReviewsPopover
			reviewedPullRequests={[
				{
					...reviews[0],
					title: "Review work",
					isDraft: false,
					isMerged: false,
					state: "OPEN",
					id: 2,
					number: 2,
					htmlUrl: "javascript:alert(1)",
				},
			]}
		/>,
	);
	fireEvent.click(screen.getByRole("button", { name: "Show 1 reviewed pull request" }));
	await screen.findByText("#2");
	expect(screen.queryByRole("link")).toBeNull();
	expect(
		screen.getByRole<HTMLButtonElement>("button", { name: "Copy links to reviewed pull requests" })
			.disabled,
	).toBe(true);
});

it("copies only usable links while retaining rows with missing URLs", async () => {
	writeText.mockResolvedValue();
	render(
		<ReviewsPopover
			reviewedPullRequests={[
				...reviews,
				{ id: 2, number: 2, title: "Review work", isDraft: false, isMerged: false, state: "OPEN" },
			]}
		/>,
	);
	fireEvent.click(screen.getByRole("button", { name: "Show 2 reviewed pull requests" }));
	await screen.findByText("#2");
	expect(screen.getAllByRole("link")).toHaveLength(1);
	fireEvent.click(screen.getByRole("button", { name: "Copy links to reviewed pull requests" }));
	await waitFor(() => expect(writeText).toHaveBeenCalledWith("https://example.com/repo/pull/1"));
});
