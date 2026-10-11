// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.detect

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.tatrman.translator.codec.sql.ParseResult
import org.tatrman.translator.codec.sql.SqlParser

class TableIdentifierExtractorSpec :
    StringSpec({

        "single table from SELECT" {
            val sql = "SELECT id, name FROM ITEM_CATEGORY WHERE name LIKE 'E%'"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "multiple tables from JOIN" {
            val sql = "SELECT * FROM ITEM_CATEGORY t1 JOIN ITEM t2 ON t1.id = t2.category_id"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category", "item")
        }

        "tables from comma/implicit join" {
            val sql = "SELECT * FROM ITEM_CATEGORY, ITEM WHERE ITEM_CATEGORY.id = ITEM.category_id"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category", "item")
        }

        "aliased table with AS" {
            val sql = "SELECT t.id, t.name FROM ITEM_CATEGORY AS t WHERE t.name LIKE 'E%'"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "aliased table without AS" {
            val sql = "SELECT t.id, t.name FROM ITEM_CATEGORY t WHERE t.name LIKE 'E%'"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "subquery in FROM" {
            val sql = "SELECT * FROM (SELECT id, name FROM ITEM_CATEGORY) AS subq"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "CTE with WITH" {
            val sql = "WITH c AS (SELECT id, name FROM ITEM_CATEGORY) SELECT * FROM c"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "CTE excluded from FROM" {
            val sql = "WITH c AS (SELECT id FROM ITEM_CATEGORY) SELECT * FROM c"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "UNION of two selects" {
            val sql = "SELECT id FROM ITEM_CATEGORY UNION SELECT id FROM ITEM"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category", "item")
        }

        "ORDER BY wrapped query" {
            val sql = "SELECT * FROM ITEM_CATEGORY ORDER BY name"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "qualified table name uses last part" {
            val sql = "SELECT id FROM dbo.ITEM_CATEGORY"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "qualified join keeps both last parts" {
            val sql = "SELECT * FROM dbo.ITEM_CATEGORY a JOIN dbo.ITEM b ON a.id = b.category_id"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("item_category", "item")
        }
    })
