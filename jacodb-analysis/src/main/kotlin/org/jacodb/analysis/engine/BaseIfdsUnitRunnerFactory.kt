/*
 *  Copyright 2022 UnitTestBot contributors (utbot.org)
 * <p>
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * <p>
 *  http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.jacodb.analysis.engine

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jacodb.api.JcMethod
import org.jacodb.api.analysis.ApplicationGraph
import org.jacodb.api.analysis.JcApplicationGraph
import org.jacodb.api.cfg.JcInst
import java.util.concurrent.ConcurrentHashMap

/**
 * This is a basic [IfdsUnitRunnerFactory], which creates one [BaseIfdsUnitRunner] for each [newRunner] call.
 *
 * @property analyzerFactory used to build [Analyzer] instance, which then will be used by launched [BaseIfdsUnitRunner].
 */
class BaseIfdsUnitRunnerFactory(private val analyzerFactory: AnalyzerFactory) : IfdsUnitRunnerFactory {
    override fun <UnitType> newRunner(
        graph: JcApplicationGraph,
        manager: IfdsUnitManager<UnitType>,
        unitResolver: UnitResolver<UnitType>,
        unit: UnitType,
        startMethods: List<JcMethod>
    ): IfdsUnitRunner<UnitType> {
        val analyzer = analyzerFactory.newAnalyzer(graph)
        return BaseIfdsUnitRunner(graph, analyzer, manager, unitResolver, unit, startMethods)
    }
}

/**
 * Encapsulates launch of tabulation algorithm, described in RHS95, for one unit
 */
