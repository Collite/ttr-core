// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translator.framework

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * MJ-P0·S1 — the "model joins" fixture: a DF-Partner-shaped slice of the ER model, entity and relation
 * names verbatim (Czech, diacritics), relations copied from ai-models `model/entities/bt05_prodeje.yaml`,
 * `bt03_dodaci_misto.yaml`, `bt02_artikl.yaml`.
 *
 * Shape that matters for the acceptance corpus (project MJ `plan/plan.md §4`):
 *  - `kumulovaný_prodej → dodací_místo`, `dodací_místo → zákazník`, `kumulovaný_prodej → produkt`: the hero
 *    chain `kp JOIN dm JOIN z JOIN p` resolves only if the second join is matched against `dm`, not `kp`;
 *  - `kumulovaný_prodej` has NO relation to `zákazník` (H7 — no path inference in v1);
 *  - `dodací_místo → uživatel` twice (OZ + VOT) — the ambiguous case (H4);
 *  - both `kumulovaný_prodej → obchodní_kanál` and `dodací_místo → obchodní_kanál` (H6 — no proximity tie-break);
 *  - a synthetic composite relation `smlouva ↔ dodatek` on `(číslo_smlouvy, rok)` (H9);
 *  - every entity maps to a DB table; `zákazník.id_zákazníka` is renamed on the way (`IDSUBJEKT`) so the
 *    MAP_TO_PHYSICAL alias-at-boundary path is exercised by a join key.
 */
