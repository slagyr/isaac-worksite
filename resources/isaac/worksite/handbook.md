# isaac.worksite — worksites: leased working directories

You are a crew running inside Isaac. This chapter covers what
**isaac-worksite** owns: a resource-pool type named `:worksite` that turns a
list of directory paths into a pool a turn can lease one member of, the
durable lock files that make a lease exclusive across processes, and the
`isaac worksites` CLI for inspecting and operator-locking members. It uses
"resource pool", "turn", "charge", and "session" the way `isaac.agent`
defines them — read that chapter first (`isaac.agent#tools-and-directories`
covers the generic resource-pool mechanism: berths, `:resource-pools` config
table, `--pool`, busy-means-wait admission) if you haven't; this one only
covers what the `:worksite` type itself adds. Config mechanics
(`handbook__configure`, hot reload, `${VAR}` secrets) are
`isaac.foundation`'s.

A worksite has no tools, comms, or slash commands of its own — everything
here is either a `resource-pools.<name>.*` config path or an
`isaac worksites` CLI action.

## Worksites: a pool of directories

**What it is.** A worksite is one entry in the generic `resource-pools`
config table with `:type :worksite` and a `:members` list of absolute
directory paths. A singleton worksite is just a one-member pool — there is
no separate "single worksite" shape. Each **member** is leased to at most
one turn at a time; leasing binds that turn's session cwd to the member's
path for the duration of that turn (and only that turn — a session's next
turn against the same pool can land on a different member; sessions and
members are independent). When more than one member is free, the **first
free member in config order wins** — deterministic, not random or
round-robin. A worksite pool never *reads* the calling session's existing
cwd to infer membership; it only assigns one.

**How to change it.** Set fields with `handbook__configure` (or `isaac
config set`), using placeholder paths in place of real ones:

```
config set resource-pools.decks.type :worksite
config set resource-pools.decks.members '["/ships/cordelia/chart-room", "/ships/cordelia/galley"]'
```

A worksite can also be its own entity file at `config/resource-pools/<name>.edn`
instead of living inline — see `isaac.foundation`'s Files section for that
general rule. There is exactly one field beyond the generic `type`/`members`
pair worth knowing: `members` is **required** for a worksite (a memberless
entry is a validation error, not an inert pool — see Validation, below).

**How to verify.** `config get resource-pools.decks` shows the resolved
entry; `config get resource-pools.decks.members` and `config get
resource-pools.decks.type` read back one field at a time the same way.
`isaac worksites list` (below) is the operational view — it shows every
configured worksite's members and their live lease/lock state, not
just the config values.

### Troubleshooting

- **A turn submitted against a pool name never seems to move.** Confirm the
  pool actually resolves as type `:worksite` (`config get
  resource-pools.<name>.type`) — an unknown pool name refuses before
  dispatch (see `isaac.agent`'s chapter), but a *misconfigured* one (no
  `type`, or a typo'd one) fails config validation instead; check `isaac
  config validate` first.
- **Two turns landed on the same member at once.** This should not happen —
  member acquisition is exclusive across processes (a file lock backs every
  claim). If you see it, that's a bug to report with the two turn/session
  ids and the member path, not a config issue.
- **A worksite with members configured still isn't listed.** Confirm the
  entry's `type` is literally `:worksite` — a differently-typed or
  unrecognized `resource-pools` entry never becomes a worksite regardless of
  whether it happens to have a `members` field.

## Validation: what a worksite entry requires

**What it is.** Every `resource-pools` entry whose `type` is `:worksite` is
checked at config load: `members` must be present and non-empty, and every
member must be an absolute path — a relative path (or a non-string) is
rejected by name. Config also outright rejects the retired top-level
`:worksites` key: an install that still has one (root config or
`config/worksites/…`) fails validation naming `"worksites"` until it's
removed in favor of a `resource-pools` entry. There is no compatibility
shim — the old key is a hard error, not a deprecation warning.

**How to change it.** These are load-time checks, not something
`handbook__configure` can override — fix the underlying `members` value:

```
config set resource-pools.dry-dock.members '["/ships/cordelia/dry-dock"]'
config unset worksites
```

**How to verify.** `isaac config validate` reports each violation by the
exact dotted path (`resource-pools.<name>.members`) and names what's wrong
("members required", "members must be absolute paths", or, for the retired
key, "worksites removed; configure resource-pools").

### Troubleshooting

- **`isaac config validate` fails citing `resource-pools.<name>.members`.**
  Either the entry has no `members` at all, or one of its entries is a
  relative path or not a string — fix the list in place; there's no partial
  acceptance of a mixed-valid list.
- **Validation fails citing `worksites`.** Something still sets the
  retired top-level `:worksites` key (inline `isaac.edn`, or a leftover
  `config/worksites/` directory or file). Move its `members` into a
  `resource-pools.<name>` entry with `:type :worksite` and remove the old
  key entirely — `config unset worksites` deletes it wherever it lives.

## Leasing a worksite for a turn

**What it is.** A worksite is leased the same way any resource pool is:
name it on submission (CLI `--pool <name>`, repeatable; or the generic
`:resource-pools` charge field any submission path can set — see
`isaac.agent#tools-and-directories`). What's specific to `:worksite`: a
successful lease binds `:session/cwd` to the member's directory path for
that turn — the crew's tools see that directory as the turn's working
directory, the same as a crew's own `cwd` field would seed it (see
`isaac.agent#crews`), except it's chosen per-turn from whichever member the
pool hands out rather than fixed on the crew. When every member is
currently leased or operator-locked, the turn is **held**, not refused — it
waits on the turn queue and is admitted the moment a member frees (see
`isaac.agent#turns-and-the-tool-loop` for the generic hold/queue mechanics
this reuses). Releasing happens on every turn outcome — success, error, or
cancellation all release the member back to the pool.

