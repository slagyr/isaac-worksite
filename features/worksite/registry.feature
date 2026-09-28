@wip
Feature: Worksites — registry
  A worksite is a resource pool of working directories: a :resource-pools
  instance with :type :worksite and :members, a required vector of
  absolute paths (a singleton is a 1-member pool). Each member is leased
  to one turn at a time and binds that turn's :session/cwd; the first
  free member in config order wins. Sessions are independent of members.
  The old :worksites config key is gone. (isaac-npmp)

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: worksite pools validate; memberless, relative members, and the old key are rejected
    Given config file "isaac.edn" containing:
      """
      {:resource-pools {"decks" {:type    :worksite
                                 :members ["/ships/cordelia/chart-room" "/ships/cordelia/galley"]}}}
      """
    And the isaac EDN file "config/resource-pools/hold.edn" exists with:
      | path    | value                    |
      | type    | :worksite                |
      | members | ["/ships/cordelia/hold"] |
    When isaac is run with "config validate"
    Then the exit code is 0
    Given config file "isaac.edn" containing:
      """
      {:resource-pools {"dry-dock" {:type :worksite}
                        "bilge"    {:type :worksite :members ["bilge"]}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "dry-dock"
    And the stderr contains "members"
    And the stderr contains "bilge"
    And the stderr contains "absolute"
    And the exit code is 1
    Given config file "isaac.edn" containing:
      """
      {:worksites {"chart-room" {:members ["/ships/cordelia/chart-room"]}}}
      """
    When isaac is run with "config validate"
    Then the stderr contains "worksites"
    And the exit code is 1

  Scenario: worksites list shows each member's state
    Given default Grover setup
    And config file "isaac.edn" containing:
      """
      {:resource-pools {"decks" {:type    :worksite
                                 :members ["/ships/cordelia/chart-room" "/ships/cordelia/galley"]}}}
      """
    When isaac is run with "help worksites"
    Then the stdout matches:
      | pattern                                          |
      | Usage: isaac worksites \[subcommand\]            |
      | list\s+List worksite members and their state     |
      | lock\s+                                          |
      | unlock\s+                                        |
    And the exit code is 0
    Given the following sessions exist:
      | name   |
      | harbor |
    And the following model responses are queued:
      | type | content   | model | wait |
      | text | Hold fast | echo  | true |
    When the user sends "keep watch" on session "harbor" with resource pools "decks"
    And isaac is run with "worksites lock /ships/cordelia/galley"
    And isaac is run with "worksites list"
    Then the stdout matches:
      | pattern                                                |
      | decks\s+/ships/cordelia/chart-room\s+leased \(harbor\) |
      | decks\s+/ships/cordelia/galley\s+locked \(operator\)   |
    And the exit code is 0
