# SPDX-License-Identifier: Apache-2.0
"""Execute the CN-P8 SQL oracles locally; Kotlin pins rendered bytes to these files.

SQLite supplies an independent window/aggregate engine without a deployed service.
The implementation-end PostgreSQL checks remain separately pending.
"""
import sqlite3
from pathlib import Path

GOLDENS = (Path(__file__).resolve().parents[4] / "packages/kotlin/ttr-concept/src/test/resources/goldens/things-in-time")


def test_bounded_episodes_first_later_termination_subject_isolation_and_open_end() -> None:
    with sqlite3.connect(":memory:") as db:
        db.executescript("""
            CREATE TABLE ticket(id TEXT);
            CREATE TABLE started(subject TEXT, occurred INTEGER);
            CREATE TABLE finished(subject TEXT, occurred INTEGER);
            INSERT INTO ticket VALUES ('A'), ('B'), ('C'), ('D'), ('E');
            INSERT INTO started VALUES ('A',1), ('A',4), ('B',2), ('C',5), ('E',10);
            INSERT INTO finished VALUES ('A',3), ('A',8), ('B',1), ('B',2), ('B',6), ('D',1);
        """)
        sql = GOLDENS.joinpath("open-ticket.sql").read_text().replace("{as_of}", ":as_of")
        expected = {2: ["A", "B"], 3: ["B"], 4: ["A", "B"], 6: ["A", "C"], 8: ["C"]}
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