**How to change it.** There's no config path for "which member a specific
turn gets" — membership order (config order) and current lease state
together decide that at acquire time. What you can change is which
worksite a turn requests, which is a submission-time argument, not config:

```
isaac prompt -m "..." --session harbor --pool decks
```

**How to verify.** After a turn against a worksite pool completes, its
session's transcript entry for that turn shows the leased member's path as
`cwd` (`isaac sessions show <id>`, or the transcript matcher used in this
module's own features). `isaac turns list` shows a turn parked and waiting
if every member was busy at submission time.

### Troubleshooting

- **A turn against a worksite pool never starts.** Check `isaac worksites
  list` — if every member reads `leased` or `locked`, that turn is
  correctly waiting, not stuck; it should start the moment a member frees.
  If a member reads `free` and the turn still isn't running, check `isaac
  turns list` for a different hold reason (another pool named on the same
  submission, or a busy session).
- **A turn's tools can't see the files you expect.** Confirm which member it
  actually landed in (`cwd` on that turn's transcript entry) — with more
  than one free member, the pool can choose either, deterministically by
  config order, not by anything the turn itself requested.
- **The same session's turns land in different directories across
  requests.** That's expected — a worksite binds cwd per **turn**, not per
  session; pin a turn to one path by naming a single-member worksite instead
  of a multi-member pool if a session needs a stable directory.

## Operator locks and turn leases

**What it is.** Every worksite member has one durable lock file under
`<root>/worksites/<url-encoded-member-path>.lock`, holding whichever of two
kinds currently holds that member:

- A **turn lease** (`:kind :turn`) — held automatically for the life of one
  turn, stamped with the session key, a random token, and an owner id
  (process id plus an in-process identity, so an embedded and a subprocess
  claim in the same pid are never mistaken for each other). Released
  automatically when that turn ends.
- An **operator lock** (`:kind :operator`) — taken and released only by an
  explicit `isaac worksites lock`/`unlock` call. It excludes every turn from
  that member until unlocked; nothing auto-releases it, including a dead
  process (there's no "operator" process to go stale).

Acquisition is exclusive across processes (a real file lock backs the
claim, not just an in-memory check), which matters because a worksite CLI
invocation and a running server are typically separate processes. A turn
lease whose owning process is no longer alive is **stale**, and the next
acquire attempt against that member silently steals it (logged
`:worksite/stale-lock-stolen`) rather than leaving the member wedged
forever; an operator lock is never treated as stale and is never stolen —
only an explicit `unlock` removes it.

**How to change it.** Locking and unlocking are **CLI-only** — there is no
config path for "lock this directory," since a lock is live, per-process
coordination state, not declared intent:

```
isaac worksites lock /ships/cordelia/chart-room
isaac worksites unlock /ships/cordelia/chart-room
```

Both take the member's directory path, not the worksite's name — a member
can belong to only one worksite pool, but is addressed by path since that's
what's actually locked.

**How to verify.** `isaac worksites list` (see below) shows each member as
`free`, `leased (<session>)`, or `locked (operator)`. `isaac logs server`
shows `:worksite/stale-lock-stolen` (with the pid and session of the
lease that was stolen) whenever a dead process's lease gets reclaimed.

### Troubleshooting

- **`isaac worksites lock <path>` fails with "already locked".** Something
  already holds that member — `isaac worksites list` shows whether it's a
  live lease (`leased (<session>)`, wait for that turn to finish) or another
  operator lock (`locked (operator)`, `unlock` it first if that's what you
  intend).
- **`isaac worksites lock`/`unlock` fails with "not a worksite member".**
  The path given doesn't appear in any configured worksite's `members` —
  check for a typo, or that the path matches exactly what's configured
  (this check is exact-string, like the members list itself).
- **A member stays `leased` long after its turn should have ended.** If the
  owning process actually died without releasing (killed mid-turn, host
  crash), the lease is stale but only clears on the **next acquire
  attempt** against that member — it doesn't self-clear on a timer. Submit
  another turn against that pool (or `isaac worksites lock` the exact
  member) to trigger the steal, or wait for the next real turn to try that
  member.
- **`isaac worksites unlock <path>` fails with "not locked".** There's no
  operator lock on that member to release — it may be free, or held by a
  turn lease instead (which only that turn releases, or a future acquire
  steals if stale); operator unlock never touches a turn lease.

## The `worksites` CLI

**What it is.** `isaac worksites` is the one CLI surface this module adds,
with three subcommands: `list` (default, also `isaac worksites` with no
subcommand), `lock <member-path>`, and `unlock <member-path>`. `list` prints
one line per `<worksite-name> <member-path> <state>`, across every
configured worksite, in worksite-name order; a worksite with no eligible
members (see Validation, above) never appears. There's no `isaac worksites
list --json`/`--edn` today — output is plain text only. `[verify]` — worth
confirming with Micah whether a structured form is wanted as a follow-up.

**How to change it.** N/A — this is a CLI action, not config.

**How to verify.**

```
isaac worksites
isaac worksites list
isaac worksites lock /ships/cordelia/chart-room
isaac worksites unlock /ships/cordelia/chart-room
isaac worksites --help
```

### Troubleshooting

- **`isaac worksites` (or `list`) prints nothing.** Either no `resource-pools`
  entry is type `:worksite`, or every such entry failed validation (an
  invalid entry is dropped from the listing, not shown with an error inline
  — check `isaac config validate` separately).
- **An unrecognized subcommand.** Only `list`, `lock`, and `unlock` exist;
  anything else prints "Unknown worksites subcommand" to stderr and exits
  non-zero rather than falling through to `list`.
