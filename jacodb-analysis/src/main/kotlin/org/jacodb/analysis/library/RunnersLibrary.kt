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

@file:JvmName("RunnersLibrary")
package org.jacodb.analysis.library

import org.jacodb.analysis.engine.BaseIfdsUnitRunnerFactory
import org.jacodb.analysis.engine.BidiIfdsUnitRunnerFactory
import org.jacodb.analysis.library.analyzers.AliasAnalyzerFactory
import org.jacodb.analysis.library.analyzers.NpeAnalyzerFactory
import org.jacodb.analysis.library.analyzers.NpePrecalcBackwardAnalyzerFactory
import org.jacodb.analysis.library.analyzers.SqlInjectionAnalyzerFactory
import org.jacodb.analysis.library.analyzers.SqlInjectionBackwardAnalyzerFactory
import org.jacodb.analysis.library.analyzers.TaintAnalysisNode
import org.jacodb.analysis.library.analyzers.TaintNode
import org.jacodb.analysis.library.analyzers.UnusedVariableAnalyzerFactory
import org.jacodb.api.cfg.JcExpr
import org.jacodb.api.cfg.JcInst

//TODO: add docs here
val UnusedVariableRunnerFactory = BaseIfdsUnitRunnerFactory(UnusedVariableAnalyzerFactory)

fun newSqlInjectionRunnerFactory(maxPathLength: Int = 5) = BidiIfdsUnitRunnerFactory(
    BaseIfdsUnitRunnerFactory(SqlInjectionAnalyzerFactory(maxPathLength)),
    BaseIfdsUnitRunnerFactory(SqlInjectionBackwardAnalyzerFactory(maxPathLength)),
)

fun newNpeRunnerFactory(maxPathLength: Int = 5) = BidiIfdsUnitRunnerFactory(
    BaseIfdsUnitRunnerFactory(NpeAnalyzerFactory(maxPathLength)),
    BaseIfdsUnitRunnerFactory(NpePrecalcBackwardAnalyzerFactory(maxPathLength)),
    isParallel = false
)

fun newAliasRunnerFactory(
    generates: (JcInst) -> List<TaintAnalysisNode>,
    sanitizes: (JcExpr, TaintNode) -> Boolean,
    sinks: (JcInst) -> List<TaintAnalysisNode>,
    maxPathLength: Int = 5
) = BaseIfdsUnitRunnerFactory(AliasAnalyzerFactory(generates, sanitizes, sinks, maxPathLength))