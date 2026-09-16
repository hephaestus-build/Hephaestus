import { type ErrorComponentProps, useRouter } from "@tanstack/react-router";
import { useEffect, useRef } from "react";

import { Button } from "@/components/ui/button";
import { captureException } from "@/runtime/sentry";

export function RouteError({ error }: ErrorComponentProps) {
	const router = useRouter();
	const reportedError = useRef<unknown>(undefined);
	useEffect(() => {
		if (reportedError.current !== error) {
			captureException(error);
			reportedError.current = error;
		}
	}, [error]);

	return (
		<div className="flex min-h-[50vh] items-center justify-center p-6">
			<section className="max-w-md space-y-4 text-center" role="alert">
				<h1 className="text-2xl font-semibold">Something went wrong</h1>
				<p className="text-muted-foreground">
					An unexpected error stopped this page from loading. Trying again usually helps.
				</p>
				<div className="flex justify-center gap-2">
					<Button onClick={() => void router.invalidate()}>Try again</Button>
				</div>
			</section>
		</div>
	);
}
