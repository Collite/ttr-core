// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttr.yaml

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Structural adapters must be deliberately updated when their grammar production changes. */
internal object GrammarCoverage {
    private val signatures =
        mapOf(
            "document" to "7e5476814f97df8385f655cedd25b2f3ee0fb6213e07e2ea6cdb9ada399ac56b",
            "securityBlock" to "811ef83cc0e41eda154428601f7b09504e8a022234c46023f08e417f0fb90655",
            "securityStatement" to "3fddf1c2e3a8c146cb25598177a4bd5399322009741484588fb3c4d08b239eda",
            "modelDirective" to "d87bbf0d4cb0b5649f877868b01e2cc8f6814a76c88eb7498f11535047f46c5d",
            "packageDecl" to "d1a79415e614f06b62dd390a895350e9d98cd8e66096c6052df295c86a33814d",
            "importDecl" to "8e663642245ff0a66b85ae9a28c5da9e0102d8bfbab85f5e97f904922cd58590",
            "graphBlock" to "196a26a57afeabad0f461e3e367d1e774f33dcd8d4ae3b7fa21963c509f1e6a2",
            "graphProperty" to "c727735075b2181db4a9934a0a1938a78b94ada450955bc0202f5a617465ef02",
            "graphObjectsProperty" to "a6795e0e79c541ec62d1635fe5ee73f5e3c956b3ae9aeef9989675717ed75b26",
            "id" to "dd3f081e98311c7874387539dbe8b5ce422d73b0d7a38a8456a8f2bd60553ad1",
            "qualifiedName" to "a56145270ce6b3bebd1dd012b73948677dd618d496488bc608a3cb43ce3547dd",
            "key" to "a56145270ce6b3bebd1dd012b73948677dd618d496488bc608a3cb43ce3547dd",
            "cnKey" to "4e08d978c5d2611c6fbb48d0d807bbb989cf22d2f49f9425819f8c4418de6d12",
            "autoKey" to "05a9daca42061f12ac7622c328da2093c78ac93c7f0b684f1af6d12e7d4217e9",
            "value" to "246aec4d030e3f2916ed2347f81b185264f2101de2a03c9f232015e39baa2067",
            "cnValue" to "5f4d5a5d546e1117ca0876d7afd703ffc37966939fbd7ad156d542bc96aaf288",
            "autoValue" to "a0b404fd766b838f18ccca2feccac9e6bc7949f735944c31a06976293e65fee3",
            "literal" to "718867274a488f8fc8854f7d2f53bf76d80302de429f5fe1967958855175401c",
            "object_" to "5df717ee696505306347723db912a9911609492598b0f893c696ade445bc5471",
            "propertyList" to "aa7c02efe223167a016e9df24cb58a1ce4493a9d3399b004e72cae85befe46b9",
            "propertyEntry" to "a1acd239a9608b2a6feee0efdfb80d97e7edca0ea04444a3092741bc953bcc35",
            "cnObject" to "9f7632021aabc2c26a9dec9b584224dab32d0988fbb3d0a78634b316958cad8c",
            "cnObjectEntry" to "254aa728de10aadab19bda178e3e1cf7fcb9640cf0545c8275d8d6210d717e9a",
            "autoObject" to "09ac0104a7febab7173079c6128f15a0df03df1cead817794dd9f6e234b4cfbf",
            "autoEntry" to "13ac9842085fc436cedf7034f81b0cb8aa57ee273754b631e3094525dc5b5582",
            "cnEntry" to "989d060f1b50027ddec92f47fca761d175456e64601fcd2788885167c362cf59",
            "functionCall" to "f3e715d62dd7a3fbab97f84f082205064da49b0fc4da2a55fead29dcfe39c359",
            "embeddedBlock" to "46ad10b479f131b730604ba1b05d74623f26902f73d995727f3bb53fa9a89f1d",
            "dataType" to "f11129ebc885184d384f8921e697e7f098fcf4641c65b17fd1d41033dc3dae5c",
            "dataTypeProperty" to "27a693aaebf9934199af5e2ceab6355d44c717f9124a72e6092bc77d8c026be3",
            "participant" to "7e2522990ea65bcc6eedfd27a9af737b8fac1424cb891362179e4b9f7f4a4a57",
            "durationLiteral" to "7d0e9d023aa55bf40abac422c5f259d5c1235d796123a06c499dd3ff3aca03b0",
            "taggedValue" to "50a01e184407a9295a75fb38e309c39670fef28a9948c6386d4b1e25c4793bc7",
            "callValue" to "f57eebfc62e07cd8639f827ad911a2215d8d85740f98476e917c2fe1c645f5dc",
            "rangeLiteral" to "059eaa51325bb355f267e3aad2ef5f2c15693a2693d6143dd2e966ae941307db",
            "restrictValue" to "592622ab8ec3f8a7626671d5fa2ac6796f62dcb057140e4dce8216f1bb50ce0f",
            "calcRef" to "ac7d4d3251a2409048e25e1bacc4c49c71882756d6b6b83ad52f160cdf2e732f",
            "calcArg" to "398b058840b721eb9c11dbf8e4073649dc0507a5e5c55e3984904c1a84215d84",
            "levelEntry" to "3e43ebb28c6b2f60635d219ecb038f02f98132e7d59143d527a9c1b6a6b73361",
            "matchMethodValue" to "f7579d9dd55e63f9aed0622b174ef4018eb1069aff21055577d359fa45089d89",
            "parameterDefList" to "9b8d3751669ff2d25ec2be8bc7b428d51ab0a77b48cd213b897c9d99e73bdd13",
            "columnDefList" to "42518ef6e19b6bc242b01b7fe08ced826779fd08891f65ef364b01c96ec4c308",
            "attributeDefList" to "f341a8b54ef7528916573f7c0856ad3762c8e0475719ecac99a03cf5eb8a44ec",
            "indexDefList" to "17bfaa6795a2db82a8ace514727ce17f6ceb747a175a1a6a6c793760a411a4bd",
            "constraintDefList" to "23420f63fddc4cbb6668c32fed0cdcba05f6ab9dae16562cc908ccfd7e49d6fe",
            "measureInlineList" to "6ab478949909897b4e2f7228f6c9dc99ebfd7f13450e1840e2b224a216d979f5",
            "bindingColumnValue" to "e435d38309b08a5c2b6237784214920557ff2fc3fedbebeb106f7cde077b1fd1",
            "valueLabelValue" to "2ea3883f5a22e4c52e3380f09d721cdde5b80c507a73b2a88e7157d7d43c3bc1",
            "localizedString" to "9183ec0334d541fd144b38f1c32859cf3597f7ef174daf33191e00ffe7f2070c",
            "localizedEntry" to "f8cc960a3cc5c1d74b5755fd5969936fe1443cbcc8ac8c03b5530ebeb9f2fdab",
            "localizedStringList" to "89080fd5d8de6ecda6b0db0b262f3cdc183370beb3ea3ece73540783c71590fa",
            "localizedStringListEntry" to "95c98b5488708b42710eac95af543e150f34d17f9fed3c4889d6055a0100bd2a",
            "valueLabelsBody" to "2c27933313f942dd70087753a9ea582f6ded3beb740b0716d4badc17413af253",
            "valueLabelEntry" to "998049b650127d20c4b89a1085993db3a512870d11eb805e007ecbba835d95a3",
            "valueLabelField" to "b1b58ea9eb286276f1bc63e20966926ec3e10a9e9a9eaf255f470be36b764e6c",
            "drillArgsMap" to "167cec6e706742313b68f840ca64beeb67f32a77786d763b72c739849f049499",
            "drillArgEntry" to "f8cc960a3cc5c1d74b5755fd5969936fe1443cbcc8ac8c03b5530ebeb9f2fdab",
            "bindingColumnMap" to "916a71f30cc9f143152ee32a0e59cb5fa6b882520b9256ee257357c927a3f409",
            "bindingColumnEntry" to "6de0d583fc4c167eab1eebee9db1b9ae0f620d4a4a5fff0c64ce858cdcd50e2b",
            "restrictBlock" to "29b0cf55a389e968abd24ff4919bdf63924904a7ca4bdb5d5f7d552ba56f46b1",
            "restrictClause" to "5e4164ad78065e1afad1ca42128a6713dbc244e6e8a2a57c95afc4f6ec3d9bc8",
            "membersBlock" to "184940230c34bd3a6ee01ca57a8d6ff75d43d5e80cba5b9ee20098a668f44a05",
            "memberEntry" to "9d2fcfaed62c8d193f38a6949ceebc0372209166053dff071b1d040aded5c6b0",
            "changeRoleMap" to "007bbcd258b3a9a504399cdfdcbab86a7c7a95d3e6f2a5b8fabec11be9268443",
            "changeRoleEntry" to "03b7a182b05afd0f99ea0d88306cb2b6f9fee3b5bb993a858a82feca8487ea61",
            "searchableProperty" to "6a40f8292f133e5f7692cd5f8fc9bc7f006820477d5e5ff7b0ea9c014c7b251a",
            "changeSemanticsProperty" to "e5b34578fbec3419122411d7e9e475bd84f4999556c00a85a3efb212ce838da5",
            "transitionProperty" to "4ff56cb49f8623e489795e2f587db9d4d93af1088afbc7b66123e6e1ebd6d52b",
            "setProperty" to "63b87b5ae4ec9b7cac1a14dbde1df663c45023b3114f86ab1da9ae4629fbae0b",
            "transitionsProperty" to "a604f2fec29ed153fd8dffe7db965b605c535b2c2c611938a51a418d0c5b9567",
            "stepsProperty" to "a0e4cfab9bb54c88838a3c17df49b4305a3a88a5f25f5a5aca74d2cf283dd6f9",
            "STRING_LITERAL" to "92e78002da842fa4036fdb3b7dd65957e6b7319c169d976da8ef910868ce6ff4",
            "TRIPLE_STRING_LITERAL" to "959ccac173ee589551f1b25fbad28289a0909014e6c668253e9ed02b4ae65e97",
            "TAGGED_BLOCK_LITERAL" to "6058abb38ab177224c0f59b9f434068bbb7958199bb8d002ab6883e8e6e72a81",
            "NUMBER_LITERAL" to "acf3c5f5d9d33a70af4941e3ee158ff17b162e7e12212d33b0899e06b0725922",
            "BOOLEAN_LITERAL" to "338ff94317b41740a8b96b5a815f84090614d447302627b93bf670885fd70705",
            "NULL_LITERAL" to "70e2c20cc983d3820acbecfbb8b6dfd0f91c017a5ceda2156ca06fed1f87d654",
            "stringLiteralForm" to "3e7013d774b220d55bcbce036ef5fe6f032c0829e5e23c8d1cff861e97a194df",
            "transitionTarget" to "78220365491941810c041fda2ca771150f25426765e75485c6c7cc1b7c8eb0b2",
            "transitionGuard" to "6118a6eecfdc3aca239b3ee30b7603a9bdcec620752c815af69f9fdd054dd785",
            "stepInline" to "ca3b7b3a2a972ee4336cf3122971dd6ae88f757dbb13fab771fae7c7ce6dda6d",
            "columnInline" to "c54ed925777957940fb980c3dd4e816193c121230e2dccc1fd9aa8c448e43dd6",
            "attributeInline" to "e924b3a00a725c98772b7bbdbd0ef713889a9af2e9298f3ef74455613dd9ad7c",
            "indexInline" to "eb828ea2b13025311e7e1cafd5e540c3c72b9334e41222b4d27b452db814736f",
            "constraintInline" to "5e27e66a7d410030a73045c7b838c5ac2cc07a312a29086042413a8e9a7d36f5",
            "transitionEntry" to "6ab12209bbe8afb9d1caaadff9951075d7311eae4352993caac412bc63cdacc9",
            "matchMethodAttr" to "c67ea0b34daa3c26ee2dceed5c9432eea3a93cb1a6332ec864af38b79de17572",
            "listOfStrings" to "5d8afee12f379fcbd29f8786d8587895934b294fca7bf85adf2f7c5545c7d20d",
            "listOfIds" to "6651d9b02ffddaadd8f5f4226f30975ead9db4ad6f01aaf49e0cc8708e9e9882",
            "list" to "e0b2362f59bdf155aa1df26f2fb9042d5108d811702cdd4b66853a6460cec759",
            "autoList" to "47284a397785087f7c07ede1f005d491fec39ca1281f2c3ccb303ccb34c00209",
            "cnList" to "05225ac66f3cb481e836ab18ca6101c54616c480b926e25c78019ee762dd22b4",
        )

