import { CircleCheck, MessageSquare, Square } from "lucide-react";
import { motion, stagger, useReducedMotion } from "motion/react";
import { LandingSignInCta } from "@/components/auth/LandingSignInCta";
import { GithubIcon, GitlabIcon } from "@/components/icons/brand";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
	LandingCluster,
	LandingFeedbackCard,
	LandingGlow,
	LandingHephFigure,
	landingEase,
	LandingMeta,
	LandingMetaRow,
	LandingQuote,
	LandingSceneList,
	LandingWorkCard,
} from "./LandingVisuals";
import styles from "./LandingVisuals.module.css";

interface LandingHeroSectionProps {
	onSignIn: (idpHint: string) => void;
	onGoToDashboard?: () => void;
	isSignedIn: boolean;
}

const itemVariants = {
	hidden: { opacity: 0, y: 16 },
	visible: { opacity: 1, y: 0, transition: { duration: 0.6, ease: landingEase } },
};

/** Groups from the shipped catalog, so each chip wears the colour and icon it has in the product. */
const groups = {
	issues: { color: "violet", icon: "FileText" },
	packaging: { color: "sky", icon: "Package" },
	reviewing: { color: "teal", icon: "Eye" },
	responding: { color: "cyan", icon: "MessageSquareReply" },
};

function Diffstat({ added, removed }: { added: number; removed: number }) {
	const greenSquares = Math.max(1, Math.round((added / (added + removed)) * 5));
	return (
		<span className="inline-flex items-center gap-1.5 font-mono">
			{/* Fixed locale: the scene is captured as a committed image, and a grouping separator
			    that follows the capturing machine's locale would rewrite that image in CI. */}
			<span className="text-success">+{added.toLocaleString("en")}</span>
			<span className="text-destructive">−{removed.toLocaleString("en")}</span>
			<span className="flex gap-px" aria-hidden="true">
				{[0, 1, 2, 3, 4].map((index) => (
					<span
						key={index}
						className={cn(
							"size-2 rounded-[2px]",
							index < greenSquares ? "bg-success" : "bg-destructive",
						)}
					/>
				))}
			</span>
		</span>
	);
}

function ReviewComment({
	author,
	initials,
	children,
}: {
	author: string;
	initials: string;
	children: string;
}) {
	return (
		<div className="flex flex-col gap-1.5 px-3 pb-3">
			<span className="flex items-center gap-1.5 text-[0.6875rem] text-muted-foreground">
				<span
					className="flex size-4 items-center justify-center rounded-full bg-muted text-[0.5rem] font-semibold text-foreground"
					aria-hidden="true"
				>
					{initials}
				</span>
				<span className="font-medium text-foreground">{author}</span>
				commented 2 days ago
			</span>
			<p
				className={cn(
					styles.commentBubble,
					"rounded-lg border border-border bg-muted/40 px-2.5 py-1.5 text-[0.8125rem] leading-relaxed text-foreground",
				)}
			>
				{children}
			</p>
		</div>
	);
}

/**
 * The story the hero tells, without the copy beside it. Exported so the asset pipeline can
 * capture it on its own: a screenshot of the whole hero repeats its headline next to whatever
 * heading the page already has.
 */
export function HeroScene() {
	return (
		<div className={styles.heroScene}>
			<LandingGlow className={styles.heroGlowLeft} />
			<LandingGlow className={styles.heroGlowRight} />
			<LandingGlow className={styles.heroGlowBottom} />

			<LandingSceneList>
				<LandingCluster placement={{ column: 1, row: 1 }} delay={0}>
					<LandingWorkCard state="open" reference="#412" title="Export reports to CSV" rotate={-3}>
						<LandingQuote>“Users want to download their reports.”</LandingQuote>
					</LandingWorkCard>
					<LandingFeedbackCard
						group={groups.issues}
						practice="Define a checkable outcome"
						lead="No acceptance criteria."
						stance="gap"
						rotate={2.5}
					>
						<ul className="flex flex-col gap-1">
							{["Which columns?", "How many rows?", "Who may export?"].map((item) => (
								<li key={item} className="flex items-center gap-1.5 text-[0.8125rem]">
									<Square className="size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
									{item}
								</li>
							))}
						</ul>
					</LandingFeedbackCard>
				</LandingCluster>

				<LandingCluster placement={{ column: 1, row: 2, offset: "1rem" }} delay={0.08}>
					<LandingWorkCard state="ready" reference="#412" title="Add CSV export" rotate={3}>
						<ul className="flex flex-col gap-0.5 border-t border-border/70 px-3 py-2">
							{["export/CsvWriter.ts", "billing/InvoiceService.ts", "config/application.yaml"].map(
								(path) => (
									<li
										key={path}
										className="truncate font-mono text-[0.6875rem] text-muted-foreground"
									>
										{path}
									</li>
								),
							)}
						</ul>
						<LandingMetaRow>
							<Diffstat added={1240} removed={380} />
							<LandingMeta>34 files</LandingMeta>
						</LandingMetaRow>
					</LandingWorkCard>
					<LandingFeedbackCard
						group={groups.packaging}
						practice="Scope the change to one concern"
						lead="The invoice rename does not belong in a CSV export."
						stance="gap"
						rotate={-2.5}
					/>
				</LandingCluster>

				<LandingCluster placement={{ column: 3, row: 1, offset: "1rem" }} delay={0.16}>
					<LandingWorkCard title="Review on “Add CSV export”" rotate={3}>
						<ReviewComment author="maria-k" initials="MK">
							Does this hold up past 10k rows? A test would settle it.
						</ReviewComment>
					</LandingWorkCard>
					<LandingFeedbackCard
						group={groups.reviewing}
						practice="Leave specific, actionable review comments"
						lead="Names the doubt and what would settle it."
						stance="strength"
						rotate={-2.5}
					/>
				</LandingCluster>

				<LandingCluster placement={{ column: 3, row: 2, offset: "1rem" }} delay={0.24}>
					<LandingWorkCard state="merged" title="Add CSV export" rotate={-2.5}>
						<LandingMetaRow>
							<LandingMeta icon={CircleCheck} tone="success">
								12 checks passed
							</LandingMeta>
							<LandingMeta icon={MessageSquare} tone="warning">
								1 unresolved
							</LandingMeta>
						</LandingMetaRow>
					</LandingWorkCard>
					<LandingFeedbackCard
						group={groups.responding}
						practice="Resolve open threads before merging"
						lead="The 10k-row question never got an answer."
						stance="gap"
						rotate={2.5}
					/>
				</LandingCluster>

				<LandingCluster placement={{ column: "full", row: 3 }} delay={0.32}>
					<LandingHephFigure
						className={styles.heroHeph}
						lead="Start with #412, not the pull request."
						body="Write the acceptance criteria down and the scope stops moving. The review gets short."
					/>
				</LandingCluster>
			</LandingSceneList>
		</div>
	);
}

