# ADR 0051: Heph talks with the reader in the browser extension's side panel

**Status:** Accepted
**Date:** 2026-10-02
**Authors:** Felix T.J. Dietrich

## Context

The Chrome extension shows a read-only practice review inside the provider's page
([ADR 0049](0049-browser-extension-report-in-the-page.md)). Readers asked to talk with Heph about
the work they are looking at, without leaving it. A conversation is unlike the report. What the reader
writes and what Heph answers is confidential, so it may not enter the provider's DOM, including
through the frame's geometry. Sending a message is a change, so the report frame, which the page can
cover, cannot be where it is typed. A turn streams for minutes through an MV3 service worker that
holds the only credential. And a conversation is server history the web app must be able to continue.

## Decision drivers

- **A surface the page can neither read nor overlay**, for typing and for the transcript.
- **No second report.** Earlier designs that put a split view, nested cards or repeated feedback beside
  the page were rejected; the report stays inline and is not repeated.
- **One mentor.** The same endpoint, protocol, gates and history as the web app; no second backend or
  protocol.
- **Honest context.** Heph is told which work, in words the reader sees; nothing is read from the page.
- **Nothing reaches Heph until the reader sends.**

## Considered options

1. **Chat inside the report frame.** Rejected. The page can lay a fake composer over the frame and
   capture what is typed; the frame may not send a change; and a growing transcript would change the
   host's height, which the page can read.
2. **The extension's own window**, like the review confirmation. Trustworthy, but it floats away from
   or over the work, and the reader manages a second window for a conversation about one tab.
3. **Hand off to the web app.** Simple and complete, but it leaves the work and is not the contextual
   conversation asked for. Kept as **Continue in Hephaestus**.
4. **Chrome's side panel for the tab.** Browser chrome beside the page, shown and hidden with its tab,
   closed and resized by the reader. Chosen. It is a side-by-side surface, so it holds the conversation
   only.

For the work reference:

- **A work reference stored on the thread** (new columns, a context section). Rejected: a schema and
  hidden prompt content for what one visible sentence says, and the web app would need to render it.
- **A visible first line in the reader's own message**, built by the extension from the parsed
  address. Chosen: ordinary history that every surface already shows, and Heph's context already
  carries the recorded addresses of the reader's work. It names the work by kind, number, repository
  and canonical address only. A title is written by whoever opened the work, so it never speaks in
  the reader's message.

## Decision

- The report's **Ask Heph**, or the toolbar icon on a supported work page, opens `mentor.html` as the side panel of that tab only. The worker
  configures the tab's panel address (`mentor.html?tab=<id>`) when the report resolves, and on the
  press calls `setOptions` and `open` before awaiting anything, because Chrome opens a side panel only
  while handling the press. There is no manifest default panel.
- Every request from the panel is attested twice: by Chrome's sender facts (outside any tab, exactly
  that address) and by `sidePanel.getOptions` still holding that address for that tab.
- The panel runs the AI SDK's `useChat` and `DefaultChatTransport` with the web app's request body.
  Its `fetch` is a port to the worker, which validates the body, sends it to the hidden mentor
  endpoint through the generated client with its own credential, and relays the response's bytes. The
  SDK parses the stream; the server's keep-alive comments keep the worker running. Closing the port
  stops the reply.
- The worker resolves the tab's work as the report does. It refuses a new conversation whose first
  message does not open with the reference to the work the tab shows, and a continued one once the tab
  has left its work. It keeps only ids per tab in session storage. A generation change or a closed
  tab aborts the turn.
- The server's gates stay the server's. The panel reads the member's AI choice only to disable sending
  with the web app's own notice.

## Consequences

- The extension declares the `sidePanel` permission and depends on `ai`, `@ai-sdk/react` and
  `streamdown`, at the web app's versions. Messages render through the web app's own leaves.
- What Heph knows about other people's work is what the workspace recorded, and the panel says so.
- The privacy page, store listing and data-use declarations now cover messages to Heph.
- Opening the panel depends on Chrome treating the report frame's press as the user gesture that
  `sidePanel.open` requires. If Chrome stops doing so, the press reports that the panel did not open.

## Revisit trigger

Chrome changes when `sidePanel.open` is allowed, the mentor gains a first-class way to attach context
to a conversation, or readers need the conversation on more than one tab at once.
