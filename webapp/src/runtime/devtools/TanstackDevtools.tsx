import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import type { AnyRouter } from "@tanstack/react-router";
import { TanStackRouterDevtools } from "@tanstack/react-router-devtools";

import environment from "@/environment";
import { sanitizeEnvironmentBoolean } from "@/lib/environment";

const isTanstackDevtoolsEnabled = sanitizeEnvironmentBoolean(environment.devtools.tanstack);

interface TanstackDevtoolsProps {
	router?: AnyRouter;
}

export function TanstackDevtools({ router }: TanstackDevtoolsProps) {
	if (!isTanstackDevtoolsEnabled) {
		return null;
	}

	return (
		<>
			<ReactQueryDevtools initialIsOpen={false} buttonPosition="bottom-left" />
			{router ? <TanStackRouterDevtools router={router} position="bottom-right" /> : null}
		</>
	);
}