export function LandingHeroSection({
	onSignIn,
	onGoToDashboard,
	isSignedIn,
}: LandingHeroSectionProps) {
	const reduceMotion = useReducedMotion();
	return (
		<section
			aria-labelledby="landing-hero-heading"
			className="w-full overflow-x-clip bg-background py-12 text-foreground sm:py-16 lg:py-20"
		>
			<motion.div
				initial={reduceMotion ? false : "hidden"}
				animate="visible"
				variants={{ hidden: {}, visible: { transition: { delayChildren: stagger(0.09) } } }}
				className={cn(
					styles.heroCanvas,
					"relative mx-auto flex w-full max-w-7xl flex-col items-center px-4 text-center md:px-6",
				)}
			>
				<motion.div className={cn(styles.heroCopy, "flex flex-col items-center")}>
					<Badge
						render={<motion.p variants={itemVariants} />}
						variant="outline"
						className="relative z-10 h-auto border-mentor/20 bg-mentor/5 px-3 py-1.5 text-sm whitespace-normal text-mentor"
					>
						Open-source AI mentoring for software teams
					</Badge>

					<motion.h1
						variants={itemVariants}
						id="landing-hero-heading"
						className="relative z-10 mt-5 max-w-3xl text-4xl font-bold tracking-[-0.04em] text-balance sm:text-5xl md:text-6xl"
					>
						Learn from the work you're{" "}
						<span className="relative inline-block whitespace-nowrap">
							already doing
							<span
								className={cn(styles.headlineMark, "absolute -right-1 -bottom-1 -left-1 -z-10")}
								aria-hidden="true"
							/>
						</span>
					</motion.h1>

					<motion.p
						variants={itemVariants}
						className="relative z-10 mt-6 max-w-xl text-lg leading-relaxed text-pretty sm:text-xl"
					>
						<span className="font-semibold text-foreground">
							The mentoring feedback a senior would give.
						</span>{" "}
						<span className="text-muted-foreground">
							For everyone, not only the people they have time for.
						</span>
					</motion.p>

					<motion.div
						variants={itemVariants}
						data-readme-actions
						className="relative z-10 mt-8 flex w-full flex-col items-center gap-3 sm:w-auto sm:flex-row"
					>
						<LandingSignInCta
							isSignedIn={isSignedIn}
							onSignIn={onSignIn}
							onGoToDashboard={onGoToDashboard}
							size="lg"
							className="h-11 w-full px-5 shadow-lg shadow-primary/10 sm:w-auto"
						/>
						<a
							href="https://github.com/hephaestus-build/Hephaestus"
							target="_blank"
							rel="noopener noreferrer"
							className={cn(
								buttonVariants({ variant: "outline", size: "lg" }),
								"h-11 w-full gap-2 px-5 sm:w-auto",
							)}
						>
							<GithubIcon className="size-4" aria-hidden="true" />
							View on GitHub
							<span className="sr-only">(opens in a new tab)</span>
						</a>
					</motion.div>

					<motion.p
						variants={itemVariants}
						className="relative z-10 mt-5 flex flex-wrap items-center justify-center gap-2 text-sm text-muted-foreground"
					>
						<span>Works with</span>
						<Badge variant="outline" className="h-auto gap-1.5 bg-muted/40 px-2.5 py-1 text-sm">
							<GithubIcon aria-hidden="true" />
							GitHub
						</Badge>
						<Badge variant="outline" className="h-auto gap-1.5 bg-muted/40 px-2.5 py-1 text-sm">
							<GitlabIcon aria-hidden="true" />
							GitLab
						</Badge>
					</motion.p>
				</motion.div>

				<figure className={cn(styles.heroFigure, "relative w-full")}>
					<HeroScene />
					<figcaption className="sr-only">
						One change through a project. The issue #412 has no acceptance criteria, so the pull
						request grew to 34 files and picked up an unrelated rename; a reviewer asks a good
						question that never gets answered before the merge. Hephaestus points back to the issue
						as the place to start.
					</figcaption>
				</figure>
			</motion.div>
		</section>
	);
}
