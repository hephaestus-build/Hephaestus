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
	guidance: string;
	variant: "destructive" | "warning";
	retryable: boolean;
}

function classifyError(status: number | undefined): ErrorClass {
	if (status == null) {
		return {
			icon: <AlertCircleIcon />,
			guidance: "Check your connection, then try again.",
			variant: "destructive",
			retryable: true,
		};
	}
	if (status === 401) {
		return {
			icon: <LockIcon />,
			guidance: "Your session has expired. Sign in again to continue.",
			variant: "warning",
			retryable: false,
		};
	}
	if (status === 403) {
		return {
			icon: <LockIcon />,
			guidance: "You don't have permission to view this. Ask an admin for access.",
			variant: "destructive",
			retryable: false,
		};
	}
	if (status === 404) {
		return {
			icon: <SearchXIcon />,
			guidance: "It may have been deleted or moved. Reload the page to see the current state.",
			variant: "destructive",
			retryable: false,
		};
	}
	if (status === 409) {
		return {
			icon: <InfoIcon />,
			guidance: "Something else changed this first. Reload the page to see the current state.",
			variant: "warning",
			retryable: false,
		};
	}
	if (status === 429) {
		return {
			icon: <InfoIcon />,
			guidance: "Too many requests for now. Wait a moment, then try again.",
			variant: "warning",
			retryable: true,
		};
	}
	if (status >= 500) {
		return {
			icon: <AlertCircleIcon />,
			guidance: "Something went wrong on our side. Trying again usually helps.",
			variant: "destructive",
			retryable: true,
		};
	}
	return {
		icon: <AlertCircleIcon />,
		guidance: "The request wasn't accepted. Reload the page and try again.",
		variant: "destructive",
		retryable: false,
	};
}

function describe(detail: string, guidance: string): string {
	const lead = detail.trim();
	if (lead.length === 0 || lead === guidance) {
		return guidance;
	}
	return /[.!?]$/u.test(lead) ? `${lead} ${guidance}` : `${lead}. ${guidance}`;
}

export function QueryErrorAlert({ error, title, onRetry, className }: QueryErrorAlertProps) {
	const status = problemStatusOf(error);
	const { icon, guidance, variant, retryable } = classifyError(status);
	const detail = problemDetailOf(error, guidance);
	const showRetry = onRetry != null && retryable;

	return (
		<Alert variant={variant} className={className}>
			{icon}
			<AlertTitle className="min-w-0 break-words">{title}</AlertTitle>
			<AlertDescription className="min-w-0 break-words">
				{describe(detail, guidance)}
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
