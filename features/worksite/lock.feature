Feature: Worksites — locks
  The worksite member is the concurrency mutex for submitters that opt in
  via `--turnstile worksite` (infer member from session cwd) or
  `--turnstile worksite:<name>` (explicit). Locks are DURABLE (file-based —
  CLI turns are separate processes): operator locks via the CLI; turn locks
  are taken at admit through the :worksite turnstile and released by
  guaranteed turn finalization (isaac-bbov) on every outcome.

  CLI defaults to the null turnstile — the human at the keyboard keeps
  working while `worksites lock` means robots out. A locked member
  returns {:refuse :worksite-busy} to opted-in submitters (CLI: stderr +
  exit 1; hail defers on the reason). Operator locks are NEVER auto-broken.
  Turn locks from dead processes are broken on demand (pid liveness —
  worksites are per-host).

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: operators can lock and unlock a worksite
    Given config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    When isaac is run with "worksites lock chart-room"
    Then the stdout contains "locked chart-room"
    And the exit code is 0
    When isaac is run with "worksites list"
    Then the stdout matches:
      | pattern                             |
      | chart-room\s+locked \(operator\)\s+ |
    When isaac is run with "worksites lock chart-room"
    Then the stderr contains "already locked"
    And the exit code is 1
    When isaac is run with "worksites unlock chart-room"
    Then the stdout contains "unlocked chart-room"
    And the exit code is 0
    When isaac is run with "worksites list"
    Then the stdout matches:
      | pattern           |
      | chart-room\s+free |
    When isaac is run with "worksites unlock chart-room"
    Then the stderr contains "not locked"
    And the exit code is 1

  Scenario: locked member refuses --turnstile worksite; bare prompt still runs
    Given default Grover setup
    And config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    And the following sessions exist:
      | name      | cwd                        |
      | helm-chat | /ships/cordelia/chart-room |
    When isaac is run with "worksites lock chart-room"
    Then the exit code is 0
    Given the following model responses are queued:
      | type | content       | model |
      | text | Course is set | echo  |
    When isaac is run with "prompt -m 'Plot the course' --session helm-chat --turnstile worksite"
    Then the stderr contains "chart-room"
    And the stderr contains "locked"
    And the exit code is 1
    When isaac is run with "prompt -m 'Plot the course' --session helm-chat"
    Then the stdout contains "Course is set"
    And the exit code is 0
    When isaac is run with "worksites unlock chart-room"
    Then the exit code is 0
    Given the following model responses are queued:
      | type | content     | model |
      | text | Under way   | echo  |
    When isaac is run with "prompt -m 'Plot the course' --session helm-chat --turnstile worksite"
    Then the stdout contains "Under way"
    And the exit code is 0

  Scenario: turn lock taken by --turnstile worksite releases at turn end
    Given default Grover setup
    And config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    And the following sessions exist:
      | name      | cwd                        |
      | helm-chat | /ships/cordelia/chart-room |
    And the following model responses are queued:
      | type | content        | model |
      | text | Course is set  | echo  |
      | text | Anchor is down | echo  |
    When isaac is run with "prompt -m 'Plot the course' --session helm-chat --turnstile worksite"
    Then the stdout contains "Course is set"
    And the exit code is 0
    When isaac is run with "worksites list"
    Then the stdout matches:
      | pattern           |
      | chart-room\s+free |
    When isaac is run with "prompt -m 'Drop anchor' --session helm-chat --turnstile worksite"
    Then the stdout contains "Anchor is down"
    And the exit code is 0

  Scenario: a turn that fails still releases the worksite
    Given default Grover setup
    And config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    And the following sessions exist:
      | name      | cwd                        |
      | helm-chat | /ships/cordelia/chart-room |
    And the following model responses are queued:
      | type       | status | message      | model |
      | http-error | 403    | token buried | echo  |
    When isaac is run with "prompt -m 'Plot the course' --session helm-chat --turnstile worksite"
    Then the exit code is 1
    When isaac is run with "worksites list"
    Then the stdout matches:
      | pattern           |
      | chart-room\s+free |
    Given the following model responses are queued:
      | type | content    | model |
      | text | Calm again | echo  |
    When isaac is run with "prompt -m 'Try again' --session helm-chat --turnstile worksite"
    Then the stdout contains "Calm again"
    And the exit code is 0

  Scenario: dead-pid turn lock is stolen with --turnstile worksite; operator locks are not
    Given default Grover setup
    And config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    And the following sessions exist:
      | name      | cwd                        |
      | helm-chat | /ships/cordelia/chart-room |
    And a stale turn lock holds worksite "chart-room" with pid 999999
    And the following model responses are queued:
      | type | content       | model |
      | text | Course is set | echo  |
    When isaac is run with "prompt -m 'Plot the course' --session helm-chat --turnstile worksite"
    Then the stdout contains "Course is set"
    And the exit code is 0
    When isaac is run with "worksites lock chart-room"
    Then the exit code is 0
    When isaac is run with "prompt -m 'Once more' --session helm-chat --turnstile worksite"
    Then the stderr contains "locked"
    And the exit code is 1