object DfpJoinModel {
    fun er(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("entity")
            .setName(name)
            .build()

    fun attr(
        entity: String,
        attribute: String,
    ): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("attribute")
            .setName("$entity.$attribute")
            .build()

    fun db(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.DB)
            .setNamespace("dbo")
            .setName(name)
            .build()

    private fun int(
        name: String,
        key: Boolean = false,
    ) = ModelAttribute(name, SurfaceType.INT, nullable = !key, isKey = key)

    private fun text(name: String) = ModelAttribute(name, SurfaceType.TEXT)

    private fun num(name: String) = ModelAttribute(name, SurfaceType.FLOAT)

    val kumulovanyProdej =
        ModelEntity(
            er("kumulovaný_prodej"),
            listOf(
                int("id_dodacího_místa"),
                int("id_obchodního_kanálu"),
                int("id_tržní_skupiny"),
                int("id_produktu"),
                int("id_podproduktu"),
                int("id_artiklu"),
                text("období"),
                num("tržba_12_měsíců"),
                num("množství_12_měsíců"),
            ),
        )
    val dodaciMisto =
        ModelEntity(
            er("dodací_místo"),
            listOf(
                int("id_dodacího_místa", key = true),
                text("název_dodacího_místa"),
                int("id_subjektu"),
                int("id_obchodního_kanálu"),
                int("obchodní_zástupce"),
                int("vedoucí_obchodního_týmu"),
                text("město"),
            ),
        )
    val zakaznik =
        ModelEntity(
            er("zákazník"),
            listOf(
                int("id_zákazníka", key = true),
                text("název_zákazníka"),
                text("ičo"),
            ),
        )
    val produkt =
        ModelEntity(
            er("produkt"),
            listOf(
                int("id_produktu", key = true),
                text("název_produktu"),
            ),
        )
    val obchodniKanal =
        ModelEntity(
            er("obchodní_kanál"),
            listOf(
                int("id_obchodního_kanálu", key = true),
                text("název_obchodního_kanálu"),
                int("id_tržní_skupiny"),
            ),
        )
    val uzivatel =
        ModelEntity(
            er("uživatel"),
            listOf(
                int("id_uživatele", key = true),
                text("jméno"),
            ),
        )
    val artikl =
        ModelEntity(
            er("artikl"),
            listOf(
                int("id_artiklu", key = true),
                text("název_artiklu"),
                int("id_produktu"),
                text("měrná_jednotka"),
            ),
        )

    /** Composite-key pair for H9. */
    val smlouva =
        ModelEntity(
            er("smlouva"),
            listOf(int("číslo_smlouvy", key = true), int("rok", key = true), text("název_smlouvy")),
        )
    val dodatek =
        ModelEntity(
            er("dodatek"),
            listOf(int("id_dodatku", key = true), int("číslo_smlouvy"), int("rok"), text("text_dodatku")),
        )

    val entities =
        listOf(kumulovanyProdej, dodaciMisto, zakaznik, produkt, obchodniKanal, uzivatel, artikl, smlouva, dodatek)

    private fun rel(
        from: String,
        to: String,
        vararg pairs: Pair<String, String>,
    ) = ModelRelation(
        fromEntity = er(from),
        toEntity = er(to),
        joinPairs = pairs.map { (f, t) -> attr(from, f) to attr(to, t) },
    )

    val relations =
        listOf(
            // bt05_prodeje.yaml
            rel("kumulovaný_prodej", "dodací_místo", "id_dodacího_místa" to "id_dodacího_místa"),
            rel("kumulovaný_prodej", "obchodní_kanál", "id_obchodního_kanálu" to "id_obchodního_kanálu"),
            rel("kumulovaný_prodej", "produkt", "id_produktu" to "id_produktu"),
            rel("kumulovaný_prodej", "artikl", "id_artiklu" to "id_artiklu"),
            // bt03_dodaci_misto.yaml
            rel("dodací_místo", "zákazník", "id_subjektu" to "id_zákazníka"),
            rel("dodací_místo", "obchodní_kanál", "id_obchodního_kanálu" to "id_obchodního_kanálu"),
            rel("dodací_místo", "uživatel", "obchodní_zástupce" to "id_uživatele"),
            rel("dodací_místo", "uživatel", "vedoucí_obchodního_týmu" to "id_uživatele"),
            // bt02_artikl.yaml
            rel("artikl", "produkt", "id_produktu" to "id_produktu"),
            // synthetic composite (H9)
            rel("dodatek", "smlouva", "číslo_smlouvy" to "číslo_smlouvy", "rok" to "rok"),
        )

    // ---- physical side: one table per entity, same column names except the renamed customer key ----

    private fun table(
        entity: ModelEntity,
        tableName: String,
        renames: Map<String, String> = emptyMap(),
    ): ModelTable =
        ModelTable(
            qname = db(tableName),
            columns =
                entity.attributes.map { a ->
                    ModelColumn(renames[a.name] ?: a.name, a.surfaceType, a.nullable)
                },
        )

    private val tableNames =
        mapOf(
            "kumulovaný_prodej" to "QKUMPRODEJ",
            "dodací_místo" to "QDODMISTO",
            "zákazník" to "QSUBJEKT",
            "produkt" to "QPRODUKT",
            "obchodní_kanál" to "QOBCHKANAL",
            "uživatel" to "QUZIVATEL",
            "artikl" to "QARTIKL",
            "smlouva" to "QSMLOUVA",
            "dodatek" to "QDODATEK",
        )

    /** `zákazník.id_zákazníka` is stored as `IDSUBJEKT` — a join key that crosses a rename. */
    val renames: Map<QualifiedName, Map<String, String>> =
        mapOf(er("zákazník") to mapOf("id_zákazníka" to "IDSUBJEKT"))

    val tables = entities.map { e -> table(e, tableNames.getValue(e.qname.name), renames[e.qname] ?: emptyMap()) }

    val entityMappings: Map<QualifiedName, EntityMapping> =
        entities.associate { e -> e.qname to EntityMapping.ToTable(db(tableNames.getValue(e.qname.name))) }

    fun handle(): InMemoryModelHandle =
        InMemoryModelHandle(
            tables = tables,
            entities = entities,
            relations = relations,
            entityMappings = entityMappings,
            attributeRenames = renames,
        )

    // ---- the acceptance corpus (project MJ plan.md §4) ----

    /** H1 — the df-test log's SQL, verbatim (2026-09-21 06:56Z). */
    const val H1_HERO: String =
        "SELECT zákazník.název_zákazníka, SUM(kumulovaný_prodej.tržba_12_měsíců) AS tržba_12_měsíců " +
            "FROM kumulovaný_prodej JOIN dodací_místo JOIN zákazník JOIN produkt " +
            "WHERE produkt.název_produktu LIKE '%Oleje%' " +
            "GROUP BY zákazník.název_zákazníka ORDER BY tržba_12_měsíců DESC FETCH FIRST 10 ROWS ONLY"

    /** H2 — aliases. */
    const val H2_ALIASES: String =
        "SELECT z.název_zákazníka, SUM(kp.tržba_12_měsíců) AS t " +
            "FROM kumulovaný_prodej kp JOIN dodací_místo dm JOIN zákazník z GROUP BY z.název_zákazníka"

    /** H3 — join type preserved. */
    const val H3_LEFT: String =
        "SELECT kumulovaný_prodej.období, produkt.název_produktu FROM kumulovaný_prodej LEFT JOIN produkt"

    /** H10 — the comma form the joiner already handles today (bytes must not change). */
    const val H10_COMMA: String =
        "SELECT kumulovaný_prodej.období, produkt.název_produktu FROM kumulovaný_prodej, produkt " +
            "WHERE produkt.název_produktu LIKE '%Oleje%'"
}
