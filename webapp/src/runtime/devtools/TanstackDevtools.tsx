import type { AnyRouter } from "@tanstack/react-router";
import { lazy, Suspense } from "react";

/**
 * Release builds of the TanStack devtools render nothing, so a release image has no use for them.
 * `import.meta.env.DEV` is a build-time constant: a production build drops the dynamic imports with
 * the branch, and the devtools packages do not reach the bundle.
 */
const Devtools = import.meta.env.DEV
	? lazy(async () => {
			const [{ ReactQueryDevtools }, { TanStackRouterDevtools }] = await Promise.all([
				import("@tanstack/react-query-devtools"),
				import("@tanstack/react-router-devtools"),
			]);
			return {
				default: ({ router }: TanstackDevtoolsProps) => (
					<>
						<ReactQueryDevtools initialIsOpen={false} buttonPosition="bottom-left" />
						{router ? <TanStackRouterDevtools router={router} position="bottom-right" /> : null}
					</>
				),
			};
		})
	: null;

interface TanstackDevtoolsProps {
	router?: AnyRouter;
}

export function TanstackDevtools({ router }: TanstackDevtoolsProps) {
	return Devtools === null ? null : (
		<Suspense fallback={null}>
			<Devtools router={router} />
		</Suspense>
	);
}
