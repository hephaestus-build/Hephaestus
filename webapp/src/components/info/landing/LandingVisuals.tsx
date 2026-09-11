import { CircleDot, GitMerge, GitPullRequest, type LucideIcon, Sparkle } from "lucide-react";
import { motion } from "motion/react";
import type { CSSProperties, ReactNode } from "react";
import { useMediaQuery } from "usehooks-ts";
import { getGroupVisual } from "@/components/admin/practice-catalog/group-visuals";
import { HephIcon } from "@/components/brand/HephIcon";
import { OUTCOME_DEFS } from "@/components/practice-vocabulary/outcome-defs";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import styles from "./LandingVisuals.module.css";

type StyleWithCustomProperties = CSSProperties & Record<`--${string}`, string>;

/** Where a cluster sits once its scene scatters; ignored while the scene is a single column. */
export interface LandingPlacement {
	/** 1-based column of the scattered grid, or `full` to span it. */
	column: number | "full";
	row: number;
	/** Static stagger, applied above the row it starts in. */
	offset?: string;
}

export interface LandingGroupVisual {
	color: string;
	icon: string;
}

/** The house decelerate curve, `webapp/AGENTS.md` § Motion. */
export const landingEase = [0.05, 0.7, 0.1, 1] as const;

export function LandingSceneList({ children }: { children: ReactNode }) {
	return <ol className={styles.sceneList}>{children}</ol>;
}

interface LandingClusterProps {
	children: ReactNode;
	placement: LandingPlacement;
	delay: number;
}

export function LandingCluster({ children, placement, delay }: LandingClusterProps) {
	const reduceMotion = useMediaQuery("(prefers-reduced-motion: reduce)");
	const style: StyleWithCustomProperties = {
		"--col": placement.column === "full" ? "1 / -1" : String(placement.column),
		"--row": String(placement.row),
		"--offset": placement.offset ?? "0rem",
	};
	return (
		<motion.li
			className={cn(styles.cluster, placement.column === "full" && styles.clusterFull)}
			style={style}
			initial={reduceMotion ? false : { opacity: 0, y: 22 }}
			whileInView={{ opacity: 1, y: 0 }}
			viewport={{ once: true, amount: 0 }}
			transition={{ duration: 0.5, ease: landingEase, delay }}
		>
			{children}
		</motion.li>
	);
}

export function LandingSpark({ className }: { className?: string }) {
	return <Sparkle className={cn(styles.accent, "fill-current", className)} aria-hidden="true" />;
}

export function LandingGlow({ className }: { className?: string }) {
	return <span className={cn(styles.glow, className)} aria-hidden="true" />;
}

function rotation(degrees: number): StyleWithCustomProperties {
	return { "--rot": `${degrees}deg` };
}

/**
 * Merged is violet by GitHub's convention rather than by a token, the same way `group-visuals.ts`
 * spells its palette out: these are borrowed states, not part of our own colour system.
 */
const WORK_STATES = {
	open: { label: "Open", icon: CircleDot, className: "bg-success text-success-foreground" },
	ready: { label: "Open", icon: GitPullRequest, className: "bg-success text-success-foreground" },
	merged: {
		label: "Merged",
		icon: GitMerge,
		className: "bg-violet-600 text-white dark:bg-violet-500",
	},
} as const;

export type LandingWorkState = keyof typeof WORK_STATES;

export function LandingStatePill({ state }: { state: LandingWorkState }) {
	const { label, icon: Icon, className } = WORK_STATES[state];
	return (
		<Badge className={cn("text-[0.6875rem] font-semibold", className)}>
			<Icon aria-hidden="true" />
			{label}
		</Badge>
	);
}

interface LandingWorkCardProps {
	state?: LandingWorkState;
	reference?: string;
	title: string;
	children?: ReactNode;
	rotate?: number;
}

