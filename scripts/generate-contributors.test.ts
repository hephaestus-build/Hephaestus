import assert from "node:assert/strict";
import { test } from "node:test";

import { contributors } from "./generate-contributors.ts";

const listed = (login: string, contributions: number, type = "User") => ({
	id: contributions,
	login,
	type,
	avatar_url: `https://avatars.example.com/${login}`,
	html_url: `https://github.com/${login}`,
	contributions,
});

void test("lists people only, most contributions first, each by profile name or login", () => {
	assert.deepEqual(
		contributors(
			[
				listed("ada", 3),
				listed("renovate[bot]", 90, "Bot"),
				listed("semantic-release-bot", 50),
				listed("grace", 7),
			],
			new Map([["grace", "Grace Hopper"]]),
		).map(({ login, name }) => ({ login, name })),
		[
			{ login: "grace", name: "Grace Hopper" },
			{ login: "ada", name: "ada" },
		],
	);
});

void test("refuses a list without a person, so a release never ships an empty list", () => {
	assert.throws(
		() => contributors([listed("renovate[bot]", 90, "Bot")], new Map()),
		/no contributors/u,
	);
});
