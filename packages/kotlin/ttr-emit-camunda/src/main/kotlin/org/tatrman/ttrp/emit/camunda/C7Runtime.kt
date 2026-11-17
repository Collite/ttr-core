// SPDX-License-Identifier: Apache-2.0
package org.tatrman.ttrp.emit.camunda

/** Connector output scopes bind `connector`, not `execution`; class lookup needs no script binding. */
internal object C7Runtime {
    private fun resolve(
        variable: String,
        suffix: String,
    ): String =
        "def $variable; try { $variable = Class.forName('org.cibseven.$suffix') } " +
            "catch (ClassNotFoundException e) { $variable = Class.forName('org.camunda.$suffix') }"

    val bpmnError = resolve("BpmnError", "bpm.engine.delegate.BpmnError")
    val spin = resolve("SpinClass", "spin.Spin")

    val engineIdentity: Map<String, String> =
        linkedMapOf(
            "instanceId" to "execution.processInstanceId",
            "businessKey" to "execution.processBusinessKey",
            "deploymentId" to
                "execution.processEngineServices.repositoryService.getProcessDefinition(execution.processDefinitionId).deploymentId",
        )

    val engineIdentityGroovy = engineIdentity.entries.joinToString(", ", "[", "]") { (key, value) -> "$key: $value" }
}