export function LandingWorkCard({
	state,
	reference,
	title,
	children,
	rotate = 0,
}: LandingWorkCardProps) {
	return (
		<div className={styles.atom} style={rotation(rotate)}>
			<div className="flex flex-col gap-1.5 p-3">
				<div className="flex items-center gap-1.5">
					{state ? <LandingStatePill state={state} /> : undefined}
					{reference ? (
						<span className="font-mono text-[0.6875rem] text-muted-foreground">{reference}</span>
					) : undefined}
				</div>
				<p className="text-sm leading-snug font-semibold text-foreground">{title}</p>
			</div>
			{children}
		</div>
	);
}

/** A line lifted from the work itself: an issue body, a review comment, a status update. */
export function LandingQuote({ children }: { children: ReactNode }) {
	return (
		<p className="border-t border-border/70 bg-muted/45 px-3 py-2 text-[0.8125rem] leading-relaxed text-muted-foreground">
			{children}
		</p>
	);
}

/** The strip of small facts a provider prints under a change: checks, threads, file counts. */
export function LandingMetaRow({ children }: { children: ReactNode }) {
	return (
		<div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 border-t border-border/70 px-3 py-2 text-[0.6875rem]">
			{children}
		</div>
	);
}

const META_TONES = {
	neutral: "text-muted-foreground",
	success: "text-success",
	warning: "text-warning",
} as const;

interface LandingMetaProps {
	icon?: LucideIcon;
	tone?: keyof typeof META_TONES;
	children: ReactNode;
}

export function LandingMeta({ icon: Icon, tone = "neutral", children }: LandingMetaProps) {
	return (
		<span className={cn("inline-flex items-center gap-1 font-medium", META_TONES[tone])}>
			{Icon ? <Icon className="size-3 shrink-0" aria-hidden="true" /> : undefined}
			{children}
		</span>
	);
}

interface LandingFeedbackCardProps {
	group: LandingGroupVisual;
	/** A practice from the shipped catalog, named exactly as it is there. */
	practice: string;
	lead: string;
	/** `strength` is a practice the work does well; `gap` is one it falls short of. */
	stance: "strength" | "gap";
	children?: ReactNode;
	rotate?: number;
}

export function LandingFeedbackCard({
	group,
	practice,
	lead,
	stance,
	children,
	rotate = 0,
}: LandingFeedbackCardProps) {
	const { Icon: GroupIcon, pill } = getGroupVisual(group.icon, group.color);
	const assessment = OUTCOME_DEFS[stance === "strength" ? "POSITIVE" : "NEGATIVE"];
	const StanceIcon = assessment.icon;
	return (
		<div className={cn(styles.atom, styles.slip)} style={rotation(rotate)}>
			<p
				className={cn(
					"flex items-start gap-1.5 rounded-t-[0.9rem] px-3 py-1.5 text-[0.6875rem] font-medium",
					pill,
				)}
			>
				<GroupIcon className="mt-px size-3 shrink-0" aria-hidden="true" />
				{practice}
			</p>
			<div className="flex flex-col gap-2 p-3">
				<p className="flex items-start gap-1.5 text-sm leading-snug font-semibold text-foreground">
					<StanceIcon
						className={cn(
							"mt-0.5 size-3.5 shrink-0",
							stance === "strength" ? "text-success" : "text-destructive",
						)}
						aria-label={assessment.label}
					/>
					{lead}
				</p>
				{children}
			</div>
			<span className="absolute right-2 bottom-1.5 text-mentor opacity-35" aria-hidden="true">
				<HephIcon size={13} pad={3} animated={false} />
			</span>
		</div>
	);
}

interface LandingHephFigureProps {
	className?: string;
	lead: string;
	body: string;
}

export function LandingHephFigure({ className, lead, body }: LandingHephFigureProps) {
	return (
		<div className={cn(styles.hephFigure, className)}>
			<span className={styles.hephAvatar} aria-hidden="true">
				<HephIcon pad={2} animated />
			</span>
			<div className={cn(styles.speechBubble, "text-left text-sm")}>
				<p className="sr-only">Hephaestus says:</p>
				<p className="font-bold tracking-[-0.01em] leading-snug">{lead}</p>
				<p className="mt-1 text-[0.8125rem] leading-relaxed text-muted-foreground">{body}</p>
			</div>
		</div>
	);
}
