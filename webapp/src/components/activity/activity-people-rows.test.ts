import { describe, expect, it } from "vitest";

import { PEOPLE, publicActivityOf } from "@/stories/activity-story-data";

import { publicPeopleRows } from "./activity-people-rows";

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
});
