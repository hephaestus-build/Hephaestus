import * as Sentry from "@sentry/react";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode, useEffect } from "react";
import ReactDOM from "react-dom/client";

import { client } from "@/api/client.gen";
import environment from "@/environment";
import { RouteError } from "@/runtime/sentry/RouteError";

import "./styles.css";

import { applyStateChangingHeaders } from "@/runtime/auth/auth-client";
import { AuthProvider, useAuth } from "@/runtime/auth/AuthContext";
import { handlePossibleSessionExpiry } from "@/runtime/auth/session-expiry";
import { SessionKeepAlive } from "@/runtime/auth/use-session-keep-alive";
import { useCookieConsent } from "@/runtime/consent";
import { TanstackDevtools } from "@/runtime/devtools/TanstackDevtools";
import { disableSentry, initSentry } from "@/runtime/sentry";
import { ThemeProvider } from "@/runtime/theme/ThemeContext";
import { applyUserViewHeaders } from "@/runtime/user-view/session";

import { routeTree } from "./routeTree.gen";
import * as TanstackQuery from "./runtime/tanstack-query/root-provider";

// No global timeout: aborting a request does not cancel a server-side mutation.
client.setConfig({
	baseUrl: environment.serverUrl,
	// Development serves the SPA and API on different origins.
	credentials: "include",
});

// CSP blocks inline scripts; select the manifest from the allowed application bundle.
{
	const manifestLink = document.createElement("link");
	manifestLink.rel = "manifest";
	manifestLink.href =
		window.location.hostname === "localhost" ? "/manifest-dev.json" : "/manifest.json";
	document.head.append(manifestLink);
}

client.interceptors.request.use((request) =>
	applyUserViewHeaders(applyStateChangingHeaders(request)),
);

client.interceptors.response.use((response) => {
	handlePossibleSessionExpiry(response, TanstackQuery.getContext().queryClient);
	return response;
});

const router = createRouter({
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
});

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
	const consent = useCookieConsent();
	const errorMonitoring = consent?.errorMonitoring === true;

	useEffect(() => {
		if (errorMonitoring) {
			initSentry();
		} else {
			disableSentry();
		}
	}, [errorMonitoring]);

	return (
		<TanstackQuery.Provider>
			<AuthProvider>
				<SessionKeepAlive />
				<ThemeProvider defaultTheme="dark" storageKey="theme">
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
		onUncaughtError: Sentry.reactErrorHandler((uncaught, errorInfo) => {
			// oxlint-disable-next-line no-console -- The custom handler replaces React's console report.
			console.warn("Uncaught error", uncaught, errorInfo.componentStack);
		}),
		onRecoverableError: Sentry.reactErrorHandler(),
	});
	root.render(
		<StrictMode>
			<Root />
		</StrictMode>,
	);
}
