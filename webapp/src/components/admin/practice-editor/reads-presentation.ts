import {
	CircleDashedIcon,
	FileSearchIcon,
	HistoryIcon,
	type LucideIcon,
	NetworkIcon,
} from "lucide-react";

import type { ReadSourceOption } from "@/api/types.gen";
import { evidenceSourceDef } from "@/components/practice-vocabulary/evidence-source-defs";

export function readSourceLabel(source: string, offered: readonly ReadSourceOption[]): string {
	return (
		offered.find((option) => option.source === source)?.label ?? evidenceSourceDef(source).label
	);
}

export function canReadComplete(option: ReadSourceOption): boolean {
	return option.needs.includes("COMPLETE");
}

export type ReadSourceFamily = "item" | "around" | "history" | "other";

export interface ReadSourceFamilyDef {
	label: string;
	icon: LucideIcon;
}

export const READ_SOURCE_FAMILY: Record<ReadSourceFamily, ReadSourceFamilyDef> = {
	item: { label: "The work itself", icon: FileSearchIcon },
	around: { label: "Around the work", icon: NetworkIcon },
	history: { label: "This developer’s history", icon: HistoryIcon },
	other: { label: "Other sources", icon: CircleDashedIcon },
};

/**
 * Keys are wire ids and never reach an operator; every word on screen comes from the source's own
 * label. A source this build has not been taught falls to "Other sources" rather than being filed
 * under a heading that would be a guess about what it is.
 */
const SOURCE_FAMILIES: Record<string, ReadSourceFamily> = {
	"scm.pull-request.core": "item",
	"scm.pull-request.checks": "item",
	"scm.pull-request.diff": "item",
	"scm.pull-request.comments": "item",
	"scm.review-threads": "item",
	"scm.general-review-comments": "item",
	"scm.issue.core": "item",
	"scm.issue.comments": "item",
	"slack.conversation.thread": "item",
	"docs.document.core": "item",
	"scm.repository.tree": "around",
	"scm.linked-work-items": "around",
	"workspace.project-inventory": "around",
	"outline.documents": "around",
	"hephaestus.result-history": "history",
	"hephaestus.feedback-history": "history",
};

export interface ReadSourceGroup {
	family: ReadSourceFamily;
	def: ReadSourceFamilyDef;
	sources: ReadSourceOption[];
}

const FAMILY_ORDER: ReadSourceFamily[] = ["item", "around", "history", "other"];

export function groupReadSources(sources: readonly ReadSourceOption[]): ReadSourceGroup[] {
	return FAMILY_ORDER.map((family) => ({
		family,
		def: READ_SOURCE_FAMILY[family],
		sources: sources.filter((option) => (SOURCE_FAMILIES[option.source] ?? "other") === family),
	})).filter((group) => group.sources.length > 0);
}
