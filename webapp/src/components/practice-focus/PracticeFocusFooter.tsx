import { ArrowRightIcon } from "lucide-react";
import type { ComponentProps } from "react";

import { cn } from "cn";

import { ActionButton } from "@/components/common/ActionButton";
import { DrawerFooter } from "@/components/ui/drawer";

export interface PracticeFocusFooterProps {
	/** What the other page shows of the same group or practice, in one sentence. */
	sentence: string;
	/** The button's words: where it goes. */
	label: string;
	/** The button's accessible name, which names the group or practice it goes to. */
	accessibleLabel: string;
	onSwitch: () => void;
	/**
	 * Where it stands: the foot of a drawer level, or the end of a page that has no level open (the
	 * workspace page's list of every group).
	 */
	placement?: "drawer" | "page";
}

/**
 * The foot of a group or practice level on either practice page, with the one way to the same
 * group or practice on the other page. It sits in the same place on both pages, so the way there is
 * also the way back. A muted band with an outline button that turns dark on hover, focus and press,
 * so the way across is seen, not searched for, and stays quieter than the level's content.
 */
export function PracticeFocusFooter({
	sentence,
	label,
	accessibleLabel,
	onSwitch,
	placement = "drawer",
}: PracticeFocusFooterProps) {
	const Foot = placement === "drawer" ? DrawerFoot : PageFoot;
	return (
		<Foot className="sm:items-center sm:justify-between">
			<p className="text-sm text-muted-foreground">{sentence}</p>
			{/* The action button of Reviews of your work, with words, at the width of the footer on a
			    phone, where the footer stacks: the one way to the other page is easy to see and to hit. */}
			<ActionButton className="w-full sm:w-auto" aria-label={accessibleLabel} onClick={onSwitch}>
				{label}
				<ArrowRightIcon aria-hidden />
			</ActionButton>
		</Foot>
	);
}

/** The drawer's own foot, on its gray band. */
function DrawerFoot(props: ComponentProps<"div">) {
	return <DrawerFooter tone="band" {...props} />;
}

/**
 * The page's own foot, laid out as a drawer's: the sentence, then the button, stacked on a phone. The
 * same gray band, bordered all round, as the page has no edge for a top rule to separate it from.
 */
function PageFoot({ className, ...props }: ComponentProps<"div">) {
	return (
		<div
			data-slot="page-focus-footer"
			className={cn("flex flex-col gap-2 rounded-lg border bg-sidebar p-4 sm:flex-row", className)}
			{...props}
		/>
	);
}

/**
 * The button's accessible name on either page: its words, which it starts with so a voice command
 * that says them reaches it, then the group or practice it goes to.
 */
export const focusSwitchName = (label: string, name: string | undefined): string =>
	name === undefined || name === "" ? label : `${label}: ${name}`;

/** The words of the footer on each page, in one place so both pages stay in step. */
export const FOCUS_FOOTER_COPY = {
	profileAllGroups: {
		sentence:
			"How the developers in this workspace split in every practice group. Nobody is named.",
		label: "See all groups across the workspace",
	},
	profileGroup: {
		sentence: "How the developers in this workspace split in this group. Nobody is named.",
		label: "See this group across the workspace",
	},
	profilePractice: {
		sentence: "How the developers in this workspace split on this practice. Nobody is named.",
		label: "See this practice across the workspace",
	},
	workspaceGroup: {
		sentence: "Your own standing, feedback and observations in this group.",
		label: "See your own standing",
	},
	workspaceAllGroups: {
		sentence: "Your own standing and feedback in every practice group.",
		label: "See your own standing",
	},
} as const;
