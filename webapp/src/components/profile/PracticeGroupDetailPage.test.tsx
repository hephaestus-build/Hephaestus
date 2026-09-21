import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PracticeGroup, PracticeGroupReviewRun, PracticeGroupStanding } from "@/api/types.gen";
import { daysBefore } from "@/components/common/story-clock";
import { PracticeGroupDetailPage } from "./PracticeGroupDetailPage";

const group: PracticeGroup = {
	id: 1,
	slug: "review-ready-work",
	name: "Packaging work for review",
	description: "Make changes easy to review.",
	displayOrder: 0,
	visibleInPracticeDashboards: true,
	autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
	createdAt: new Date("2026-01-01T00:00:00Z"),
};
const standing: PracticeGroupStanding = {
	groupSlug: group.slug,
	groupName: group.name,
	standing: "MIXED",
	observations: [],
	sources: [],
};

const run: PracticeGroupReviewRun = {
	reviewId: "00000000-0000-0000-0000-000000000901",
	reviewedAt: daysBefore(2),
	reviewedWork: { id: "902", kind: "scm.pull_request", label: "#902" },
	observations: [],
};

describe("PracticeGroupDetailPage", () => {
	it("shows the group standing and practices", () => {
		render(
			<PracticeGroupDetailPage
				group={group}
				standing={standing}
				practices={[{ slug: "small-changes", name: "Keep changes focused", standing: "MIXED" }]}
				isLoading={false}
			/>,
		);
		screen.getByRole("heading", { name: group.name });
		expect(screen.getAllByText("Mixed feedback")).toHaveLength(2);
		screen.getByText("Keep changes focused");
		screen.getByText("No review runs");
	});

	it("selects a practice to filter review runs", () => {
		const onSelectPractice = vi.fn();
		render(
			<PracticeGroupDetailPage
				group={group}
				standing={standing}
				practices={[{ slug: "small-changes", name: "Keep changes focused" }]}
				onSelectPractice={onSelectPractice}
				isLoading={false}
			/>,
		);
		fireEvent.click(
			screen.getByRole("button", { name: "Show review runs for Keep changes focused" }),
		);
		expect(onSelectPractice).toHaveBeenCalledWith("small-changes");
	});

	it("tells an empty filtered feed apart from a group that was never reviewed", () => {
		const { rerender } = render(<PracticeGroupDetailPage group={group} isLoading={false} />);
		screen.getByText("Review runs appear here once your work has been reviewed.");

		rerender(
			<PracticeGroupDetailPage
				group={group}
				practices={[{ slug: "small-changes", name: "Keep changes focused" }]}
				selectedPracticeSlug="small-changes"
				isLoading={false}
			/>,
		);
		screen.getByText("No review runs mention Keep changes focused.");
	});

	it("offers a way out when the chosen practice emptied the feed", () => {
		const onSelectPractice = vi.fn();
		render(
			<PracticeGroupDetailPage
				group={group}
				practices={[{ slug: "small-changes", name: "Keep changes focused" }]}
				selectedPracticeSlug="small-changes"
				onSelectPractice={onSelectPractice}
				isLoading={false}
			/>,
		);

		fireEvent.click(screen.getByRole("button", { name: "Show every review in this group" }));
		expect(onSelectPractice).toHaveBeenCalledWith(undefined);
	});

	it("loads earlier reviews without losing the ones already shown", () => {
		const onLoadMore = vi.fn();
		const { rerender } = render(
			<PracticeGroupDetailPage
				group={group}
				feed={{
					status: "ready",
					runs: [],
					hasMore: true,
					isLoadingMore: false,
					onLoadMore,
				}}
				isLoading={false}
			/>,
		);

		expect(screen.queryByRole("button", { name: "View earlier reviews" })).toBeNull();

		rerender(
			<PracticeGroupDetailPage
				group={group}
				feed={{ status: "ready", runs: [run], hasMore: true, isLoadingMore: false, onLoadMore }}
				isLoading={false}
			/>,
		);
		fireEvent.click(screen.getByRole("button", { name: "View earlier reviews" }));
		expect(onLoadMore).toHaveBeenCalledOnce();

		rerender(
			<PracticeGroupDetailPage
				group={group}
				feed={{ status: "ready", runs: [run], hasMore: true, isLoadingMore: true, onLoadMore }}
				isLoading={false}
			/>,
		);
		expect(screen.getByRole("button", { name: "Loading…" }).hasAttribute("disabled")).toBe(true);
	});

	it("offers a retry when the feed itself failed", () => {
		const onRetry = vi.fn();
		render(
			<PracticeGroupDetailPage
				group={group}
				feed={{ status: "error", error: new Error("Gateway timeout"), onRetry }}
				isLoading={false}
			/>,
		);
		fireEvent.click(screen.getByRole("button", { name: /retry/i }));
		expect(onRetry).toHaveBeenCalledOnce();
	});

	it("offers navigation when the group is missing", () => {
		const onBack = vi.fn();
		render(<PracticeGroupDetailPage isLoading={false} onBack={onBack} />);
		fireEvent.click(screen.getByRole("button", { name: "Back to profile" }));
		expect(onBack).toHaveBeenCalledOnce();
	});
});
