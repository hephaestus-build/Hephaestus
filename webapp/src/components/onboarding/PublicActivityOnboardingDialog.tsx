import { useRef } from "react";

import {
	AlertDialog,
	AlertDialogAction,
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
	answer: PublicActivityAnswer;
	onAnswer: (visible: boolean) => void;
}

/**
 * The step that tells a person a workspace publishes its activity, and what the page holds of
 * them. Both answers are the same size and neither is focused, so neither is the default. Only an
 * answer closes it: a person who has not chosen is asked again next time.
 */
export function PublicActivityOnboardingDialog({
	open,
	workspaceName,
	providerType,
	answer,
	onAnswer,
}: PublicActivityOnboardingDialogProps) {
	const titleRef = useRef<HTMLHeadingElement>(null);
	const pullRequests = artifactKindNoun(ARTIFACT_KIND.pullRequest, 2, providerType);
	const repositories = getProviderTerms(providerType).repositories.toLowerCase();
	const saving = answer.status === "saving";
	return (
		<AlertDialog open={open}>
			<AlertDialogContent initialFocus={titleRef}>
				<AlertDialogHeader>
					<AlertDialogTitle ref={titleRef} tabIndex={-1}>
						{workspaceName} has a public activity page
					</AlertDialogTitle>
					<AlertDialogDescription>
						Anyone can see it, without signing in. For public {repositories} only, it shows:
					</AlertDialogDescription>
				</AlertDialogHeader>
				<ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground">
					<li>your name, your picture and a link to your profile</li>
					<li>the {pullRequests} you opened and reviewed, and the issues you opened, as numbers</li>
					<li>a weekly line of your activity</li>
				</ul>
				<p className="text-sm text-muted-foreground">
					Your choice covers every public activity page. You can change it at any time in User
					settings.
				</p>
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
