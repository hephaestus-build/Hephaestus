import type {
	PracticeGroup,
	PracticeGroupStanding,
	PracticeStanding,
	ReviewedPractice,
} from "@/api/types.gen";
import { workLabel } from "@/feedback/work-kind";

import { STANDING, STANDING_ORDER, type Standing } from "./vocabulary";

/** One practice the workspace reviews, with the developer's standing in it when there is one. */
export interface PracticeEntry {
	slug: string;
	name: string;
	groupSlug: string;
	/** `NOT_OBSERVED` when the server has no standing for it. */
	standing: Standing;
	verdict: PracticeStanding | undefined;
	whyItMatters: string | undefined;
	whatGoodLooksLike: string | undefined;
}

/** One practice group from the workspace's catalog, with the developer's standing and its practices. */
export interface GroupEntry {
	group: PracticeGroup;
	/** `NOT_OBSERVED` when the server has no standing for it. */
	standing: Standing;
	verdict: PracticeGroupStanding | undefined;
	practices: PracticeEntry[];
}

export interface ProfileSources {
	/** The groups the workspace shows in practice dashboards: names, descriptions, icons and order. */
	groups: PracticeGroup[];
	groupStandings: PracticeGroupStanding[];
	/** The practices the workspace reviews: which practices exist at all, and in which group. */
	reviewed: ReviewedPractice[];
	practiceStandings: PracticeStanding[];
}

const rank = (standing: Standing) => STANDING_ORDER.indexOf(standing);

/**
 * The developer's practice profile, as the web's builds it: the catalog decides which groups and
 * practices exist, and the standings say where the developer stands in them. A catalog entry the
 * standings do not mention is "not observed yet", never dropped; a standing for something the catalog
 * does not show is left out. Groups are ordered most in need of attention first, then as the workspace
 * orders them; practices within a group likewise.
 */
export function buildProfile({
	groups,
	groupStandings,
	reviewed,
	practiceStandings,
}: ProfileSources): GroupEntry[] {
	const groupVerdicts = new Map(groupStandings.map((standing) => [standing.groupSlug, standing]));
	const practiceVerdicts = new Map(practiceStandings.map((standing) => [standing.slug, standing]));
	return groups
		.map((group): GroupEntry => {
			const verdict = groupVerdicts.get(group.slug);
			const practices = reviewed
				.filter((practice) => practice.groupSlug === group.slug)
				.map((practice): PracticeEntry => {
					const practiceVerdict = practiceVerdicts.get(practice.slug);
					return {
						slug: practice.slug,
						name: practice.name,
						groupSlug: group.slug,
						standing: practiceVerdict?.standing ?? "NOT_OBSERVED",
						verdict: practiceVerdict,
						whyItMatters: practice.whyItMatters ?? practiceVerdict?.whyItMatters,
						whatGoodLooksLike: practice.whatGoodLooksLike ?? practiceVerdict?.whatGoodLooksLike,
					};
				})
				// Sorting the array `map` just made, in place: Hermes has no `toSorted`. Stable, so practices of
				// equal standing keep the catalog's order.
				.sort((left, right) => rank(left.standing) - rank(right.standing));
			return { group, standing: verdict?.standing ?? "NOT_OBSERVED", verdict, practices };
		})
		.sort(
			(left, right) =>
				rank(left.standing) - rank(right.standing) ||
				left.group.displayOrder - right.group.displayOrder ||
				left.group.name.localeCompare(right.group.name),
		);
}

/** Whether reviewed work has said anything about this group yet. */
export function isObserved(entry: { standing: Standing }): boolean {
	return entry.standing !== "NOT_OBSERVED";
}

/** How many entries stand where, in standing order, leaving out standings nobody has. */
export function tally(entries: { standing: Standing }[]): { standing: Standing; count: number }[] {
	return STANDING_ORDER.map((standing) => ({
		standing,
		count: entries.filter((entry) => entry.standing === standing).length,
	})).filter((part) => part.count > 0);
}

/** "2 going well · 1 needs attention": a tally in running text, in lower case after the first word. */
export function tallyText(entries: { standing: Standing }[]): string {
	return tally(entries)
		.map(({ standing, count }) => `${count} ${STANDING[standing].shortLabel.toLowerCase()}`)
		.join(" · ");
}

/**
 * How many groups no reviewed work has touched yet, as quiet context under where the developer stands:
 * coverage, not a result. Nothing to say when every group has observations, or when none has, which the
 * profile greets on its own.
 */
export function coverageNote(groups: GroupEntry[]): string | undefined {
	const unobserved = groups.filter((entry) => !isObserved(entry)).length;
	if (unobserved === 0 || unobserved === groups.length) {
		return undefined;
	}
	return unobserved === 1
		? "1 more practice group is not observed yet."
		: `${unobserved} more practice groups are not observed yet.`;
}

/**
 * The next step to lead with: the suggested next step of the group most in need of attention that has
 * one. It is the server's own guidance for that group, quoted, never composed here.
 */
export function leadingNextStep(
	groups: GroupEntry[],
): { entry: GroupEntry; guidance: string } | undefined {
	for (const entry of groups) {
		const guidance = entry.verdict?.guidance?.trim() ?? "";
		if (isObserved(entry) && entry.standing !== "NO_OPPORTUNITY" && guidance !== "") {
			return { entry, guidance };
		}
	}
	return undefined;
}

type Provider = Parameters<typeof workLabel>[1];

/** "Based on 9 pull requests and 1 issue": the reviewed work a group's standing rests on. */
export function basedOn(
	sources: PracticeGroupStanding["sources"] | undefined,
	provider: Provider,
): string | undefined {
	const parts = (sources ?? [])
		.filter((source) => source.count > 0)
		.map((source) => `${source.count} ${workLabel(source.workKind, provider, source.count)}`);
	return parts.length === 0 ? undefined : `Based on ${parts.join(" and ")}`;
}

/**
 * What to do next in one practice, as the web's group page says it: the feedback delivered about the
 * latest thing to work on, else what that observation was called when it says more than the practice's
 * own name, else what fits the standing.
 */
export function practiceNextStep(practice: PracticeEntry): string | undefined {
	const first = practice.verdict?.toWorkOn[0];
	const delivered = first?.deliveredFeedback?.trim() ?? "";
	if (delivered !== "") {
		return delivered;
	}
	const title = first?.title.trim() ?? "";
	if (title !== "" && title !== practice.name.trim()) {
		return title;
	}
	if (practice.standing === "STRENGTH" && practice.whatGoodLooksLike !== undefined) {
		return `Keep doing this: ${practice.whatGoodLooksLike}`;
	}
	if (practice.standing === "NO_OPPORTUNITY") {
		return "Nothing to act on yet — the reviews ran and your work offered no occasion for this practice.";
	}
	if (practice.standing === "NOT_OBSERVED") {
		return "No focused next step yet. It will appear after this practice is observed in reviewed work.";
	}
	return practice.whatGoodLooksLike;
}
