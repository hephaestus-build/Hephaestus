# ADR 0027: Dialog lifetime, and where a write's outcome lands when the dialog is gone

**Status:** Accepted
**Date:** 2026-07-27
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md) (the money surfaces this was first written for)

## Context

Every admin surface in the webapp starts writes from a dialog: a confirm for destructive row actions,
a form dialog for editing a connection, a model, or a monthly cap. All of them face the same question,
and before this ADR each answered it locally, in a comment: **the dialog can stop existing before the
request it started settles — so where does the outcome go?**

The dialog can vanish two ways. The confirm closes itself the instant you confirm. A form dialog stays
open while its request is out, but it stays *dismissible* while it does — we set no request timeout,
so a dialog that refused to close would be a trap with no exit. Either way the request outlives the
surface that started it, and TanStack Query resets a mutation whose owning component unmounts, so a
rejection can arrive with nowhere obvious to land.

The failure this guards against is specific and silent: an admin sets a monthly cap, the write is
rejected, nothing is said, the page still shows the old cap, and the admin walks away believing the
new one is in force. Money settings make it worst, but the shape is not money-specific.

## Decision drivers

- A rejected write must never be silent. That is the whole of it; everything below is mechanism.
- A rejected write must not be reported *twice* either — a field error plus a toast saying the same
  sentence trains people to read neither.
- The dialog should hold no in-flight state. State that exists only while a request is out is state
  that gets stranded when the request outlives its holder.
- Confirming must not be firable twice.
- The user must always be able to leave.

## Considered options

- **Keep the dialog open until the request settles, with the actions disabled.** Reports failures in
  the most obvious place — but there is no request timeout, so a hung request holds the user in a
  modal with no exit. Rejected.
- **Let each surface decide.** What we had. Four copies of the same paragraph at four call sites,
  already drifting in wording, and none of them discoverable from a fifth surface that needs the rule.
- **Report every outcome as a toast, uniformly.** Simple, and wrong for a field-level rejection: the
  server's reason for refusing an amount belongs against the amount that earned it, not in a corner of
  the screen while the field that caused it sits blank.

## Decision

**A confirm closes on confirm, before its request settles.** `ConfirmDialog` calls `onClose` right
after `onConfirm`. The dialog therefore holds no in-flight state to get wrong, and a second click
cannot reach a dialog that is no longer there. Feedback survives the close by living on the surface
that outlives it: the row that owns the request stays disabled until it settles (from
`usePendingMutationIds`, which reads in-flight ids off the mutation cache rather than a local flag),
and the outcome arrives as a toast.

**A form dialog stays open, and stays dismissible.** Its `onError` writes the server's reason into the
field that earned it; that field error is the whole report and a toast would only say it twice.

**Whichever surface is still alive at settle time is the one that reports.** These two rules meet when
a form dialog is dismissed while its write is out. The `onError` handler asks whether the dialog it
would have reported into is still open on the same subject — a ref, because the handler must read the
value at settle time, not the value captured when the mutation was fired — and falls back to a toast
when it is not:

```ts
onError: (error, variables) => {
  if (editingRef.current?.workspaceSlug !== variables.path.workspaceSlug) {
    toast.error("Couldn't save the cap", { description: problemDetailOf(error) });
  }
},
```

That single condition is what makes "never silent" and "never said twice" hold at once.

**Success copy states a bound the system keeps, not a hedge.** Where a write's effect is not
immediate, the confirmation says how long — "New calls resume within a minute", because
`ProxyBudgetGate` caches its verdict for 5 seconds (ADR 0026). "Resumes now" would be a small lie, and
"about a minute" would be a hedge rather than a promise.

## Consequences

- Destructive confirms are uniform across the app: they close on confirm, and the calling row owns the
  busy state. A caller that forgets the disabled row gets a re-firable action, which is why
  `usePendingMutationIds` exists rather than a per-surface `useState`.
- Any surface adding a dismissible write dialog must add the settle-time check. Without it a dismissed
  dialog's rejection is silent — the exact failure this ADR exists to prevent.
- Call sites carry a one-line reference here instead of the argument. The argument had reached four
  copies across two routes and two tests, in two wordings, before it was extracted.

## Confirmation

`ConfirmDialog.test.tsx` fails if a confirm stays open after confirm or blocks Escape while its request is out.
`use-pending-mutation-ids.test.tsx` checks that the row stays busy until its own request settles.
`-admin-usage-route.test.tsx` checks the settle-time rule for the budget dialog: a toast after dismissal, and only the field error while it is open.
Review catches a new dismissible write dialog without the settle-time check.

## Revisit trigger

If we ever give writes a request timeout, the "refusing to close would be a trap" driver goes away and
the first considered option (hold the dialog open until settle) becomes viable again — it is the more
obvious design, and it was rejected only for the lack of a bound.
