import { RefreshCwIcon, ScrollTextIcon } from "lucide-react";

import { Button } from "~/components/common/Button";
import { ExternalLink } from "~/components/common/ExternalLink";
import { Spinner } from "~/components/common/Spinner";

export interface ConsentPromptProps {
	instanceHost: string;
	webAppOrigin: string;
	checking: boolean;
	onCheckAgain: () => void;
}

/**
 * Signed in, but the account has a step to finish in the web app — accepting the current notice.
 * The extension cannot do it on the reader's behalf; it sends them there and checks again after.
 */
export function ConsentPrompt({
	instanceHost,
	webAppOrigin,
	checking,
	onCheckAgain,
}: ConsentPromptProps) {
	return (
		<section
			aria-label="One step left in Hephaestus"
			className="flex gap-3 rounded-lg border border-warning/30 bg-warning/5 p-4"
		>
			<ScrollTextIcon aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
			<div className="flex min-w-0 flex-col gap-2">
				<h2 className="text-sm font-semibold">One step left in Hephaestus</h2>
				<p className="text-sm text-muted-foreground">
					Your account on {instanceHost} needs you to read and accept its current notice before the
					extension can show anything. Open Hephaestus, accept it there, then come back.
				</p>
				<div className="flex flex-wrap items-center gap-2 pt-1">
					<ExternalLink
						href={webAppOrigin}
						allowedOrigin={webAppOrigin}
						button={{ variant: "mentor" }}
					>
						Open Hephaestus
					</ExternalLink>
					<Button variant="outline" disabled={checking} onClick={onCheckAgain}>
						{checking ? <Spinner /> : <RefreshCwIcon aria-hidden />}
						{checking ? "Checking…" : "I have accepted it"}
					</Button>
				</div>
			</div>
		</section>
	);
}
