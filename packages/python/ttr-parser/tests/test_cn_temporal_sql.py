# SPDX-License-Identifier: Apache-2.0
"""Execute the CN-P8 SQL oracles locally; Kotlin pins rendered bytes to these files.

SQLite supplies an independent engine without a deployed service.
The implementation-end PostgreSQL checks remain separately pending.
"""
import sqlite3
from pathlib import Path

GOLDENS = (Path(__file__).resolve().parents[4] / "packages/kotlin/ttr-concept/src/test/resources/goldens/things-in-time")


def test_bounded_episodes_close_at_the_first_termination_at_or_after_the_start() -> None:
    # Active at t iff some start s <= t has no termination f with s <= f <= t (A-CN-106, review-175 ⚑A: a
    # termination at the start's own instant closes it — [t, t) is empty):
    #   A  starts 1, 4; ends 3, 8   -> 2 yes (1, nothing in [1,2]); 3 no (3 in [1,3]); 4, 6 yes (4); 8 no (8 in [4,8])
    #   B  starts 2;    ends 1, 2, 6 -> never: the end at 2 closes the start at 2 (the end at 1 precedes it)
    #   C  starts 5                 -> 6, 8 yes (open, no termination)
    #   D  ends only; E starts at 10 -> never at these instants
    #   F  starts 1, 3; ends 3      -> 2 yes; from 3 on no: a restart at the instant of a termination is closed by
    #                                  that termination too (the cost ⚑A accepts)
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE ticket(id TEXT);
            CREATE TABLE started(subject TEXT, occurred INTEGER);
            CREATE TABLE finished(subject TEXT, occurred INTEGER);
            INSERT INTO ticket VALUES ('A'), ('B'), ('C'), ('D'), ('E'), ('F');
            INSERT INTO started VALUES ('A',1), ('A',4), ('B',2), ('C',5), ('E',10), ('F',1), ('F',3);
            INSERT INTO finished VALUES ('A',3), ('A',8), ('B',1), ('B',2), ('B',6), ('D',1), ('F',3);
        """)
        sql = GOLDENS.joinpath("open-ticket.sql").read_text().replace("{as_of}", ":as_of")
        expected = {2: ["A", "F"], 3: [], 4: ["A"], 6: ["A", "C"], 8: ["C"]}
        for instant, subjects in expected.items():
            assert sorted(r[0] for r in db.execute(sql, {"as_of": instant})) == subjects


def test_projection_selects_the_per_participant_and_valid_interval_with_null_when_absent() -> None:
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE item(id TEXT);
            CREATE TABLE price(item TEXT, channel TEXT, since TEXT, until TEXT, amount INTEGER);
            INSERT INTO item VALUES ('A'), ('B'), ('C');
            INSERT INTO price VALUES ('A','web','2026-10-01','2026-10-04',100),
                ('A','web','2026-10-05',NULL,120), ('A','store','2026-10-01',NULL,999),
                ('C','web','2026-10-01','2026-10-02',50);
        """)
        sql = GOLDENS.joinpath("quoted-price.sql").read_text().replace("{as_of}", ":as_of")
        assert sorted(db.execute(sql, {"as_of": "2026-10-03"})) == [("A", 100), ("B", None), ("C", None)]
        assert sorted(db.execute(sql, {"as_of": "2026-10-05"})) == [("A", 120), ("B", None), ("C", None)]
