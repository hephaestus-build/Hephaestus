import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { PracticeGroup, PracticeGroupStanding } from "@/api/types.gen";
import { PracticeGroupStandingCard } from "./PracticeGroupStandingCard";

const group: PracticeGroup = {
	id: 1,
	slug: "review-ready-work",
	name: "Packaging work for review",
	displayOrder: 0,
	visibleInPracticeDashboards: true,
	autonomy: { effective: "AUTOMATIC", inherited: true, source: "WORKSPACE" },
	createdAt: new Date("2026-01-01T00:00:00Z"),
};
const standing: PracticeGroupStanding = {
	groupSlug: group.slug,
	groupName: group.name,
	standing: "NO_OPPORTUNITY",
	observations: [],
	sources: [],
};

describe("PracticeGroupStandingCard", () => {
	it("shows the server-derived standing", () => {
		render(
			<PracticeGroupStandingCard
				groups={[group]}
				standings={{ [group.slug]: standing }}
				isLoading={false}
			/>,
		);
		screen.getByText("Nothing to report yet");
		screen.getByText(/nothing here could be judged/iu);
	});

	it("opens a group", () => {
		const onOpenDetails = vi.fn<(group: PracticeGroup) => void>();
		render(
			<PracticeGroupStandingCard
				groups={[group]}
				standings={{ [group.slug]: standing }}
				isLoading={false}
				onOpenDetails={onOpenDetails}
			/>,
		);
		fireEvent.click(screen.getByRole("button", { name: `See details about ${group.name}` }));
		expect(onOpenDetails).toHaveBeenCalledWith(group);
	});

	it("puts the groups that need attention first", () => {
		const groups = ["going-well", "needs-attention", "mixed"].map((slug, index) => ({
			...group,
			id: index + 1,
			slug,
			name: slug,
		}));
		render(
			<PracticeGroupStandingCard
				groups={groups}
				standings={{
					"going-well": { ...standing, groupSlug: "going-well", standing: "STRENGTH" },
					"needs-attention": {
						...standing,
						groupSlug: "needs-attention",
						standing: "DEVELOPING",
					},
					mixed: { ...standing, groupSlug: "mixed", standing: "MIXED" },
				}}
				isLoading={false}
			/>,
		);

		const headings = screen.getAllByRole("heading", { level: 3 }).map((node) => node.textContent);
		expect(headings).toStrictEqual(["needs-attention", "mixed", "going-well"]);
	});

	it("collapses past three groups and says how many are hidden", () => {
		const groups = Array.from({ length: 5 }, (_, index) => ({
			...group,
			id: index + 1,
			slug: `group-${index}`,
			name: `Group ${index}`,
		}));
		render(<PracticeGroupStandingCard groups={groups} standings={{}} isLoading={false} />);

		expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(3);
		const toggle = screen.getByRole("button", { name: "Show all 5 practice groups" });
		expect(toggle.getAttribute("aria-expanded")).toBe("false");

		fireEvent.click(toggle);
		expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(5);
		screen.getByRole("button", { name: "Show fewer groups" });
	});

	it("shows an empty workspace state", () => {
		render(<PracticeGroupStandingCard groups={[]} standings={{}} isLoading={false} />);
		screen.getByText("No practice groups are configured yet.");
	});
});
