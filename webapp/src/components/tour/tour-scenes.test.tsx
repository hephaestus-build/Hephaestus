import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { practiceStandings } from "@/stories/practice-profile-story-mock-data";

import { EXAMPLE_WORK } from "./tour-example";
import {
	DestinationsScene,
	FlowOverviewScene,
	GroupStandingScene,
	LevelsScene,
	ObservationsScene,
	PatternScene,
	PracticesScene,
	ProfileScene,
	PROFILE_PARTS,
	practiceNames,
	STANDING_DEFS,
	Stations,
	StandingsScene,
	TOUR_STATIONS,
	TREND_DEFS,
} from "./tour-scenes";

/** The reader's motion setting, as the scenes read it through `prefers-reduced-motion`. */
function prefersReducedMotion(reduce: boolean) {
	const original = window.matchMedia.bind(window);
	vi.spyOn(window, "matchMedia").mockImplementation((query: string) =>
		Object.assign(original(query), {
			matches: reduce && query.includes("prefers-reduced-motion: reduce"),
		}),
	);
}

const PICKED = {
	name: "Small pull requests",
	whyItMatters: "Small changes are easier to review.",
	whatGoodLooksLike: "One concern per pull request.",
};

afterEach(() => {
	vi.restoreAllMocks();
	vi.useRealTimers();
});

describe("the tour's scenes", () => {
	it("show their final state at once under reduced motion, so nothing waits for an animation", () => {
		prefersReducedMotion(true);
		render(<PracticesScene names={["Clear titles", PICKED.name]} picked={PICKED} />);
		// The picked practice and its words are there from the first render.
		screen.getByText("Small changes are easier to review.");
		screen.getByText("One concern per pull request.");

		render(<LevelsScene levels={["Practice profile", "All practice groups", "Testing"]} />);
		screen.getByText("A press on the page beside the panels closed the last one too.");
	});

	it("play their beats in order with motion, and the reader sees the picked practice only after it is picked", () => {
		prefersReducedMotion(false);
		vi.useFakeTimers();
		render(<PracticesScene names={["Clear titles", PICKED.name]} picked={PICKED} />);
		expect(screen.queryByText("Small changes are easier to review.")).toBeNull();
		act(() => {
			vi.advanceTimersByTime(2200);
		});
		screen.getByText("Small changes are easier to review.");
	});

	it("walk the levels: open one by one, then back by the path, then closed beside them", () => {
		prefersReducedMotion(false);
		vi.useFakeTimers();
		render(
			<LevelsScene levels={["Practice profile", "All practice groups", "Testing", "Review"]} />,
		);
		screen.getByText("1 level open");
		act(() => {
			vi.advanceTimersByTime(2700);
		});
		screen.getByText("4 levels open");
		act(() => {
			vi.advanceTimersByTime(2200);
		});
		screen.getByText(
			"A press on “All practice groups” in the path closed the two panels above it.",
		);
	});

	it("name each station from the work to the profile, in the words of the step", () => {
		prefersReducedMotion(true);
		render(<FlowOverviewScene noun="merge request" />);
		for (const { label } of TOUR_STATIONS) {
			screen.getByText(label);
		}
		screen.getByText("You open a merge request, as usual.");
	});

	it("show the parts of the chain that the later steps explain, in the provider's own words", () => {
		prefersReducedMotion(true);
		render(
			<>
				<ObservationsScene provider="GITLAB" />
				<PatternScene provider="GITHUB" />
				<ProfileScene />
				<StandingsScene />
			</>,
		);
		// GitLab names a merge request with "!", GitHub a pull request with "#".
		screen.getByText(`!${EXAMPLE_WORK.number} ${EXAMPLE_WORK.title}`);
		screen.getByText("#17");
		screen.getByText("#19");
		for (const [title] of PROFILE_PARTS) {
			screen.getByText(title);
		}
		// The standings scene reads the same registries as the page: every standing and trend label.
		for (const def of [...STANDING_DEFS, ...TREND_DEFS]) {
			expect(screen.getAllByText(def.label).length).toBeGreaterThan(0);
		}
	});

	it("say where feedback goes, and that a conversation answers only when asked", () => {
		prefersReducedMotion(true);
		render(<DestinationsScene noun="pull request" />);
		screen.getByText("About this one piece of work.");
		screen.getByText("Only when it fits what you ask.");
	});

	it("read a group's standing from its practices' own", () => {
		prefersReducedMotion(true);
		const practices = practiceStandings.slice(0, 3);
		render(<GroupStandingScene groupName="Testing" practices={practices} standing="DEVELOPING" />);
		for (const practice of practices) {
			screen.getByText(practice.name);
		}
		screen.getByText("Each practice: its own standing");
	});

	it("say only the current station in words, beside the others' marks", () => {
		const { container } = render(<Stations current={2} />);
		const named = [...container.querySelectorAll("li span span:not(.sr-only)")].map(
			(span) => span.textContent,
		);
		expect(named).toStrictEqual(["Observations"]);
	});

	it("list a practice once even where it sits in two groups", () => {
		// A practice listed under a second group adds no second name.
		const twice = [
			...practiceStandings,
			...practiceStandings.map((p) => ({ ...p, groupSlug: "another" })),
		];
		expect(practiceNames(twice)).toStrictEqual(practiceNames(practiceStandings));
		expect(practiceNames(practiceStandings).length).toBeGreaterThan(1);
	});
});
