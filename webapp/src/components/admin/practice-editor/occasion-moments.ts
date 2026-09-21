import {
	ArchiveIcon,
	BookOpenCheckIcon,
	CircleCheckIcon,
	CircleDashedIcon,
	CircleDotIcon,
	EyeIcon,
	FilePenLineIcon,
	GitCommitHorizontalIcon,
	GitMergeIcon,
	GitPullRequestArrowIcon,
	GitPullRequestClosedIcon,
	type LucideIcon,
	MessageSquareTextIcon,
	MessagesSquareIcon,
} from "lucide-react";

import type { PracticeSignalOption, PracticeWorkTypeDefinitionOptions } from "@/api/types.gen";

/**
 * Where a moment sits in the life of one piece of work.
 *
 * Bands rather than one ordered line, because a work type's moments are not a queue: those in the
 * middle repeat in no fixed order, and those at the end are alternatives to each other.
 */
export type MomentPhase = "start" | "during" | "end";

export const PHASE_LABEL: Record<MomentPhase, string> = {
	start: "Starts",
	during: "Along the way",
	end: "Ends",
};

/**
 * What this build knows about one moment beyond what the wire carries. Deliberately no `label`:
 * `PracticeSignalOption.displayName` is authoritative, and a second copy would be a second truth.
 */
export interface MomentDef {
	/** Distinct within a work type, so the strip reads in greyscale (WCAG 2.2 SC 1.4.1). */
	icon: LucideIcon;
	phase: MomentPhase;
	/**
	 * True where the moment happens repeatedly in one piece of work, which makes binding it a
	 * decision about volume the strip has to state up front.
	 */
	repeats: boolean;
}

/**
 * Signal ids are an open vocabulary — `SignalName` reaches the client as a bare `string` — so a
 * domain may declare a moment this build has never met. Hence the fallback rather than a total map.
 */
const MOMENTS: Record<string, MomentDef> = {
	"scm.pull_request.opened": { icon: GitPullRequestArrowIcon, phase: "start", repeats: false },
	"scm.pull_request.ready": { icon: EyeIcon, phase: "during", repeats: false },
	"scm.pull_request.synchronized": {
		icon: GitCommitHorizontalIcon,
		phase: "during",
		repeats: true,
	},
	"scm.pull_request.reviewed": { icon: MessageSquareTextIcon, phase: "during", repeats: true },
	"scm.pull_request.merged": { icon: GitMergeIcon, phase: "end", repeats: false },
	"scm.pull_request.closed": { icon: GitPullRequestClosedIcon, phase: "end", repeats: false },

	"scm.issue.opened": { icon: CircleDotIcon, phase: "start", repeats: false },
	"scm.issue.updated": { icon: FilePenLineIcon, phase: "during", repeats: true },
	"scm.issue.closed": { icon: CircleCheckIcon, phase: "end", repeats: false },

	"chat.conversation_thread.settled": { icon: MessagesSquareIcon, phase: "end", repeats: false },

	"docs.document.published": { icon: BookOpenCheckIcon, phase: "start", repeats: false },
	"docs.document.updated": { icon: FilePenLineIcon, phase: "during", repeats: true },
	"docs.document.archived": { icon: ArchiveIcon, phase: "end", repeats: false },
};

const UNKNOWN_MOMENT: MomentDef = { icon: CircleDashedIcon, phase: "during", repeats: false };

export function momentDef(signal: string): MomentDef {
	return MOMENTS[signal] ?? UNKNOWN_MOMENT;
}

/**
 * Moments a practice is already bound to that its work type no longer offers: a review asked for by
 * hand, saved while that still counted as an occasion, or a signal a later build withdrew. Drawn
 * rather than dropped — the form refuses to save while one is chosen, and a moment nobody can see is
 * a moment nobody can untick.
 *
 * A withdrawn moment has no `displayName` on the wire, so it is named by the hand-asked review where
 * it is that, and by its bare id otherwise, which is at least something to search for.
 */
export function withdrawnMoments(
	workType: PracticeWorkTypeDefinitionOptions,
	selected: readonly string[],
): PracticeSignalOption[] {
	const offered = new Set(workType.signals.map((option) => option.signal));
	const manual = workType.manualReviewSignal;
	return selected
		.filter((signal) => !offered.has(signal))
		.map((signal) => ({
			signal,
			displayName: manual?.signal === signal ? manual.displayName : signal,
			recommended: false,
		}));
}

export interface MomentBand {
	phase: MomentPhase;
	moments: PracticeSignalOption[];
}

const PHASE_ORDER: MomentPhase[] = ["start", "during", "end"];

/**
 * The work type's moments grouped into bands, keeping the server's order within each. Empty bands
 * are dropped, so one strip serves every work type without a special case at the call site.
 */
export function momentBands(signals: readonly PracticeSignalOption[]): MomentBand[] {
	return PHASE_ORDER.map((phase) => ({
		phase,
		moments: signals.filter((option) => momentDef(option.signal).phase === phase),
	})).filter((band) => band.moments.length > 0);
}
