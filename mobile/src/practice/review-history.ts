import type {
	ReviewedWorkRef,
	ObservationDetail,
	PracticeGroupReviewRun,
	PracticeGroupReviewRunsPage,
} from "@/api/types.gen";
import { workLabel } from "@/feedback/work-kind";

import { OUTCOME, UNASSESSED } from "./vocabulary";

/** The runs loaded so far, in the order the server returned them: newest first. */
export function loadedRuns(
	data: { pages: PracticeGroupReviewRunsPage[] } | undefined,
): PracticeGroupReviewRun[] {
	return (data?.pages ?? []).flatMap((page) => page.content);
}

type Provider = Parameters<typeof workLabel>[1];

function capitalised(text: string): string {
	return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * The reviewed work as its host names it: its title, and "Pull request #12 · api" under it. A
 * document's label is its title, which the heading already says, so it is not repeated under it.
 */
export function workHeading(
	work: ReviewedWorkRef,
	provider: Provider,
): { title: string; detail: string } {
	const kind = capitalised(workLabel(work.kind, work.provider ?? provider));
	const title = work.title?.trim();
	const label = work.label.trim();
	const numbered = label === "" || label === title ? kind : `${kind} ${label}`;
	return {
		title: title !== undefined && title !== "" && title !== kind ? title : numbered,
		detail: [numbered, work.repositoryName]
			.filter((part) => part !== undefined && part !== "")
			.join(" · "),
	};
}

/** What one observation found, in words: its outcome when it was assessed, else why it was not. */
export function observationResult(
	observation: Pick<ObservationDetail, "assessmentStatus" | "outcome">,
): { label: string; positive: boolean | undefined } {
	if (observation.assessmentStatus !== "ASSESSED") {
		return { label: UNASSESSED[observation.assessmentStatus].label, positive: undefined };
	}
	if (observation.outcome === undefined) {
		return { label: "Assessed", positive: undefined };
	}
	return {
		label: OUTCOME[observation.outcome].label,
		positive: observation.outcome === "POSITIVE",
	};
}

/** A web address the app opens: only one on https, so a record never opens something unexpected. */
export function openableUrl(url: string | undefined): string | undefined {
	if (url === undefined) {
		return undefined;
	}
	try {
		return new URL(url).protocol === "https:" ? url : undefined;
	} catch {
		return undefined;
	}
}