private class BaseIfdsUnitRunner<UnitType>(
    private val graph: ApplicationGraph<JcMethod, JcInst>,
    private val analyzer: Analyzer,
    private val manager: IfdsUnitManager<UnitType>,
    private val unitResolver: UnitResolver<UnitType>,
    unit: UnitType,
    private val startMethods: List<JcMethod>
) : AbstractIfdsUnitRunner<UnitType>(unit) {

    private val pathEdges: MutableSet<IfdsEdge> = ConcurrentHashMap.newKeySet()
    private val summaryEdges: MutableMap<IfdsVertex, MutableSet<IfdsVertex>> = mutableMapOf()
    private val callSitesOf: MutableMap<IfdsVertex, MutableSet<IfdsEdge>> = mutableMapOf()
    private val pathEdgesPreds: MutableMap<IfdsEdge, MutableSet<PathEdgePredecessor>> = ConcurrentHashMap()

    private val flowSpace = analyzer.flowFunctions

    /**
     * Queue containing all unprocessed path edges.
     */
    private val workList = Channel<IfdsEdge>(Channel.UNLIMITED)

    /**
     * This method should be called each time new path edge is observed.
     * It will check if the edge is new and, if success, add it to [workList]
     * and summarize all [SummaryFact]s produces by the edge.
     *
     * @param edge the new path edge
     * @param pred the description of predecessor of the edge
     */
    private suspend fun propagate(edge: IfdsEdge, pred: PathEdgePredecessor): Boolean {
        require(unitResolver.resolve(edge.method) == unit)

        pathEdgesPreds.computeIfAbsent(edge) {
            ConcurrentHashMap.newKeySet()
        }.add(pred)

        if (pathEdges.add(edge)) {
            workList.send(edge)
            analyzer.handleNewEdge(edge).forEach {
                manager.handleEvent(it, this)
            }
            return true
        }
        return false
    }

    private val JcMethod.isExtern: Boolean
        get() = unitResolver.resolve(this) != unit

    /**
     * Implementation of tabulation algorithm, based on RHS95. It slightly differs from the original in the following:
     *
     * - We do not analyze the whole supergraph (represented by [graph]), but only the methods that belong to our [unit];
     * - Path edges are added to [workList] not only by the main cycle, but they can also be obtained from [manager];
     * - By summary edge we understand the path edge from the start node of the method to its exit node;
     * - The supergraph is explored dynamically, and we do not inverse flow functions when new summary edge is found, i.e.
     * the extension from Chapter 4 of NLR10 is implemented.
     */
    private suspend fun runTabulationAlgorithm(): Unit = coroutineScope {
        while (isActive) {
            val curEdge = workList.tryReceive().getOrNull() ?: run {
                manager.handleEvent(QueueEmptinessChanged(true), this@BaseIfdsUnitRunner)
                workList.receive().also {
                    manager.handleEvent(QueueEmptinessChanged(false), this@BaseIfdsUnitRunner)
                }
            }

            val (u, v) = curEdge
            val (curVertex, curFact) = v

            val callees = graph.callees(curVertex).toList()
            val curVertexIsCall = callees.isNotEmpty()
            val curVertexIsExit = curVertex in graph.exitPoints(graph.methodOf(curVertex))

            if (curVertexIsCall) {
                for (returnSite in graph.successors(curVertex)) {
                    // Propagating through call-to-return-site edges (in RHS95 it is done in lines 17-19)
                    for (fact in flowSpace.obtainCallToReturnFlowFunction(curVertex, returnSite).compute(curFact)) {
                        val newEdge = IfdsEdge(u, IfdsVertex(returnSite, fact))
                        propagate(newEdge, PathEdgePredecessor(curEdge, PredecessorKind.Sequent))
                    }

                    for (callee in callees) {
                        val factsAtStart = flowSpace.obtainCallToStartFlowFunction(curVertex, callee).compute(curFact)
                        for (sPoint in graph.entryPoint(callee)) {
                            for (sFact in factsAtStart) {
                                val sVertex = IfdsVertex(sPoint, sFact)

                                val handleExitVertex: suspend (IfdsVertex) -> Unit = { (eStatement, eFact) ->
                                    val finalFacts = flowSpace
                                        .obtainExitToReturnSiteFlowFunction(curVertex, returnSite, eStatement)
                                        .compute(eFact)
                                    for (finalFact in finalFacts) {
                                        val summaryEdge = IfdsEdge(IfdsVertex(sPoint, sFact), IfdsVertex(eStatement, eFact))
                                        val newEdge = IfdsEdge(u, IfdsVertex(returnSite, finalFact))
                                        propagate(newEdge, PathEdgePredecessor(curEdge, PredecessorKind.ThroughSummary(summaryEdge)))
                                    }
                                }

                                if (callee.isExtern) {
                                    // Notify about cross-unit call
                                    analyzer.handleNewCrossUnitCall(CrossUnitCallFact(v, sVertex)).forEach {
                                        manager.handleEvent(it, this@BaseIfdsUnitRunner)
                                    }

                                    // Waiting for exit vertices and handling them
                                    val exitVertices = flow {
                                        manager.handleEvent(
                                            SubscriptionForSummaryEdges(callee, this@flow),
                                            this@BaseIfdsUnitRunner
                                        )
                                    }
                                    exitVertices
                                        .filter { it.u == sVertex }
                                        .map { it.v }
                                        .onEach(handleExitVertex)
                                        .launchIn(this)
                                } else {
                                    // Save info about call for summary-facts that will be found later
                                    callSitesOf.getOrPut(sVertex) { mutableSetOf() }.add(curEdge)

                                    // Initiating analysis for callee
                                    val nextEdge = IfdsEdge(sVertex, sVertex)
                                    propagate(nextEdge, PathEdgePredecessor(curEdge, PredecessorKind.CallToStart))

                                    // Handling already-found summary edges
                                    // .toList() is needed below to avoid ConcurrentModificationException
                                    for (exitVertex in summaryEdges[sVertex].orEmpty().toList()) {
                                        handleExitVertex(exitVertex)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (curVertexIsExit) {
                    // Propagating through newly found summary edge, similar to lines 22-31 of RHS95
                    // TODO: rewrite this in a more reactive way
                    for (callerEdge in callSitesOf[u].orEmpty()) {
                        val callerStatement = callerEdge.v.statement
                        for (returnSite in graph.successors(callerStatement)) {
                            for (returnSiteFact in flowSpace.obtainExitToReturnSiteFlowFunction(callerStatement, returnSite, curVertex).compute(curFact)) {
                                val returnSiteVertex = IfdsVertex(returnSite, returnSiteFact)
                                val newEdge = IfdsEdge(callerEdge.u, returnSiteVertex)
                                propagate(newEdge, PathEdgePredecessor(callerEdge, PredecessorKind.ThroughSummary(curEdge)))
                            }
                        }
                    }
                    summaryEdges.getOrPut(curEdge.u) { mutableSetOf() }.add(curEdge.v)
                }

                // Simple propagation through intraprocedural edge, as in lines 34-36 of RHS95
                // Note that generally speaking, exit vertices may have successors (in case of exceptional flow, etc.),
                // so this part should be done for exit vertices as well
                for (nextInst in graph.successors(curVertex)) {
                    val nextFacts = flowSpace.obtainSequentFlowFunction(curVertex, nextInst).compute(curFact)
                    for (nextFact in nextFacts) {
                        val newEdge = IfdsEdge(u, IfdsVertex(nextInst, nextFact))
                        propagate(newEdge, PathEdgePredecessor(curEdge, PredecessorKind.Sequent))
                    }
                }
            }
        }
    }

    private val ifdsResult: IfdsResult by lazy {
        val allEdges = pathEdges.toList()

        val resultFacts = allEdges.groupBy({ it.v.statement }) {
            it.v.domainFact
        }.mapValues { (_, facts) -> facts.toSet() }

        IfdsResult(allEdges, resultFacts, pathEdgesPreds)
    }

    /**
     * Performs some initialization and runs tabulation algorithm, sending all relevant events to [manager].
     */
    override suspend fun run() = coroutineScope {
        try {
            // Adding initial facts to workList
            for (method in startMethods) {
                require(unitResolver.resolve(method) == unit)
                for (sPoint in graph.entryPoint(method)) {
                    for (sFact in flowSpace.obtainPossibleStartFacts(sPoint)) {
                        val vertex = IfdsVertex(sPoint, sFact)
                        val edge = IfdsEdge(vertex, vertex)
                        propagate(edge, PathEdgePredecessor(edge, PredecessorKind.NoPredecessor))
                    }
                }
            }

            runTabulationAlgorithm()
        } finally {
            withContext(NonCancellable) {
                analyzer.handleIfdsResult(ifdsResult).forEach {
                    manager.handleEvent(it, this@BaseIfdsUnitRunner)
                }
            }
        }
    }

    override suspend fun submitNewEdge(edge: IfdsEdge) {
        propagate(edge, PathEdgePredecessor(edge, PredecessorKind.Unknown))
    }
}