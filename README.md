# isaac-worksite

Worksite registry, locks, and pools — named working directories for isaac crews.

A **worksite** is a named pool of working directories (a singleton is a
1-member pool). Hails and config refer to worksites by *logical name*; the
per-host registry resolves names to local paths. The worksite entry is the
concurrency **mutex**: isaac's dispatch gate takes a member's lock before a
turn runs in it, so no two turns ever mutate one directory at once.

Design record: `isaac` repo, beans `isaac-51xy` (decisions 36-38) and
`isaac-tdgt` (composition with the foreman). Vocabulary: *the foreman assigns
crews to worksites; hail carries the message.*

- **W1** — registry + config schema + dispatch-gate lock (singleton worksites)
- **W2** — pools ("any free member"), band `crew+pool` binding, `max-in-flight` retired
- **W3** — cross-process lock durability (lease vs server-routed CLI turns)

## Config

```edn
;; isaac.edn                         ;; or config/worksites/isaac-work.edn
{:worksites                          {:members ["/Users/zane/agents/isaac/work-1"]}
 {"isaac-work"
  {:members ["/Users/zane/agents/isaac/work-1"]}}}
```
