import environment from "@/environment";
import { sanitizeEnvironmentValue } from "@/lib/environment";

export const sentryDsn = sanitizeEnvironmentValue(environment.sentry.dsn);
export const sentryEnvironment = sanitizeEnvironmentValue(environment.sentry.environment);

export const isSentryConfigured = sentryDsn.length > 0;