    fun check(metadata: JsonObject = CanonicalGrammar.metadata) {
        val rules = metadata.getValue("rules").jsonObject
        signatures.forEach { (name, expected) ->
            val rule =
                rules
                    .getValue(name)
                    .jsonPrimitive.content
                    .replace(Regex("\\s"), "")
            val actual =
                MessageDigest
                    .getInstance(
                        "SHA-256",
                    ).digest(rule.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            require(actual == expected) { "Unhandled canonical grammar shape $name; update the adapter and its specs" }
        }
        val bodies = metadata.getValue("bodies").jsonObject
        val literals = metadata.getValue("literals").jsonObject
        val visited = mutableSetOf<String>()
        lateinit var name: (String) -> Unit

        fun expression(e: JsonElement) {
            val o = e.jsonObject
            when {
                "symbol" in o -> name(o.string("symbol"))
                "alt" in o -> o.getValue("alt").jsonArray.forEach(::expression)
                "node" in o -> expression(o.getValue("node"))
                else -> {
                    val seq = o.getValue("seq").jsonArray
                    require(
                        seq
                            .first()
                            .jsonObject["symbol"]
                            ?.jsonPrimitive
                            ?.content == "LBRACK" &&
                            seq
                                .last()
                                .jsonObject["symbol"]
                                ?.jsonPrimitive
                                ?.content == "RBRACK",
                    ) { "Unhandled canonical expression $e" }
                    val symbols =
                        CanonicalGrammar
                            .symbols(
                                e,
                            ).filter { it !in setOf("LBRACK", "RBRACK", "COMMA") }
                            .distinct()
                    require(symbols.size == 1) { "Unhandled heterogeneous canonical list $e" }
                    name(symbols.single())
                }
            }
        }
        name = { n ->
            if (visited.add(n)) {
                when {
                    n in bodies -> {
                        val b = bodies.getValue(n).jsonObject
                        b.getValue("properties").jsonArray.forEach { p ->
                            val property = p.jsonObject
                            if (property.string("rule") !in signatures) expression(property.getValue("value"))
                        }
                        b.getValue("freeForm").jsonArray.forEach { name(it.jsonObject.string("value")) }
                        b.getValue("members").jsonArray.forEach { name(it.jsonObject.string("body")) }
                        b["fields"]?.let { name(it.jsonObject.string("value")) }
                    }
                    n in signatures ||
                        n in literals ||
                        n in
                        setOf(
                            "typeValue",
                            "modelCode",
                            "stringLiteralForm",
                        )
                    -> Unit
                    n in rules -> expression(GrammarExpression.parse(rules.getValue(n).jsonPrimitive.content))
                    else -> error("Unhandled canonical value rule $n")
                }
            }
        }
        bodies.keys.forEach { name(it) }
        metadata.getValue("kinds").jsonArray.forEach { name(it.jsonObject.string("body")) }
    }
}
