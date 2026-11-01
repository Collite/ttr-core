// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

internal enum class Dialect(
    val suffix: String,
) {
    C7("c7"),
    C8("c8"),
    ;

    fun choose(
        juel: String,
        feel: String,
    ): String = if (this == C7) juel else feel

    fun condition(value: String): String = choose("\${$value}", "=$value")

    private fun feelRetryAfter(response: String = "response"): String {
        val date =
            listOf(
                "parts:split(header,\" \")",
                "month:index of([\"Jan\",\"Feb\",\"Mar\",\"Apr\",\"May\",\"Jun\",\"Jul\",\"Aug\",\"Sep\",\"Oct\",\"Nov\",\"Dec\"],parts[3])[1]",
                "iso:if count(parts) = 6 and month != null and parts[6] = \"GMT\" then parts[4]+\"-\"+(if month < 10 then \"0\" else \"\")+string(month)+\"-\"+parts[2]+\"T\"+parts[5]+\"Z\" else null",
                "instant:if iso != null then date and time(iso) else null",
                "delay:if instant != null then max([1,ceiling((instant-now())/duration(\"PT1S\"))]) else 15",
            ).joinToString(",", "{", "}.delay")
        return "{header:if get value($response.headers,\"retry-after\") != null then " +
            "get value($response.headers,\"retry-after\") else get value($response.headers,\"Retry-After\"), " +
            "seconds:number(header), delay:if seconds != null and seconds > 0 then seconds " +
            "else if header != null then $date else 15}.delay"
    }

    fun objectValue(fields: Map<String, String>): String =
        choose(
            fields.entries.fold("S('{}')") { acc, (k, v) -> "$acc.prop(${quote(k)},$v)" },
            fields.entries.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:$v" },
        )

    fun array(vararg values: String): String =
        choose(values.fold("S('[]')") { a, v -> "$a.append($v)" }, values.joinToString(",", "[", "]"))

    fun field(
        variable: String,
        name: String,
    ): String = choose("$variable.prop(${quote(name)})", "$variable.$name")

    fun string(
        variable: String,
        name: String,
    ): String = choose("${field(variable, name)}.stringValue()", field(variable, name))

    fun number(
        variable: String,
        name: String,
    ): String = choose("${field(variable, name)}.numberValue()", field(variable, name))

    fun bool(
        variable: String,
        name: String,
    ): String = choose("${field(variable, name)}.boolValue()", field(variable, name))

    fun exists(
        variable: String,
        name: String,
    ): String =
        choose(
            "$variable.hasProp(${quote(name)}) && !$variable.prop(${quote(name)}).isNull()",
            "$variable.$name != null",
        )

    fun eq(
        a: String,
        b: String,
    ): String = choose("$a == $b", "$a = $b")

    fun and(
        a: String,
        b: String,
    ): String = "($a) ${choose("&&", "and")} ($b)"

    fun or(
        a: String,
        b: String,
    ): String = "($a) ${choose("||", "or")} ($b)"

    fun concat(vararg parts: String): String =
        choose(parts.drop(1).fold(parts.first()) { a, b -> "$a.concat($b)" }, parts.joinToString(" + "))

    fun mapping(
        inputs: Map<String, String> = emptyMap(),
        outputs: Map<String, String> = emptyMap(),
    ): Xml =
        if (this ==
            C7
        ) {
            Xml(
                "camunda:inputOutput",
                children =
                    inputs.map { (k, v) -> Xml("camunda:inputParameter", mapOf("name" to k), text = condition(v)) } +
                        outputs.map { (k, v) ->
                            Xml("camunda:outputParameter", mapOf("name" to k), text = condition(v))
                        },
            )
        } else {
            Xml(
                "zeebe:ioMapping",
                children =
                    inputs.map { (k, v) -> Xml("zeebe:input", mapOf("target" to k, "source" to condition(v))) } +
                        outputs.map { (k, v) -> Xml("zeebe:output", mapOf("target" to k, "source" to condition(v))) },
            )
        }

    fun rest(
        method: String,
        url: String,
        body: String?,
        result: String,
        retryBudgetSeconds: Long,
    ): List<Xml> =
        if (this == C7) {
            listOf(
                Xml(
                    "camunda:connector",
                    children =
                        listOf(
                            Xml("camunda:connectorId", text = "http-connector"),
                            Xml(
                                "camunda:inputOutput",
                                children =
                                    listOf(
                                        Xml("camunda:inputParameter", mapOf("name" to "method"), text = method),
                                        Xml("camunda:inputParameter", mapOf("name" to "url"), text = condition(url)),
                                        Xml(
                                            "camunda:inputParameter",
                                            mapOf("name" to "headers"),
                                            listOf(
                                                Xml(
                                                    "camunda:map",
                                                    children =
                                                        listOf(
                                                            Xml(
                                                                "camunda:entry",
                                                                mapOf("key" to "Authorization"),
                                                                text =
                                                                    "\${'Bearer '.concat(" +
                                                                        "TTR_CONN_AUTOMATION.token)}",
                                                            ),
                                                            Xml(
                                                                "camunda:entry",
                                                                mapOf("key" to "Content-Type"),
                                                                text = "application/json",
                                                            ),
                                                        ),
                                                ),
                                            ),
                                        ),
                                    ) +
                                        (
                                            body?.let {
                                                listOf(
                                                    Xml(
                                                        "camunda:inputParameter",
                                                        mapOf("name" to "payload"),
                                                        text = condition("($it).toString()"),
                                                    ),
                                                )
                                            }
                                                ?: emptyList()
                                        ) +
                                        listOf(
                                            Xml(
                                                "camunda:outputParameter",
                                                mapOf("name" to result),
                                                listOf(
                                                    Xml(
                                                        "camunda:script",
                                                        mapOf("scriptFormat" to "groovy"),
                                                        text =
                                                            """
                                                            def code = ((Number) statusCode).intValue()
                                                            if (code == 409) throw new org.camunda.bpm.engine.delegate.BpmnError('TTR_STALE', 'run API fingerprint refused')
                                                            if (code >= 400 && code < 500 && code != 408 && code != 429) throw new org.camunda.bpm.engine.delegate.BpmnError('TTR_REFUSED', 'run API HTTP ' + code + ': ' + response.toString().take(256))
                                                            if (code < 200 || code >= 300) throw new IllegalStateException('run API HTTP ' + code)
                                                            return org.camunda.spin.Spin.JSON(response)
                                                            """.trimIndent(),
                                                    ),
                                                ),
                                            ),
                                            Xml(
                                                "camunda:outputParameter",
                                                mapOf("name" to "${result}Code"),
                                                text = "\${statusCode}",
                                            ),
                                            Xml(
                                                "camunda:outputParameter",
                                                mapOf("name" to "${result}Headers"),
                                                text = "\${headers}",
                                            ),
                                            Xml(
                                                "camunda:outputParameter",
                                                mapOf("name" to "${result}RetryAfter"),
                                                listOf(
                                                    Xml(
                                                        "camunda:script",
                                                        mapOf(
                                                            "scriptFormat" to "groovy",
                                                        ),
                                                        text =
                                                            """
                                                            def value = headers['retry-after'] ?: headers['Retry-After']
                                                            try {
                                                                def seconds = new BigDecimal(value.toString())
                                                                return seconds > 0 ? seconds : 15
                                                            } catch (Exception ignored) {
                                                                try {
                                                                    def instant = java.time.ZonedDateTime.parse(value.toString(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                                                                    return Math.max(1L, (long) Math.ceil(java.time.Duration.between(java.time.Instant.now(), instant).toMillis() / 1000.0))
                                                                } catch (Exception absent) { return 15 }
                                                            }
                                                            """.trimIndent(),
                                                    ),
                                                ),
                                            ),
                                        ),
                            ),
                        ),
                ),
            )
        } else {
            listOf(
                Xml("zeebe:taskDefinition", mapOf("type" to "io.camunda:http-json:1", "retries" to "3")),
                Xml(
                    "zeebe:ioMapping",
                    children =
                        listOf(
                            Xml("zeebe:input", mapOf("target" to "url", "source" to condition(url))),
                            Xml("zeebe:input", mapOf("target" to "method", "source" to method)),
                            Xml("zeebe:input", mapOf("target" to "authentication.type", "source" to "bearer")),
                            Xml(
                                "zeebe:input",
                                mapOf(
                                    "target" to "authentication.token",
                                    "source" to "{{secrets.TTR_CONN_AUTOMATION}}",
                                ),
                            ),
                        ) +
                            (
                                body?.let {
                                    listOf(
                                        Xml(
                                            "zeebe:input",
                                            mapOf(
                                                "target" to "body",
                                                "source" to condition(it),
                                            ),
                                        ),
                                    )
                                }
                                    ?: emptyList()
                            ),
                ),
                Xml(
                    "zeebe:taskHeaders",
                    children =
                        listOf(
                            Xml(
                                "zeebe:header",
                                mapOf(
                                    "key" to "resultExpression",
                                    "value" to
                                        "={$result:response.body,${result}Code:response.status,${result}Headers:response.headers,${result}RetryAfter:${feelRetryAfter()}}",
                                ),
                            ),
                            Xml(
                                "zeebe:header",
                                mapOf(
                                    "key" to "errorExpression",
                                    "value" to
                                        "=if error.code = \"409\" then " +
                                        "bpmnError(\"TTR_STALE\", \"run API fingerprint refused\") else " +
                                        "if number(error.code) >= 400 and number(error.code) < 500 and " +
                                        "not(list contains([\"408\",\"429\"],error.code)) " +
                                        "then bpmnError(\"TTR_REFUSED\", \"run API HTTP \" + error.code) else " +
                                        "if list contains([\"408\",\"429\"],error.code) " +
                                        "or number(error.code) >= 500 then " +
                                        "jobError(\"run API HTTP \" + error.code, {}, job.retries - 1, " +
                                        "duration(\"PT\" + string(min([$retryBudgetSeconds,max([1,${feelRetryAfter(
                                            "error.variables.response",
                                        )}])])) + \"S\")) else null",
                                ),
                            ),
                            Xml("zeebe:header", mapOf("key" to "retryBackoff", "value" to "PT15S")),
                        ),
                ),
            )
        }
}
