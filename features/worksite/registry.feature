Feature: Worksites — registry
  Worksites are named pools of working directories. Both config forms are
  valid and merge: root :worksites in isaac.edn AND entity files under
  config/worksites/<name>.edn (:merge-root-entity? convention, like
  providers). :members is a required vector of absolute paths — a
  singleton is a 1-member pool.

  Protection is opt-in via the submitted :worksite turnstile (never
  ambient). Registration alone does not gate CLI or other null-turnstile
  turns. Unregistered directories are untouched.

  Background:
    Given an Isaac root at "isaac-state"

  @wip
  Scenario: worksites validate from both config forms; memberless is rejected
    Given config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    And the isaac EDN file "config/worksites/galley.edn" exists with:
      | path    | value                      |
      | members | ["/ships/cordelia/galley"] |
    When isaac is run with "config validate"
    Then the exit code is 0
    Given config file "isaac.edn" containing:
      """
      {:worksites {"dry-dock" {}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "dry-dock"
    And the stderr contains "members"
    And the exit code is 1

  @wip
  Scenario: worksites list shows merged registry with lock state
    Given config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    And the isaac EDN file "config/worksites/galley.edn" exists with:
      | path    | value                      |
      | members | ["/ships/cordelia/galley"] |
    When isaac is run with "help worksites"
    Then the stdout matches:
      | pattern                                    |
      | Usage: isaac worksites \[subcommand\]      |
      | list\s+List worksites and their lock state |
      | lock\s+                                    |
      | unlock\s+                                  |
    And the exit code is 0
    When isaac is run with "worksites list"
    Then the stdout matches:
      | pattern                                        |
      | chart-room\s+free\s+/ships/cordelia/chart-room |
      | galley\s+free\s+/ships/cordelia/galley         |
    And the exit code is 0

  @wip
  Scenario: turns outside any worksite sail through even when another worksite is locked
    Given default Grover setup
    And config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    And the following sessions exist:
      | name      | cwd                 |
      | open-seas | /ships/cordelia/bow |
    When isaac is run with "worksites lock chart-room"
    Given the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When isaac is run with "prompt -m 'Status?' --session open-seas"
    Then the stdout contains "Aye"
    And the exit code is 0
