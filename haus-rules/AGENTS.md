# haus-rules

Vote counting and notice automation. Behavior is entirely configuration-driven via `cf-haus-rules.yml` in each repository that needs it.

## Context

- [docs/haus-rules-overview.md](../docs/haus-rules-overview.md) - Notice rules engine, voting methods (manual, manual+comments, marthas), config schema
- [docs/configuration-overview.md](../docs/configuration-overview.md) - Config file naming (`cf-haus-rules.yml`) and FileWatcher pattern

## Key constraints

- **Config-driven, not code-driven.** Vote counting methods and notice rules are defined in `cf-haus-rules.yml` per repository. Adding a new behavior means extending the config schema and its interpreter, not hardcoding logic.
- **Vote counting methods are distinct.** `manual` groups by reaction and counts; `manual comments` counts group-member comments; `marthas` groups reactions into approve/ok/revise buckets. Each has different counting logic — don't conflate them.
- **Label syntax uses `!` for negation.** In both `label` conditions and `label_change` triggers, a `!` prefix means "not present" or "was removed". This is a domain convention — preserve it in any label-handling code.
- **Task group is issue/discussion ID.** Queue collapsing for vote counting uses the specific item ID as the task group so concurrent events for the same vote collapse correctly.
- **`managers` controls who can close votes**, not who can vote. Don't conflate the two roles when evaluating permissions.
