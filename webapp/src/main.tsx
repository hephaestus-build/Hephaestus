import * as Sentry from "@sentry/react";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode, useEffect } from "react";
import ReactDOM from "react-dom/client";

import { client } from "@/api/client.gen";
import environment from "@/environment";
import { RouteError } from "@/integrations/sentry/RouteError";
import { workspaceAccessAlias } from "@/lib/workspace-access-alias";

import "./styles.css";

import { AuthProvider, applyStateChangingHeaders, useAuth } from "@/integrations/auth";
import { handlePossibleSessionExpiry } from "@/integrations/auth/session-expiry";
import { SessionKeepAlive } from "@/integrations/auth/use-session-keep-alive";
import { useCookieConsent } from "@/integrations/consent";
import { TanstackDevtools } from "@/integrations/devtools/TanstackDevtools";
import { disableSentry, initSentry } from "@/integrations/sentry";
import { ThemeProvider } from "@/integrations/theme";
import { useImpersonationStore } from "@/stores/impersonation-store";

import * as TanstackQuery from "./integrations/tanstack-query/root-provider";
import { routeTree } from "./routeTree.gen";

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
	document.head.appendChild(manifestLink);
}

client.interceptors.request.use((request) =>
	applyStateChangingHeaders(request, useImpersonationStore.getState().writesEnabled),
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

const rootElement = document.getElementById("app");
const accessAlias =
	window.location.pathname === "/request-access"
		? workspaceAccessAlias(window.location.hostname, environment.clientUrl)
		: undefined;
if (accessAlias) {
	window.location.replace(accessAlias);
} else if (rootElement && !rootElement.innerHTML) {
	const root = ReactDOM.createRoot(rootElement, {
		onUncaughtError: Sentry.reactErrorHandler((error, errorInfo) => {
			// oxlint-disable-next-line no-console -- The custom handler replaces React's console report.
			console.warn("Uncaught error", error, errorInfo.componentStack);
		}),
		onRecoverableError: Sentry.reactErrorHandler(),
	});
	root.render(
		<StrictMode>
			<Root />
		</StrictMode>,
	);
}
