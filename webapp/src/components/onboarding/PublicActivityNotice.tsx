import { GlobeIcon } from "lucide-react";
import { useId, useLayoutEffect, useRef } from "react";

import { cn } from "cn";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Collapsible, CollapsibleContent } from "@/components/ui/collapsible";
import { Spinner } from "@/components/ui/spinner";
import { ITEM_KIND, itemKindNoun } from "@/lib/item-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

/** The answer in flight, or the reason the last one failed. */
export type PublicActivityAnswer =
	| { status: "idle" }
	| { status: "saving"; visible: boolean }
	| { status: "error"; message: string };

export interface PublicActivityNoticeProps {
	/** Open until the account has answered for this workspace, or chose Decide later on this visit. */
	open: boolean;
	workspaceName: string;
	providerType: ProviderType;
	/** Whether public pages show the account right now, as the server holds its answer. */
	currentlyVisible: boolean;
	answer: PublicActivityAnswer;
	onAnswer: (visible: boolean) => void;
	/** Decide later: leaves the notice for this visit, which a save in flight does not hold back. */
	onDefer: () => void;
	/** Spacing around the notice. It sits inside the closing panel, so it leaves with the notice. */
	className?: string;
}

/**
 * Tells a member that the workspace publishes its activity, and what the page holds of them. It is
 * a region in the page, not a dialog: it never takes focus when the page loads. Both answers have
 * the same weight, and neither is the default.
 *
 * The notice that arrives with the page does not animate in. After an answer it closes in 200 ms,
 * and at once under reduced motion. Focus that was inside it moves to the page heading, so it is
 * not lost with the buttons.
 */
export function PublicActivityNotice({
	open,
	workspaceName,
	providerType,
	currentlyVisible,
	answer,
	onAnswer,
	onDefer,
	className,
}: PublicActivityNoticeProps) {
	const titleId = useId();
	const root = useRef<HTMLDivElement>(null);
	const pullRequests = itemKindNoun(ITEM_KIND.pullRequest, 2, providerType);
	const repositories = getProviderTerms(providerType).repositories.toLowerCase();
	const saving = answer.status === "saving";

	// A layout effect: without a close transition, the panel unmounts in the same commit, and the
	// focus inside it would already be gone when a passive effect runs.
	useLayoutEffect(() => {
		const node = root.current;
		if (open || node === null || !node.contains(document.activeElement)) {
			return;
		}
		pageHeading(node)?.focus();
	}, [open]);

	return (
		<Collapsible ref={root} open={open} className="shrink-0">
			<CollapsibleContent
				// oxlint-disable-next-line shadcn/no-arbitrary-values -- The standard exit curve of webapp/DESIGN.md § Motion, which no theme token holds; the drawer exit uses it too.
				className="h-(--collapsible-panel-height) overflow-hidden transition-[height] duration-200 ease-[cubic-bezier(0.2,0,0.38,0.9)] data-ending-style:h-0 data-starting-style:h-0 motion-reduce:transition-none"
			>
				<div className={cn("mx-auto w-full max-w-6xl", className)}>
					<Alert role="region" aria-labelledby={titleId}>
						<GlobeIcon aria-hidden />
						<AlertTitle id={titleId} className="min-w-0 break-words">
							{workspaceName} has a public activity page
						</AlertTitle>
						<AlertDescription className="min-w-0 space-y-3 break-words">
							<p>
								Anyone can see it, without signing in, and search engines can list it if the
								workspace allows that. For public {repositories} only, it shows:
							</p>
							<ul className="list-disc space-y-1 pl-5">
								<li>your name, your picture and a link to your profile</li>
								<li>
									counts of the {pullRequests} you opened and reviewed, and the issues you opened
								</li>
								<li>
									your public work timeline, with titles and links, and weekly activity counts
								</li>
							</ul>
							<p>
								Your choice covers every public activity page, and you can change it at any time in
								User settings. Right now, public pages {currentlyVisible ? "show" : "hide"} you.
							</p>
							{answer.status === "error" && (
								<p role="alert" className="text-destructive">
									{answer.message}
								</p>
							)}
							<div className="flex flex-wrap gap-2">
								<ChoiceButton visible answer={answer} disabled={saving} onAnswer={onAnswer}>
									Show me
								</ChoiceButton>
								<ChoiceButton visible={false} answer={answer} disabled={saving} onAnswer={onAnswer}>
									Hide me
								</ChoiceButton>
								<Button type="button" variant="ghost" size="sm" onClick={onDefer}>
									Decide later
								</Button>
							</div>
						</AlertDescription>
					</Alert>
				</div>
			</CollapsibleContent>
		</Collapsible>
	);
}

/** The page's own heading, or its main region where it has none. */
function pageHeading(from: HTMLElement): HTMLElement | null {
	const main = from.closest("main") ?? document.getElementById("main-content");
	const heading = main?.querySelector("h1");
	if (heading) {
		// A heading takes focus only from a script, and keeps out of the tab order.
		if (!heading.hasAttribute("tabindex")) {
			heading.tabIndex = -1;
		}
		return heading;
	}
	return main ?? null;
}

function ChoiceButton({
	visible,
	answer,
	disabled,
	onAnswer,
	children,
}: {
	visible: boolean;
	answer: PublicActivityAnswer;
	disabled: boolean;
	onAnswer: (visible: boolean) => void;
	children: string;
}) {
	const pending = answer.status === "saving" && answer.visible === visible;
	return (
		<Button
			type="button"
			variant="outline"
			size="sm"
			disabled={disabled}
			focusableWhenDisabled
			onClick={() => onAnswer(visible)}
		>
			{pending && <Spinner />}
			{children}
		</Button>
	);
}
