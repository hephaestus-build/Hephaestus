import { ArrowRight, BookOpen } from "lucide-react";
import { LandingSignInCta } from "@/components/auth/LandingSignInCta";
import { buttonVariants } from "@/components/ui/button";
import { LandingGlow, LandingHephFigure, LandingSpark } from "./LandingVisuals";
import styles from "./LandingVisuals.module.css";

interface LandingCtaSectionProps {
	onSignIn: (idpHint: string) => void;
}

export function LandingCtaSection({ onSignIn }: LandingCtaSectionProps) {
	return (
		<section
			aria-labelledby="landing-cta-heading"
			className="w-full border-t border-border bg-[linear-gradient(180deg,color-mix(in_oklab,var(--color-mentor)_9%,transparent)_0%,transparent_38%)] py-12 [background-size:100%_24rem] [background-repeat:no-repeat] md:py-20"
		>
			<div className="mx-auto grid w-full max-w-6xl items-center gap-8 px-4 md:px-6 lg:grid-cols-[0.85fr_1.15fr] lg:gap-12">
				<div className="mx-auto lg:mx-0">
					<div className={styles.ctaScene}>
						<LandingGlow className={styles.ctaGlow} />
						<LandingHephFigure
							lead="Ask me about the feedback on your last change."
							body="I can say why it matters, or hear the context I did not have when I wrote it."
						/>
						<LandingSpark className={styles.ctaSparkA} />
						<LandingSpark className={styles.ctaSparkB} />
					</div>
				</div>
				<div className="text-center lg:text-left">
					<h2
						id="landing-cta-heading"
						className="text-3xl font-bold tracking-tight text-balance text-foreground md:text-5xl"
					>
						Start with the work in front of you
					</h2>
					<p className="mx-auto mt-4 max-w-2xl text-lg leading-relaxed text-muted-foreground lg:mx-0">
						Sign in to see feedback on the work you are already doing, and ask it anything about it.
					</p>
					<div className="mt-6 flex w-full flex-col justify-center gap-3 sm:flex-row lg:justify-start">
						<LandingSignInCta onSignIn={onSignIn} size="lg" className="w-full sm:w-auto" />
						<a
							href="https://docs.hephaestus.build/user/overview"
							target="_blank"
							rel="noopener noreferrer"
							className={buttonVariants({ size: "lg", variant: "outline" })}
						>
							<BookOpen aria-hidden="true" />
							<span>Read the user guide</span>
							<span className="sr-only">(opens in a new tab)</span>
							<ArrowRight aria-hidden="true" />
						</a>
					</div>
					<p className="mt-5 text-sm text-muted-foreground">
						Running your own deployment?{" "}
						<a
							className="font-medium text-foreground underline underline-offset-4"
							href="https://docs.hephaestus.build/admin/install"
							target="_blank"
							rel="noopener noreferrer"
						>
							Read the installation guide
							<span className="sr-only"> (opens in a new tab)</span>
						</a>
						.
					</p>
				</div>
			</div>
		</section>
	);
}
