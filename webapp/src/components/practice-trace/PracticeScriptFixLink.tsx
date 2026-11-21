import { Link } from "@tanstack/react-router";
import { ArrowRightIcon } from "lucide-react";

import type { PracticeEditedIn } from "@/api/types.gen";
import { practiceSetupLevelFor } from "@/components/admin/practices/practice-search";
import { DocLink } from "@/components/common/DocLink";
import { InlineLink } from "@/components/common/InlineLink";
import { detailSearch } from "@/components/layout/detail-drawer/detail-stack";
import {
	PRACTICE_SCRIPT_GUIDE_DEFS,
	type PracticeScriptFix,
} from "@/components/practice-vocabulary/practice-script-run-status-defs";

export interface PracticeScriptFixLinkProps {
	workspaceSlug: string;
	practiceSlug: string;
	/**
	 * The practice's name, for a screen reader, where one list repeats the same link for several
	 * practices. Absent where the link sits in the practice's own sentence.
	 */
	practiceName?: string;
	/** A practice edited in the practice repository has its script in its folder there, not an editor here. */
	editedIn: PracticeEditedIn;
	fix: PracticeScriptFix;
}

export function PracticeScriptFixLink({
	workspaceSlug,
	practiceSlug,
	practiceName,
	editedIn,
	fix,
}: PracticeScriptFixLinkProps) {
	// Place context after the visible label so speech control can match it (WCAG 2.5.3).
	const context =
		practiceName === undefined ? null : (
			<>
				{" "}
				<span className="sr-only">for {practiceName}</span>
			</>
		);
	if (fix.kind === "GUIDE") {
		return (
			<DocLink doc={PRACTICE_SCRIPT_GUIDE_DEFS[fix.guide].doc} className="font-medium">
				{PRACTICE_SCRIPT_GUIDE_DEFS[fix.guide].label}
				{context}
			</DocLink>
		);
	}
	return (
		<InlineLink
			render={
				<Link
					to="/w/$workspaceSlug/admin/practices"
					params={{ workspaceSlug }}
					search={detailSearch(practiceSetupLevelFor(practiceSlug, editedIn))}
				/>
			}
			className="inline-flex items-center gap-1 font-medium"
		>
			{editedIn === "REPOSITORY" ? "Open in Practice setup" : "Edit the script"}
			{context}
			<ArrowRightIcon className="size-3 shrink-0" aria-hidden />
		</InlineLink>
	);
}
