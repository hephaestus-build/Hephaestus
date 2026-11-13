import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { ProfileReviewDetail } from "@/api/types.gen";
import { InLevelStack } from "@/stories/level-stack";
import { openProfileReviewDetail } from "@/stories/profile-reviews-story-mock-data";
import { ProfileReviewLevel } from "./ProfileReviewLevel";

afterEach(cleanup);

const providers = new Map<string, ProfileReviewDetail["review"]["item"]["provider"]>([
	["docs.document", "OUTLINE"],
	["chat.message", "SLACK"],
	["scm.pull_request", "GITLAB"],
]);

function reviewOf(
	kind: string,
	label: string,
	url: string | null,
	title: string | null = "Architecture notes",
) {
	const detail = {
		...openProfileReviewDetail,
		results: [],
		practiceOutcomes: { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 },
		review: {
			...openProfileReviewDetail.review,
			item: {
				...openProfileReviewDetail.review.item,
				kind,
				provider: providers.get(kind),
				container:
					kind === "scm.pull_request" ? openProfileReviewDetail.review.item.container : undefined,
				label,
				// The wire leaves out a missing title or link.
				title: title ?? undefined,
				url: url ?? undefined,
			},
		},
	} satisfies ProfileReviewDetail;
	render(
		<InLevelStack
			entry={{ kind: "review", id: detail.review.id }}
			path={{ behind: [], onClose: vi.fn() }}
		>
			{(level) => (
				<ProfileReviewLevel
					{...level}
					state={{ status: "ready", detail }}
					groupOf={() => undefined}
					onOpenPractice={vi.fn()}
					filters={{}}
					onFiltersChange={vi.fn()}
				/>
			)}
		</InLevelStack>,
	);
	return screen.getByRole("heading", { level: 2 });
}

it.each(["docs.document", "chat.message"])(
	"keeps a %s link when the work has no provider number",
	(kind) => {
		const heading = reviewOf(kind, "Architecture notes", "https://example.com/architecture");
		screen.getByRole("dialog", { name: "Architecture notes" });
		expect(
			within(heading)
				.getByRole("link", { name: /^Architecture notes/u })
				.getAttribute("href"),
		).toBe("https://example.com/architecture");
	},
);

it("keeps unlinked work as plain text", () => {
	expect(
		within(reviewOf("docs.document", "Architecture notes", null)).queryByRole("link"),
	).toBeNull();
});

it("keeps the provider number as the link for numbered work", () => {
	const heading = reviewOf("scm.pull_request", "!12", "https://example.com/merge_requests/12");
	expect(within(heading).getByRole("link", { name: /^!12/u }).getAttribute("href")).toBe(
		"https://example.com/merge_requests/12",
	);
});

it("names untitled numbered work without the link's new-tab instruction", () => {
	reviewOf("scm.pull_request", "!12", "https://example.com/merge_requests/12", null);
	screen.getByRole("dialog", { name: "Merge request !12" });
});
