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

package org.utbot.jcdb.api.cfg


/**
 * Basic block represents a list of instructions that:
 * - guaranteed to execute one after other during normal control flow
 * (i.e. no exceptions thrown)
 * - all have the same exception handlers (i.e. `jcGraph.catchers(inst)`
 * returns the same result for all instructions of the basic block)
 *
 * Because of the current implementation of basic block API, block is *not*
 * guaranteed to end with a terminating (i.e. `JcTerminatingInst` or `JcBranchingInst`) instruction.
 * However, any terminating instruction is guaranteed to be the last instruction of a basic block.
 */
class JcBasicBlock(val start: JcInstRef, val end: JcInstRef)

interface JcBlockGraph: Iterable<JcBasicBlock> {
    val jcGraph: JcGraph
    val basicBlocks: List<JcBasicBlock>
    val entry: JcBasicBlock
    val exits: List<JcBasicBlock>
    fun instructions(block: JcBasicBlock): List<JcInst>

    /**
     * `successors` and `predecessors` represent normal control flow
     */
    fun predecessors(block: JcBasicBlock): Set<JcBasicBlock>
    fun successors(block: JcBasicBlock): Set<JcBasicBlock>

    /**
     * `throwers` and `catchers` represent control flow when an exception occurs
     */
    fun catchers(block: JcBasicBlock): Set<JcBasicBlock>
    fun throwers(block: JcBasicBlock): Set<JcBasicBlock>
}