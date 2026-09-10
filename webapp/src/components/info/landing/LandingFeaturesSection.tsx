import { FlaskConical } from "lucide-react";
import { motion } from "motion/react";
import { useMediaQuery } from "usehooks-ts";
import { getGroupVisual } from "@/components/admin/practice-catalog/group-visuals";
import { SlackIcon } from "@/components/icons/brand";
import { Badge } from "@/components/ui/badge";
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

/** Groups from the set of practices that ships with Hephaestus, shown to give the range a name. */
const practiceGroups = [
	{ name: "Packaging work for review", color: "sky", icon: "Package" },
	{ name: "Writing issues a maintainer can act on", color: "violet", icon: "FileText" },
	{ name: "Reviewing a teammate's work constructively", color: "teal", icon: "Eye" },
	{ name: "Acting on review feedback", color: "cyan", icon: "MessageSquareReply" },
	{ name: "Testing your changes", color: "amber", icon: "TestTube" },
	{ name: "Handling failure well", color: "rose", icon: "ShieldAlert" },
	{ name: "Making changes secure by default", color: "red", icon: "ShieldCheck" },
	{ name: "Communicating in the open", color: "violet", icon: "MessageCircle" },
];

function DiffLines({ removed, added }: { removed: string[]; added: string[] }) {
	return (
		<div className="flex flex-col border-t border-border/70 font-mono text-[0.6875rem] leading-relaxed">
			{removed.map((line) => (
				<span key={line} className="bg-destructive/10 px-3 text-foreground">
					<span className="text-muted-foreground">−</span> {line}
				</span>
			))}
			{added.map((line) => (
				<span key={line} className="bg-success/10 px-3 text-foreground">
					<span className="text-muted-foreground">+</span> {line}
				</span>
			))}
		</div>
	);
}

export function LandingFeaturesSection() {
	const reduceMotion = useMediaQuery("(prefers-reduced-motion: reduce)");
	return (
		<section
			id="how-it-works"
			aria-labelledby="landing-features-heading"
			className="w-full scroll-mt-20 overflow-x-clip bg-muted/15 py-12 md:py-20"
		>
			<div className="mx-auto grid w-full max-w-6xl gap-10 px-4 md:px-6 lg:grid-cols-[minmax(0,22rem)_1fr] lg:gap-12">
				<div className="lg:col-start-1 lg:row-start-1">
					<Badge className="mb-4" variant="outline">
						Feedback in context
					</Badge>
					<h2
						id="landing-features-heading"
						className="text-3xl font-bold tracking-tight text-balance sm:text-4xl"
					>
						Not only the diff
					</h2>
					<p className="mt-4 text-lg leading-relaxed text-pretty text-muted-foreground">
						A mentor watches how someone works, not just what they shipped. Hephaestus reads the
						code, the conversation around it, and the plan behind it — and authors and reviewers
						both get feedback.
					</p>

					<h3 className="mt-8 text-sm font-semibold">
						Every piece of feedback names the practice it came from
					</h3>
					<ul className="mt-3 flex flex-wrap gap-1.5">
						{practiceGroups.map((group) => {
							const { Icon, pill } = getGroupVisual(group.icon, group.color);
							return (
								<Badge key={group.name} render={<li />} className={pill}>
									<Icon aria-hidden="true" />
									{group.name}
								</Badge>
							);
						})}
					</ul>
					<p className="mt-3 text-sm text-muted-foreground">
						A curated set of practices ships with Hephaestus. Admins adopt the groups their project
						cares about and can rewrite any practice inside them.
					</p>
				</div>

				<figure className={cn(styles.featureScene, "lg:col-start-2 lg:row-span-2 lg:row-start-1")}>
					<figcaption className="sr-only">
						Three more kinds of work Hephaestus reads: a bug fix with no test, a swallowed error in
						the code, and a status update in Slack.
					</figcaption>
					<LandingGlow className={styles.featureGlowA} />
					<LandingGlow className={styles.featureGlowB} />

					<LandingSceneList>
						<LandingCluster placement={{ column: 1, row: 1 }} delay={0}>
							<LandingWorkCard
								state="ready"
								reference="#188"
								title="Fix rounding on invoice totals"
								rotate={-2.5}
							>
								<LandingMetaRow>
									<LandingMeta>1 file changed</LandingMeta>
									<LandingMeta icon={FlaskConical}>no tests</LandingMeta>
								</LandingMetaRow>
							</LandingWorkCard>
							<LandingFeedbackCard
								group={{ color: "amber", icon: "TestTube" }}
								practice="Include tests with the change"
								lead="A rounding bug is exactly what a test pins down."
								stance="gap"
								rotate={2.5}
							/>
						</LandingCluster>

						<LandingCluster placement={{ column: 2, row: 1, offset: "5rem" }} delay={0.08}>
							<LandingWorkCard title="PaymentClient.java" rotate={3}>
								<DiffLines
									removed={["} catch (TimeoutException e) { }"]}
									added={[
										"} catch (TimeoutException e) {",
										"  throw new PaymentUnavailable(e);",
										"}",
									]}
								/>
							</LandingWorkCard>
							<LandingFeedbackCard
								group={{ color: "rose", icon: "ShieldAlert" }}
								practice="Handle errors instead of swallowing them"
								lead="The caller now learns the payment did not go through."
								stance="strength"
								rotate={-2.5}
							/>
						</LandingCluster>

						<LandingCluster placement={{ column: 1, row: 2 }} delay={0.16}>
							<LandingWorkCard title="#team-payments" rotate={2}>
								<LandingQuote>
									<span className="inline-flex items-center gap-1.5">
										<SlackIcon className="size-3 shrink-0" aria-hidden="true" />
										<span className="font-medium text-foreground">daily update</span>
									</span>
									<br />
									“Still working on the invoice thing.”
								</LandingQuote>
							</LandingWorkCard>
							<LandingFeedbackCard
								group={{ color: "violet", icon: "MessageCircle" }}
								practice="Post clear status and blocker updates"
								lead="Nobody can tell whether you are blocked."
								stance="gap"
								rotate={-3}
							/>
						</LandingCluster>
					</LandingSceneList>
				</figure>

				<motion.div
					initial={reduceMotion ? false : { opacity: 0, scale: 0.94 }}
					whileInView={{ opacity: 1, scale: 1 }}
					viewport={{ once: true, amount: 0.5 }}
					transition={{ duration: 0.5, ease: landingEase, delay: 0.1 }}
					className={cn(styles.featureHeph, "mt-10 lg:col-start-1 lg:row-start-2")}
				>
					<LandingHephFigure
						lead="Not sure which one to fix first?"
						body="Tell me where the work is stuck and we can work out the next move together."
					/>
				</motion.div>
			</div>
		</section>
	);
}
