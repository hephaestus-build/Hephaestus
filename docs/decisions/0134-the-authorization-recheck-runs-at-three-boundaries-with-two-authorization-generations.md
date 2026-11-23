# ADR 0134: The authorization recheck runs at three boundaries, with two authorization generations

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0079](0079-one-agent-system.md) § Data model (runs and actions carry an authorization stamp), [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) decision 11 (the model request also rechecks authority)

**Builds on:** [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md) (concrete readers), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (the outbox and its write fence), [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) (the relationship)

**Relates to:** ADR 0120 and ADR 0151 (proposed in #4862), ADR 0135 (proposed in #4867, the change feed), [ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md) (the narrowings of transfer)

## Context

A turn can run for minutes, and a queued write can wait longer.
In that time, the person can leave the team, turn off "Use what travels with me here", or keep a record out of a space.
A check at the start of the run then allows a read or a write that the person already withdrew.

On main, the LLM proxy rechecks the person's AI choice on each model request ([ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) decision 11).
Nothing rechecks the other kinds of authority.
The real constraint is to find the boundaries where stale authority does harm, and to check there only.

## Decision drivers

- Withdrawn authority never reaches a model, a provider or another person.
- A check is cheap. Narrowing is rare, and a false cancellation is safe.
- The person's own answer is not interrupted by a change that does not concern authority.
- A write that may have started is never repeated.

## Considered options

1. **Check once, at the start of the run.** A run that lasts minutes sends after access ended. The gap is at egress, where the harm is.
2. **Check at five boundaries,** with the owner's stream and the owner's commit added. Those two stay inside the relationship. A check there adds latency to every answer and stops no harm.
3. **A generation for each kind of setting.** Seven or more generation columns add bugs. A bump to the wrong one misses a narrowing.
4. **One global generation.** One person's change then cancels the runs of every person on the instance.
5. **Two authorization generations, and a recheck at three boundaries (chosen).**

## Decision

Option 5.

1. **Two authorization generations.** One belongs to the person, on the relationship. One belongs to the workspace.
2. **Only an authority narrowing bumps a generation.** The narrowings are:
   - "Use what travels with me here" turned off, and "Keep out of this space" on a record.
   - Excluding a workspace, and the withdrawal of a claim to be the change requester of an item.
   - A learning switch turned off, and a membership change.
   - A change of the Slack binding, which bumps the workspace's generation.
3. **Some revocations have their own check instead.**
   - A session revoke closes that session's streams at once.
   - A support snapshot revoke is checked at every read.
   - A research consent withdrawal is checked again before release.
4. **Instance switches bump nothing.** Dispatch and send read the current instance settings. Silent mode is also checked at send.
5. **Contact changes are not authority changes.** Pause, quiet hours, Not now, Less often, mute and stop never bump a generation. They never stop an answer or reset a cache. The contact policy checks them at send.
6. **The recheck runs at three boundaries.**
   - Context read: before a run reads a source.
   - Model request: before the LLM proxy sends a call.
   - Any send outside the app: a provider write through the adapter's `beginWrite`, a Slack reply, an email and a push.
7. **The owner's own stream is not a recheck boundary.** A session end closes it. A narrowing changes the scope epoch, and the epoch fences the stream:
   - Every stream frame carries the scope epoch that it was produced under.
   - The sandbox gateway drops an answer delta whose scope epoch is older than the current one. So the output of a model call that started before the narrowing reaches no client.
   - The eviction frame goes out before any frame of the new epoch. ADR 0135 (proposed in #4867) decision 5 binds the frames.
8. **A run and an action carry an authorization stamp.** `agent_run` and `agent_action` store the generations that they started with. A stale stamp refuses with its reason. A false cancellation is safe.
9. **A write that may have started is never replayed blindly.** ADR 0151 (proposed in #4862) decision 3 gives the rule for each transport. Provider and Slack writes are read back. Email and push have no readback and stay at most once.
10. **A person's pause never blocks the copy on the work.** That copy follows the work's own settings.
11. **One order at each boundary.** The authorization recheck runs first, then routing and limits, then the contact policy.
12. **Each setting that carries authority is registered.** It has one owning row, one owning command and one generation. It registers one test case for each boundary.

### What this changes in other ADRs

- [ADR 0079](0079-one-agent-system.md) § Data model: `agent_run` and `agent_action` gain `authorization_stamp`.
- [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) decision 11 stays and grows. The model request also compares the run's stamp with the current generations.
- [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) stays. The send boundary extends the adapter's `beginWrite` fence. It adds no second outbox.
- ADR 0139 (proposed in #4865), the contact policy, points to decisions 5 and 11. They own the two rules: a contact change is not an authority change, and one order at each boundary.

## Consequences

- Good: a person who leaves a team stops every queued read, call and send at the next boundary.
- Good: two columns cover every narrowing, and a registry test finds a setting that has no case.
- Good: a change of contact settings never cuts off an answer.
- Bad: a narrowing cancels runs that the change did not concern, such as a review in the same workspace. The run starts again.
- Bad: every new authority setting must register its cases. A setting that is not marked as one escapes the registry.
- Bad: a send refused for a stale stamp can leave the person without a requested result. The result stays in the app.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `AuthorityFenceTest` fails when a revoked membership allows the next context read, model request or queued send. Each refusal carries its reason.
- A test with a recording adapter fails when a write whose authority ended after it was queued reaches the provider.
- `AuthorityBearingRegistryTest` fails when a setting that carries authority has no case for each boundary.
- An integration test changes a contact setting during a stream. It fails when the turn stops or the scope epoch changes.
- An integration test fails when a narrowing does not evict on the next feed frame and close the stream. It also fails when an answer delta of the old scope epoch reaches the client after the eviction.

## Revisit trigger

- Narrowings cancel more than 1 % of runs over four weeks.
- A new kind of authority cannot map to the person or the workspace.

## Sources

- Pang et al., [Zanzibar: Google's Consistent, Global Authorization System](https://www.usenix.org/system/files/atc19-pang.pdf), USENIX ATC 2019. To prevent the "new enemy" problem, Zanzibar stores "a zookie for each content version". A check then never uses permissions older than the content. The authorization stamp applies that idea to runs and actions.
