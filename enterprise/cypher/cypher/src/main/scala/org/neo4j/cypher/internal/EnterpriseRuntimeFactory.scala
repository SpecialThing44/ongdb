/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal

import org.neo4j.cypher.internal.compatibility._
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.compiled.EnterpriseRuntimeContext
import org.neo4j.cypher.{CypherRuntimeOption, InvalidArgumentException}

object EnterpriseRuntimeFactory {

  val interpreted = new FallbackRuntime[EnterpriseRuntimeContext](List(ProcedureCallOrSchemaCommandRuntime, InterpretedRuntime), CypherRuntimeOption.interpreted)
  val slotted = new FallbackRuntime[EnterpriseRuntimeContext](List(ProcedureCallOrSchemaCommandRuntime, SlottedRuntime, InterpretedRuntime), CypherRuntimeOption.slotted)
  val compiled = new FallbackRuntime[EnterpriseRuntimeContext](List(ProcedureCallOrSchemaCommandRuntime, CompiledRuntime, SlottedRuntime, InterpretedRuntime), CypherRuntimeOption.compiled)
  //val morsel = new FallbackRuntime[EnterpriseRuntimeContext](List(ProcedureCallOrSchemaCommandRuntime, MorselRuntime), CypherRuntimeOption.morsel)
  val default = new FallbackRuntime[EnterpriseRuntimeContext](List(ProcedureCallOrSchemaCommandRuntime, CompiledRuntime, SlottedRuntime, InterpretedRuntime), CypherRuntimeOption.default)

  def getRuntime(runtimeName: CypherRuntimeOption, useErrorsOverWarnings: Boolean): CypherRuntime[EnterpriseRuntimeContext] =
    runtimeName match {
      case CypherRuntimeOption.interpreted => interpreted

      case CypherRuntimeOption.slotted if useErrorsOverWarnings => slotted

      case CypherRuntimeOption.slotted => slotted

      case CypherRuntimeOption.compiled if useErrorsOverWarnings => compiled

      case CypherRuntimeOption.compiled => compiled

//      case CypherRuntimeOption.morsel if useErrorsOverWarnings => morsel
//
//      case CypherRuntimeOption.morsel => morsel

      case CypherRuntimeOption.default => default
    }
}
