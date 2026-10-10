import { describe, expect, it } from "vitest";

import { PEOPLE, publicActivityOf } from "@/stories/activity-story-data";

import { publicPeopleRows } from "./activity-people-rows";
import { competitionPositions } from "./people-positions";

describe("publicPeopleRows", () => {
	const rows = publicPeopleRows(publicActivityOf(PEOPLE));

	it("names a person by login and links the name to the provider's page", () => {
		const [ada] = rows.people;
		expect(ada?.person).toStrictEqual({
			id: "ada",
			login: "ada",
			name: "Ada Lovelace",
			avatarUrl: "",
			htmlUrl: "https://github.com/ada",
		});
	});

	it("marks a first contribution by the same login", () => {
		expect(rows.highlights.firstContributors).toStrictEqual(["elodie"]);
	});

	it("positions people by the same count the members' table sorts by", () => {
		const positions = competitionPositions(rows.people, "contributions");
		expect(positions.get("ada")).toBe(1);
		// Bob and Dana tie on contributions, so they share a position and the next one skips.
		expect(positions.get("bob")).toBe(2);
		expect(positions.get("dana")).toBe(2);
		expect(positions.get("chen")).toBe(4);
	});
});
