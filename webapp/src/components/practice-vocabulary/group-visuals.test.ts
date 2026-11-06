import { Folder, Rocket } from "lucide-react";
import { describe, expect, it } from "vitest";

import { ICON_NAMES, iconLabel, iconSearchText, pickedGroupIcon } from "./group-icons";
import { getGroupVisual, groupInk, PILL, UNASSIGNED_GROUP_VISUAL } from "./group-visuals";
import { PracticeGroupIcon } from "./level-icons";

describe("groupVisuals", () => {
	it("draws every group with the one group icon and keeps the admin-set color", () => {
		const visual = getGroupVisual("Rocket", "fuchsia");
		expect(visual.Icon).toBe(PracticeGroupIcon);
		expect(visual.pill).toBe(PILL.fuchsia);
	});

	it("keeps the admin-set icon for the picker's preview", () => {
		expect(pickedGroupIcon("Rocket")).toBe(Rocket);
		expect(pickedGroupIcon("NotAnIcon")).toBe(Folder);
	});

	it("uses a neutral fallback for missing or unknown values", () => {
		const visual = getGroupVisual("NotAnIcon", "chartreuse");
		expect(visual.Icon).toBe(Folder);
		expect(visual.pill).toBe(PILL.slate);
	});

	it.each([
		["GitBranch", "git branch"],
		["ShieldX", "shield x"],
		["Code2", "code2"],
		["MessageSquareReply", "message square reply"],
	])("splits the PascalCase icon name %s into searchable %s", (name, expected) => {
		expect(iconSearchText(name)).toBe(expected);
	});

	it("turns icon identifiers into human labels", () => {
		expect(iconLabel("MessageSquareReply")).toBe("Message square reply");
	});

	it("matches icons by a word fragment of their split name", () => {
		const hits = ICON_NAMES.filter((n) => iconSearchText(n).includes("git"));
		expect(hits).toStrictEqual(expect.arrayContaining(["GitBranch", "GitPullRequest", "GitMerge"]));
		expect(hits).not.toContain("ShieldX");
	});
});

describe("groupInk", () => {
	it("keeps every color's text and drops its ground, in both themes", () => {
		for (const pill of [...Object.values(PILL), UNASSIGNED_GROUP_VISUAL.pill]) {
			const ink = groupInk(pill).split(" ");
			expect(ink.some((name) => /(?:^|:)bg-/u.test(name))).toBe(false);
			expect(ink.filter((name) => /(?:^|:)text-/u.test(name))).toStrictEqual(
				pill.split(" ").filter((name) => /(?:^|:)text-/u.test(name)),
			);
		}
	});
});
