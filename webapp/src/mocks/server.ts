// Vitest and Storybook share these handlers so mocked behavior stays consistent.

import { HttpResponse, http } from "msw";
import { setupServer } from "msw/node";

import { handlers } from "./handlers";

/** Requests that no handler answered since `src/test/setup-msw.ts` last read them. */
export const unansweredRequests: string[] = [];

/** What handlers threw since `src/test/setup-msw.ts` last read them. MSW only logs a throw and answers 500. */
export const handlerFailures: string[] = [];

// The last handler answers what no other handler does with a network error. Thus, no test request
// reaches a real host, such as another checkout's server on the API port, and a query that waits on it ends.
export const server = setupServer(
	...handlers,
	http.all("*", ({ request }) => {
		unansweredRequests.push(`${request.method} ${request.url}`);
		return HttpResponse.error();
	}),
);
server.events.on("unhandledException", ({ request, error }) => {
	handlerFailures.push(`${request.method} ${request.url}: ${error.message}`);
});
