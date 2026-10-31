import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import ReactDOM from "react-dom/client";

import { client } from "@/api/client.gen";
import { PageSkeleton } from "@/components/layout/PageSkeleton";
import environment from "@/environment";
import { ROUTER_SEARCH } from "@/lib/router-search";
import { csrfFetch } from "@/runtime/auth/csrf";
import { RouteError } from "@/runtime/errors/RouteError";
import { installPreloadErrorReload } from "@/runtime/preload-error";
import {
	installWorkspaceNavigation,
	workspaceAddressConfig,
	workspaceRewrite,
} from "@/runtime/workspace-address";

import "./styles.css";

import { applyStateChangingHeaders } from "@/runtime/auth/auth-client";
import { AuthProvider, useAuth } from "@/runtime/auth/AuthContext";
import { installPasskeyChallenge } from "@/runtime/auth/passkey-challenge";
import { handlePossibleSessionExpiry } from "@/runtime/auth/session-expiry";
import { SessionKeepAlive } from "@/runtime/auth/use-session-keep-alive";
import { installBrowserFaults } from "@/runtime/browser-faults";
import { TanstackDevtools } from "@/runtime/devtools/TanstackDevtools";
import { ThemeProvider } from "@/runtime/theme/ThemeContext";
import { applyUserViewHeaders } from "@/runtime/user-view/session";
import { reportVisitVitals } from "@/runtime/web-vitals";

import { routeTree } from "./routeTree.gen";
import * as TanstackQuery from "./runtime/tanstack-query/root-provider";

// No global timeout: aborting a request does not cancel a server-side mutation.
client.setConfig({
	baseUrl: environment.serverUrl,
	// Development serves the SPA and API on different origins.
	credentials: "include",
	fetch: csrfFetch,
});

client.interceptors.request.use((request) =>
	applyUserViewHeaders(applyStateChangingHeaders(request)),
);

const router = createRouter({
	...ROUTER_SEARCH,
	rewrite: workspaceRewrite(workspaceAddressConfig, window.location.origin),
	routeTree,
	context: {
		...TanstackQuery.getContext(),
		auth: undefined,
	},
	defaultPreload: "intent",
	scrollRestoration: true,
	// `index.html` sets `scroll-smooth`, so an unspecified behaviour resolves to `auto` and CSSOM-View
	// makes every restore an *animated* scroll — one that a second write can abort halfway.
	scrollRestorationBehavior: "instant",
	defaultStructuralSharing: true,
	defaultPreloadStaleTime: 0,
	defaultErrorComponent: RouteError,
	// A route renders nothing of its own while it loads, so without a default a slow navigation keeps
	// the previous page on screen under the new address. After `defaultPendingMs` the page area shows
	// a skeleton instead, and the layout around it stays.
	defaultPendingComponent: PageSkeleton,
});

installBrowserFaults(router);
installPasskeyChallenge(router);
reportVisitVitals(router, TanstackQuery.getContext().queryClient);
installPreloadErrorReload(router);

client.interceptors.response.use((response) => {
	handlePossibleSessionExpiry(response, TanstackQuery.getContext().queryClient);
	return response;
});

installWorkspaceNavigation(router, workspaceAddressConfig);

declare module "@tanstack/react-router" {
	interface Register {
		router: typeof router;
	}
}

function WrappedRouterProvider() {
	const auth = useAuth();
	return <RouterProvider router={router} context={{ ...TanstackQuery.getContext(), auth }} />;
}

function Root() {
	return (
		<TanstackQuery.Provider>
			<AuthProvider>
				<SessionKeepAlive />
				<ThemeProvider>
					<WrappedRouterProvider />
					<TanstackDevtools router={router} />
				</ThemeProvider>
			</AuthProvider>
		</TanstackQuery.Provider>
	);
}

const rootElement = document.querySelector("#app");
if (rootElement && !rootElement.innerHTML) {
	const root = ReactDOM.createRoot(rootElement, {
		onUncaughtError: (uncaught, errorInfo) => {
			// oxlint-disable-next-line no-console -- The custom handler replaces React's console report.
			console.warn("Uncaught error", uncaught, errorInfo.componentStack);
		},
	});
	root.render(
		<StrictMode>
			<Root />
		</StrictMode>,
	);
}
