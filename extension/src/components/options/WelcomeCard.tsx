import { ShieldCheckIcon } from "lucide-react";
import { useId } from "react";

import { HephMark } from "~/components/brand/HephaestusLogo";
import { Button } from "~/components/common/Button";
import { Disclosure } from "~/components/common/Disclosure";
import { ExternalLink } from "~/components/common/ExternalLink";
import { Spinner } from "~/components/common/Spinner";
import { InstanceSetupForm } from "~/components/options/InstanceSetupForm";

export type ConnectTarget = "hosted" | "custom";

export type ConnectState =
	| { status: "idle" }
	| { status: "pending"; target: ConnectTarget }
	| { status: "error"; target: ConnectTarget; message: string };

export interface WelcomeCardProps {
	/** The hosted service offered first, as its host: `hephaestus.build`. */
	hostedHost: string;
	/** Adds the local web app address to the self-hosted form. */
	developmentBuild: boolean;
	state: ConnectState;
	/** Called from the click itself, so the handler can still ask Chrome for access. */
	onConnectHosted: () => void;
	onConnectCustom: (input: { origin: string; webAppOrigin?: string }) => void;
	/** The extension's data handling notice; `docsOrigin` is the one origin it may point to. */
	privacyUrl: string;
	docsOrigin: string;
}

/**
 * The first thing a new user sees: what the extension is for, and one obvious way to start with the
 * hosted service. A self-hosted instance is the same flow behind a disclosure. Choosing only connects:
 * Chrome asks for the one address, and signing in is the next, separate step.
 */
export function WelcomeCard({
	hostedHost,
	developmentBuild,
	state,
	onConnectHosted,
	onConnectCustom,
	privacyUrl,
	docsOrigin,
}: WelcomeCardProps) {
	const titleId = useId();
	const hostedErrorId = useId();
	const pending = state.status === "pending" ? state.target : undefined;
	const hostedError =
		state.status === "error" && state.target === "hosted" ? state.message : undefined;
	return (
		<section
			aria-labelledby={titleId}
			className="flex flex-col gap-6 rounded-2xl border border-border bg-card p-6 text-card-foreground shadow-xs sm:p-8"
		>
			<div className="flex flex-col gap-3">
				<HephMark className="size-12" />
				<h2 id={titleId} className="text-2xl font-semibold tracking-display">
					Practice reviews, in context.
				</h2>
				<p className="max-w-prose text-base text-muted-foreground">
					See the practice review of the pull request, merge request or issue you open on GitHub or
					GitLab in the page, right after its description: the comments Hephaestus posted for you,
					with the way to each, and what the review concluded about your work.
				</p>
			</div>
			<div className="flex flex-col gap-2">
				<div className="flex flex-wrap items-center gap-x-3 gap-y-2">
					<Button
						variant="mentor"
						size="lg"
						disabled={pending !== undefined}
						aria-describedby={hostedError === undefined ? undefined : hostedErrorId}
						onClick={onConnectHosted}
					>
						{pending === "hosted" ? <Spinner /> : null}
						{pending === "hosted" ? "Connecting…" : "Continue with Hephaestus"}
					</Button>
					<span className="text-sm text-muted-foreground">{hostedHost}</span>
				</div>
				<p className="text-xs text-muted-foreground">
					Chrome asks you to let the extension reach {hostedHost}. You sign in next.
				</p>
				{hostedError === undefined ? null : (
					<p id={hostedErrorId} className="text-sm text-destructive" role="alert">
						{hostedError}
					</p>
				)}
			</div>
			<div className="flex gap-2.5 rounded-lg bg-muted/60 p-3.5 text-sm text-muted-foreground">
				<ShieldCheckIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-mentor" />
				<p>
					On the sites you allow, the extension looks up each pull request, merge request or issue
					as you open it, and a list row only when you press its Hephaestus button, by sending its
					address to your Hephaestus. It never sends the page&apos;s content.{" "}
					<ExternalLink href={privacyUrl} allowedOrigin={docsOrigin}>
						How the extension handles data
					</ExternalLink>
				</p>
			</div>
			<Disclosure
				summary="Use a self-hosted instance"
				defaultOpen={state.status === "error" && state.target === "custom"}
				className="border-t border-border pt-4"
			>
				<div className="flex flex-col gap-3">
					<p className="text-sm text-muted-foreground">
						If your organisation runs its own Hephaestus, connect to it instead. You can switch at
						any time.
					</p>
					<InstanceSetupForm
						developmentBuild={developmentBuild}
						state={
							state.status === "error" && state.target === "custom"
								? { status: "error", message: state.message }
								: { status: pending === "custom" ? "pending" : "idle" }
						}
						disabled={pending === "hosted"}
						onConnect={onConnectCustom}
					/>
				</div>
			</Disclosure>
		</section>
	);
}
