import { createContext } from "react";

/**
 * The `id` of the line that describes the composer: the conversation's AI disclosure. The timeline
 * renders both and the route builds the composer, so the timeline hands the id down this way.
 */
export const ComposerDescription = createContext<string | undefined>(undefined);
