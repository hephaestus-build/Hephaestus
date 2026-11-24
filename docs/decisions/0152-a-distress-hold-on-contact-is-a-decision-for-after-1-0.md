# ADR 0152: A distress hold on contact is a decision for after 1.0

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Relates to:**

- ADR 0139 (proposed in #4865, the contact policy and the person's own controls)
- ADR 0124 (proposed in #4866, Hephaestus claims no feeling and infers no emotion)

> **1.0 does not build this.**
>
> - 1.0 has no distress hold. The person's own controls stop contact: Pause, the Only when agreed level and mutes.
> - When a message shows distress, Hephaestus gives a fixed reply with the support resources that the operator set. It points to human support and is not a crisis service.
> - 1.0 reserves nothing for a hold: no table, column, state or contact rule.

## Context

A person can write that they are stressed or overwhelmed.
Hephaestus then gives a fixed reply with the support resources of the instance, and no practice feedback in the same reply.

A distress hold would go further: code would stop unprompted contact for a time after such a message.
Someone or something must set that hold.
A model that sets it classifies the person's emotion, and Hephaestus infers no emotion (ADR 0124, proposed in #4866).
A hold also needs an end, the contact kinds it blocks, a reverse and a record.

The real constraint is that the person stays in control of contact without Hephaestus judging their state.

## Decision drivers

- The person controls contact. Hephaestus does not decide that a person is in distress.
- Hephaestus is not a crisis service, and it never implies that it is one.
- 1.0 adds no state that nobody has designed end to end.

## Considered options

1. **A hold that a classifier sets.** A model reads each message and holds contact when it detects distress. It infers an emotion, it can be wrong both ways, and nobody can check it.
2. **A hold that the person sets from the fixed reply.** One more control beside Pause. It duplicates Pause, with a second name for the same act.
3. **No hold in 1.0. The person's own controls cover it, and 1.x decides whether a hold exists (chosen).**

## Decision

Option 3.

1. **1.0 has no distress hold.** Pause, the Only when agreed level and mutes of ADR 0139 stop contact. Each has its reverse.
2. **The fixed reply stays.** It names the support resources that the operator set. It points to human support and says that Hephaestus is not a crisis service.
3. **A 1.x release decides whether a hold exists.** That decision states who sets the hold and what ends it. It states the contact kinds it blocks, its reverse and its record. It must not infer an emotion.

### What this changes in other ADRs

No ADR changes.

## Consequences

- Good: Hephaestus never infers a person's emotion, and the person keeps every control.
- Good: 1.0 ships no half-designed state.
- Bad: after a distress message, unprompted contact continues until the person pauses it.

## Confirmation

Not enforced. Review of the 1.0 contact policy catches a hold that a model sets.

## Revisit trigger

- People report that unprompted contact after a distress message hurt them.
- An operator asks for a hold that the person can set from the fixed reply.

## Sources

No outside source. The decision follows the rule of ADR 0124 (proposed in #4866) that Hephaestus infers no emotion.
