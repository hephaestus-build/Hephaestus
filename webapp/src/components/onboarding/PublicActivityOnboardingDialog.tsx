import { useRef } from "react";

import {
	AlertDialog,
	AlertDialogAction,
	AlertDialogCancel,
	AlertDialogContent,
	AlertDialogDescription,
	AlertDialogFooter,
	AlertDialogHeader,
	AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Spinner } from "@/components/ui/spinner";
import { ARTIFACT_KIND, artifactKindNoun } from "@/lib/artifact-kinds";
import { getProviderTerms, type ProviderType } from "@/lib/provider/provider-terms";

/** The answer in flight, or the reason the last one failed. */
export type PublicActivityAnswer =
	| { status: "idle" }
	| { status: "saving"; visible: boolean }
	| { status: "error"; message: string };

export interface PublicActivityOnboardingDialogProps {
	/** Open until the account has answered for this workspace. */
	open: boolean;
	workspaceName: string;
	providerType: ProviderType;
	/** Whether public pages show the account right now, as it answered in another workspace or by default. */
	currentlyVisible: boolean;
	answer: PublicActivityAnswer;
	onAnswer: (visible: boolean) => void;
	/** Leaves the step until the next visit: Escape and Decide later, which a save in flight does not hold back. */
	onDefer: () => void;
}

/**
 * The step that tells a person a workspace publishes its activity, and what the page holds of
 * them. Both answers are the same size and neither is focused, so neither is the default. Escape
 * and Decide later leave it for this visit, and a person who has not chosen is asked again next time.
 */
export function PublicActivityOnboardingDialog({
	open,
	workspaceName,
	providerType,
	currentlyVisible,
	answer,
	onAnswer,
	onDefer,
}: PublicActivityOnboardingDialogProps) {
	const popupRef = useRef<HTMLDivElement>(null);
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	const repositories = getProviderTerms(providerType).repositories.toLowerCase();
	const saving = answer.status === "saving";
	return (
		<AlertDialog
			open={open}
			onOpenChange={(next) => {
				if (!next) {
					onDefer();
				}
			}}
		>
			{/* Focus starts on the dialog, announced with its title and text, not on either answer. */}
			<AlertDialogContent ref={popupRef} initialFocus={popupRef}>
				<AlertDialogHeader className="place-items-start text-left">
					<AlertDialogTitle>{workspaceName} has a public activity page</AlertDialogTitle>
					{/* One description, so a screen reader hears all of what is shown, not its first line. */}
					<AlertDialogDescription render={<div />}>
						<div className="space-y-3">
							<p>
								Anyone can see it, without signing in. For public {repositories} only, it shows:
							</p>
							<ul className="list-disc space-y-1 pl-5">
								<li>your name, your picture and a link to your profile</li>
								<li>
									the {pullRequests} you opened and reviewed, and the issues you opened, as numbers
								</li>
								<li>a weekly line of your activity</li>
							</ul>
							<p>
								Your choice covers every public activity page, and you can change it at any time in
								User settings. Right now, public pages {currentlyVisible ? "show" : "hide"} you.
							</p>
						</div>
					</AlertDialogDescription>
				</AlertDialogHeader>
				{answer.status === "error" && (
					<p role="alert" className="text-sm text-destructive">
						{answer.message}
					</p>
				)}
				<AlertDialogFooter className="grid grid-cols-2 sm:flex-none">
					<ChoiceButton visible answer={answer} disabled={saving} onAnswer={onAnswer}>
						Show me
					</ChoiceButton>
					<ChoiceButton visible={false} answer={answer} disabled={saving} onAnswer={onAnswer}>
						Hide me
					</ChoiceButton>
					<AlertDialogCancel variant="ghost" className="col-span-2">
						Decide later
					</AlertDialogCancel>
				</AlertDialogFooter>
			</AlertDialogContent>
		</AlertDialog>
	);
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
		<AlertDialogAction
			variant="outline"
			disabled={disabled}
			focusableWhenDisabled
			onClick={() => onAnswer(visible)}
		>
			{pending && <Spinner />}
			{children}
		</AlertDialogAction>
	);
}
