Feature: Worksites — leases and locks
  A turn that names a worksite pool (--pool, or :resource-pools) leases
  one free member for the whole turn; the member's directory becomes the
  turn's cwd. When every member is leased or locked, the turn waits in
  the queue — busy means wait, never a refusal. Leases are DURABLE
  (file-based — CLI turns are separate processes) and released on every
  turn outcome. Operators lock and unlock a member by its path; an
  operator lock is never broken, while a lease held by a dead process is
  stolen on the next acquire. A CLI unlock is another process, so the
  server's queue picks the waiting turn up on its next tick. (isaac-npmp)

  Background:
    Given an Isaac root at "isaac-state"
    And default Grover setup
    And config file "isaac.edn" containing:
      """
      {:resource-pools {"decks" {:type    :worksite
                                 :members ["/ships/cordelia/chart-room" "/ships/cordelia/galley"]}}}
      """
    And the following sessions exist:
      | name   |
      | harbor |
      | jetty  |
      | quay   |

  Scenario: two members run two turns; a third waits and takes the first member released
    Given the following model responses are queued:
      | type | content | model | wait |
      | text | First   | echo  | true |
      | text | Second  | echo  | true |
      | text | Third   | echo  |      |
    When the user sends "berth one" on session "harbor" with resource pools "decks"
    And the user sends "berth two" on session "jetty" with resource pools "decks"
    And isaac is run with "prompt -m 'berth three' --session quay --pool decks"
    Then the stdout contains "held"
    And the exit code is 0
    When the turn ends on session "jetty"
    Then session "quay" has transcript matching:
      | type    | message.role | message.content | cwd                    |
      | message | user         | berth three     | /ships/cordelia/galley |
      | message | assistant    | Third           |                        |
    And session "harbor" has transcript matching:
      | type    | message.role | message.content | cwd                        |
      | message | user         | berth one       | /ships/cordelia/chart-room |
    And session "jetty" has transcript matching:
      | type    | message.role | message.content | cwd                    |
      | message | user         | berth two       | /ships/cordelia/galley |

  Scenario: one session's turns run in whichever member is free
    Given the following model responses are queued:
      | type | content        | model | wait |
      | text | Hold fast      | echo  | true |
      | text | Galley first   | echo  |      |
      | text | Chart room now | echo  |      |
    When the user sends "keep watch" on session "jetty" with resource pools "decks"
    And isaac is run with "prompt -m 'first leg' --session harbor --pool decks"
    Then the stdout contains "Galley first"
    When the turn ends on session "jetty"
    And isaac is run with "prompt -m 'second leg' --session harbor --pool decks"
    Then the stdout contains "Chart room now"
    And session "harbor" has transcript matching:
      | type    | message.role | message.content | cwd                        |
      | message | user         | first leg       | /ships/cordelia/galley     |
      | message | user         | second leg      | /ships/cordelia/chart-room |

  Scenario: an operator lock keeps its member out; with every member locked the turn waits
    Given the following model responses are queued:
      | type | content            | model |
      | text | Galley it is       | echo  |
      | text | Chart room at last | echo  |
    When isaac is run with "worksites lock /ships/cordelia/chart-room"
    And isaac is run with "prompt -m 'Stow the lines' --session harbor --pool decks"
    Then the stdout contains "Galley it is"
    And session "harbor" has transcript matching:
      | type    | message.role | message.content | cwd                    |
      | message | user         | Stow the lines  | /ships/cordelia/galley |
    When isaac is run with "worksites lock /ships/cordelia/galley"
    And isaac is run with "prompt -m 'Plot the course' --session jetty --pool decks"
    Then the stdout contains "held"
    And the exit code is 0
    When isaac is run with "worksites unlock /ships/cordelia/chart-room"
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then session "jetty" has transcript matching:
      | type    | message.role | message.content    | cwd                        |
      | message | user         | Plot the course    | /ships/cordelia/chart-room |
      | message | assistant    | Chart room at last |                            |

  Scenario: operators lock and unlock a member by its path
    When isaac is run with "worksites lock /ships/cordelia/chart-room"
    Then the stdout contains "locked /ships/cordelia/chart-room"
    And the exit code is 0
    When isaac is run with "worksites lock /ships/cordelia/chart-room"
    Then the stderr contains "already locked"
    And the exit code is 1
    When isaac is run with "worksites unlock /ships/cordelia/chart-room"
    Then the stdout contains "unlocked /ships/cordelia/chart-room"
    And the exit code is 0
    When isaac is run with "worksites unlock /ships/cordelia/chart-room"
    Then the stderr contains "not locked"
    And the exit code is 1
    When isaac is run with "worksites lock /ships/cordelia/bilge"
    Then the stderr contains "/ships/cordelia/bilge"
    And the stderr contains "not a worksite member"
    And the exit code is 1

  Scenario: a turn that fails still releases its member
    Given config file "isaac.edn" containing:
      """
      {:resource-pools {"decks" {:type :worksite :members ["/ships/cordelia/chart-room"]}}}
      """
    And the following model responses are queued:
      | type       | status | message          | model |
      | http-error | 400    | lamp oil spilled | echo  |
    When isaac is run with "prompt -m 'Plot the course' --session harbor --pool decks"
    Then the exit code is 1
    When isaac is run with "worksites list"
    Then the stdout matches:
      | pattern                                   |
      | decks\s+/ships/cordelia/chart-room\s+free |
    Given the following model responses are queued:
      | type | content    | model |
      | text | Calm again | echo  |
    When isaac is run with "prompt -m 'Try again' --session harbor --pool decks"
    Then the stdout contains "Calm again"
    And the exit code is 0

  Scenario: a dead process's lease is stolen; an operator lock is not
    Given config file "isaac.edn" containing:
      """
      {:resource-pools {"decks" {:type :worksite :members ["/ships/cordelia/chart-room"]}}}
      """
    And a stale turn lock holds worksite "/ships/cordelia/chart-room" with pid 999999
    And the following model responses are queued:
      | type | content       | model |
      | text | Course is set | echo  |
    When isaac is run with "prompt -m 'Plot the course' --session harbor --pool decks"
    Then the stdout contains "Course is set"
    And the exit code is 0
    When isaac is run with "worksites lock /ships/cordelia/chart-room"
    And isaac is run with "prompt -m 'Once more' --session harbor --pool decks"
    Then the stdout contains "held"
    And the exit code is 0

  @wip
  Scenario: a lock write that fails partway leaves no lock; the turn takes the next member (isaac-x3g4)
    Foreman pilot 1: on a real filesystem each failed acquisition left its
    lock behind, so one turn locked every member and then waited on itself.
    The suite's in-memory filesystem never reached that code, so this one
    runs on a real directory.
    Given the worksite locks live on the real filesystem
    And writing the lock for "/ships/cordelia/chart-room" fails partway
    And the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When isaac is run with "prompt -m 'berth one' --session harbor --pool decks"
    Then the exit code is 0
    And session "harbor" has transcript matching:
      | type    | message.role | message.content | cwd                    |
      | message | user         | berth one       | /ships/cordelia/galley |
      | message | assistant    | Aye             |                        |
    When isaac is run with "worksites list"
    Then the stdout matches:
      | pattern                                    |
      | decks\s+/ships/cordelia/chart-room\s+free  |
      | decks\s+/ships/cordelia/galley\s+free      |
