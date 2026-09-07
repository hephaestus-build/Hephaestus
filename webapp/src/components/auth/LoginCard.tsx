import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

import { SignInButtons, type SignInButtonsProps } from "@/components/auth/SignInButtons";
import { SignInNotice } from "@/components/auth/SignInNotice";
import { HephaestusLogo } from "@/components/brand/HephaestusLogo";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader } from "@/components/ui/card";

// Never display raw OAuth error parameters; they can contain provider details.
const ERROR_COPY: Record<string, { title: string; description: string }> = {
	access_denied: {
		title: "Sign-in was cancelled",
		description: "No problem — you can try again whenever you're ready.",
	},
	idp_unavailable: {
		title: "That provider isn't responding",
		description: "We couldn't reach it just now. Give it a moment and try again.",
	},
};

function describeError(code: string): { title: string; description: string } {
	return (
		(Object.hasOwn(ERROR_COPY, code) ? ERROR_COPY[code] : undefined) ?? {
			title: "Something went wrong",
			description: "We couldn't sign you in. Please try again.",
		}
	);
}

interface LoginCardProps extends SignInButtonsProps {
	title: string;
	description?: ReactNode;
	error?: string;
}

export function LoginCard({ title, description, error, ...signIn }: LoginCardProps) {
	const errorCopy = error ? describeError(error) : undefined;

	return (
		<div className="flex min-h-svh flex-col items-center justify-center gap-6 bg-background p-6 md:p-10">
			<Link
				to="/"
				aria-label="Hephaestus home"
				className="flex items-center gap-2 font-medium hover:opacity-80"
			>
				<HephaestusLogo markClassName="size-7" wordmarkClassName="text-lg" />
			</Link>

			<Card className="w-full max-w-sm">
				<CardHeader className="text-center">
					<h1 className="text-xl font-semibold">{title}</h1>
					{description ? <CardDescription>{description}</CardDescription> : null}
				</CardHeader>
				<CardContent className="flex flex-col gap-4">
					<div aria-live="assertive" aria-atomic="true">
						{errorCopy ? (
							<Alert variant="destructive">
								<AlertTitle>{errorCopy.title}</AlertTitle>
								<AlertDescription>{errorCopy.description}</AlertDescription>
							</Alert>
						) : null}
					</div>
					<SignInButtons {...signIn} />
					<SignInNotice />
				</CardContent>
			</Card>

			<Link to="/" className="text-sm text-muted-foreground hover:text-foreground">
				← Back to home
			</Link>
		</div>
	);
}
