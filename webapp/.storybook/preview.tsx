import { withThemeByClassName, withThemeFromJSXProvider } from "@storybook/addon-themes";
import type { Decorator, Preview } from "@storybook/react-vite";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRootRoute, createRouter, RouterProvider } from "@tanstack/react-router";
import { isCommonAssetRequest } from "msw";
import { initialize, mswLoader } from "msw-storybook-addon";
import React from "react";

import { Toaster } from "@/components/ui/sonner";
import { ThemeProvider } from "@/integrations/theme";
import { handlers } from "@/mocks/handlers";

import "@/styles.css";

initialize(
	{
		onUnhandledRequest(request, print) {
			if (
				!isCommonAssetRequest(request) &&
				new URL(request.url).origin === window.location.origin
			) {
				print.error();
			}
		},
		quiet: true,
		serviceWorker: { url: "./mockServiceWorker.js" },
	},
	handlers,
);

const QueryDecorator: Decorator = (Story) => {
	const queryClient = new QueryClient({
		defaultOptions: {
			queries: {
				retry: false,
				refetchOnWindowFocus: false,
				refetchOnReconnect: false,
				staleTime: Number.POSITIVE_INFINITY,
			},
			mutations: { retry: false },
		},
	});
	return (
		<QueryClientProvider client={queryClient}>
			<Story />
		</QueryClientProvider>
	);
};

const RouterDecorator: Decorator = (Story) => {
	const rootRoute = createRootRoute({
		component: () => <Story />,
	});
	const routeTree = rootRoute;
	const router = createRouter({ routeTree });
	return <RouterProvider router={router} />;
};

/**
 * `__root.tsx` mounts one of these for the whole app, so a story without it has no error channel at
 * all: `toast.error` resolves, renders nowhere, and a play function asserting the failure path has
 * nothing to find. Mounted here rather than per story, because the surfaces that raise a toast are
 * spread across the app and the ones that forget to mount it are exactly the ones that need it.
 */
const ToastDecorator: Decorator = (Story) => (
	<>
		<Story />
		<Toaster />
	</>
);

const injectDocsThemeCSS = () => {
	if (typeof document === "undefined") return;

	const styleId = "storybook-docs-theme";
	let style = document.getElementById(styleId);

	if (!style) {
		style = document.createElement("style");
		style.id = styleId;
		document.head.appendChild(style);
	}

	style.textContent = `
		.docs-story {
			background-color: var(--background) !important;
			color: var(--foreground) !important;
		}
	`;
};

const ThemeDecorator: Decorator = (Story) => {
	React.useEffect(() => {
		injectDocsThemeCSS();
	}, []);

	return <Story />;
};

const StorybookThemeProvider = ({
	theme,
	children,
}: {
	theme: string;
	children: React.ReactNode;
}) => {
	return (
		<ThemeProvider
			key={theme}
			defaultTheme={theme === "dark" ? "dark" : "light"}
			storageKey="storybook-theme"
		>
			{children}
		</ThemeProvider>
	);
};

const preview: Preview = {
	parameters: {
		// Base UI focus guards redirect focus immediately but axe flags their hidden sentinels.
		// https://github.com/mui/base-ui/issues/4668
		a11y: {
			test: "error",
			context: { exclude: "[data-base-ui-focus-guard]" },
		},
		controls: {
			matchers: {
				color: /(background|color)$/i,
				date: /Date$/,
			},
		},
		options: {
			storySort: {
				// Product surfaces first, roughly outside-in by who opens them, then the shared kit, then
				// the auto-titled path trees, then cross-cutting regression suites. Every top-level
				// segment any story declares has to appear here — one that does not sorts alphabetically
				// below every segment that does, silently. That includes segments nobody wrote: a story
				// with no explicit title gets one derived from its path.
				order: [
					"Onboarding",
					"Workspace",
					"Workspace admin",
					"Instance admin",
					"Practice trace",
					"Profile",
					"Surveys",
					"Common",
					"Shared",
					"Provider",
					"Icons",
					"components",
					"integrations",
					"Tests",
				],
			},
		},
		docs: {
			story: {
				inline: true,
			},
		},
		chromatic: {
			viewports: [1440],
			disableSnapshot: false,
		},
		viewport: {
			options: {
				reflow: {
					name: "Reflow (320px)",
					styles: { width: "320px", height: "568px" },
				},
				mobile: {
					name: "Mobile",
					styles: { width: "375px", height: "667px" },
				},
				tablet: {
					name: "Tablet",
					styles: { width: "768px", height: "1024px" },
				},
				desktop: {
					name: "Desktop",
					styles: { width: "1440px", height: "900px" },
				},
				wide: {
					name: "Wide Desktop",
					styles: { width: "1920px", height: "1080px" },
				},
			},
		},
	},
	loaders: [mswLoader],
	decorators: [
		QueryDecorator,
		RouterDecorator,
		ToastDecorator,
		ThemeDecorator,
		withThemeByClassName({
			themes: {
				light: "light",
				dark: "dark",
			},
			defaultTheme: "light",
			parentSelector: "html",
		}),
		withThemeFromJSXProvider({
			themes: {
				light: { name: "light" },
				dark: { name: "dark" },
			},
			defaultTheme: "light",
			// The addon types `Provider` as `any`, so the shape it is called with — one entry of
			// `themes` above — is declared here.
			Provider: ({ theme, children }: { theme: { name: string }; children: React.ReactNode }) => (
				<StorybookThemeProvider theme={theme.name}>{children}</StorybookThemeProvider>
			),
		}),
	],
};

export default preview;
