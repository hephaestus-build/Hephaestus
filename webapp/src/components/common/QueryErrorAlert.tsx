import { AlertCircleIcon, InfoIcon, LockIcon, SearchXIcon } from "lucide-react";
import type * as React from "react";

import { Alert, AlertAction, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { problemDetailOf, problemStatusOf } from "@/lib/problem-detail";

export interface QueryErrorAlertProps {
	error: unknown;
	title: string;
	onRetry?: () => void;
	className?: string;
}

interface ErrorClass {
	icon: React.ReactNode;
	/** What happened, used when the server gave no wording of its own. */
	cause?: string;
	/** What the reader does next. */
	next: string;
	variant: "destructive" | "warning";
	retryable: boolean;
	/** Re-words the caller's "We could not load X" title when the failure is not a load failure. */
	retitle?: (what: string) => string;
	/** The status already says everything, so the server's wording is not shown. */
	ignoreDetail?: boolean;
}

function classifyError(status: number | undefined): ErrorClass {
	if (status == null) {
		return {
			icon: <AlertCircleIcon />,
			next: "Check your connection, then try again.",
			variant: "destructive",
			retryable: true,
		};
	}
	if (status === 401) {
		return {
			icon: <LockIcon />,
			next: "Your session has expired. Sign in again to continue.",
			variant: "warning",
			retryable: false,
			ignoreDetail: true,
		};
	}
	if (status === 403) {
		return {
			icon: <LockIcon />,
			next: "Ask a workspace admin or instance admin for access.",
			variant: "destructive",
			retryable: false,
			retitle: (what) => `You do not have access to ${what}`,
		};
	}
	if (status === 404) {
		return {
			icon: <SearchXIcon />,
			next: "It may have been deleted or moved. Go back to continue.",
			variant: "destructive",
			retryable: false,
			retitle: (what) => `We could not find ${what}`,
		};
	}
	if (status === 409) {
		return {
			icon: <InfoIcon />,
			next: "Something else changed this first. Reload the page to see the current state.",
			variant: "warning",
			retryable: false,
		};
	}
	if (status === 429) {
		return {
			icon: <InfoIcon />,
			cause: "Too many requests.",
			next: "Wait a moment, then try again.",
			variant: "warning",
			retryable: true,
		};
	}
	if (status >= 500) {
		return {
			icon: <AlertCircleIcon />,
			cause: "The server had a problem.",
			next: "Try again in a moment.",
			variant: "destructive",
			retryable: true,
		};
	}
	return {
		icon: <AlertCircleIcon />,
		cause: "The request was not accepted.",
		next: "Reload the page and try again.",
		variant: "destructive",
		retryable: false,
	};
}

const LOAD_TITLE = /^We could not load (?<what>.+)$/u;

function describe(lead: string, next: string): string {
	if (lead.length === 0 || lead === next) {
		return next;
	}
	// A lead that already says what to do must not be followed by a second instruction.
	if (/\btry again\b/iu.test(lead)) {
		return lead;
	}
	return /[.!?]$/u.test(lead) ? `${lead} ${next}` : `${lead}. ${next}`;
}

export function QueryErrorAlert({ error, title, onRetry, className }: QueryErrorAlertProps) {
	const status = problemStatusOf(error);
	const { icon, cause, next, variant, retryable, retitle, ignoreDetail } = classifyError(status);
	const detail = ignoreDetail === true ? "" : problemDetailOf(error, "").trim();
	const showRetry = onRetry != null && retryable;
	const what = LOAD_TITLE.exec(title)?.groups?.what;
	const shownTitle = what != null && retitle != null ? retitle(what) : title;

	return (
		<Alert variant={variant} className={className}>
			{icon}
			<AlertTitle className="min-w-0 break-words">{shownTitle}</AlertTitle>
			<AlertDescription className="min-w-0 break-words">
				{describe(detail || (cause ?? ""), next)}
			</AlertDescription>
			{showRetry && (
				<AlertAction>
					<Button type="button" variant="outline" size="sm" onClick={onRetry}>
						Retry
					</Button>
				</AlertAction>
			)}
		</Alert>
	);
}
