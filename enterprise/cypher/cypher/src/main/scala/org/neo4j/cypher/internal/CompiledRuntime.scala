/*
 * Copyright (c) 2018-2020 "Graph Foundation,"
 * Graph Foundation, Inc. [https://graphfoundation.org]
 *
 * This file is part of ONgDB Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) as found
 * in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 */
package org.neo4j.cypher.internal

import org.neo4j.cypher.internal.compatibility.CypherRuntime
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.{CompiledRuntimeName, InterpretedPipeBuilderFactory, PipeExecutionPlanBuilder, RuntimeName}
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.compiled.{CompiledPlan, EnterpriseRuntimeContext}
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.compiled.codegen.{CodeGenConfiguration, CodeGenerator}
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.executionplan.{ExecutionPlan, ExecutionResultBuilderFactory, PeriodicCommitInfo, PipeInfo}
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.profiler.{InterpretedProfileInformation, Profiler}
import org.neo4j.cypher.internal.compiler.v3_5.phases.LogicalPlanState
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.ReadOnlies
import org.neo4j.cypher.internal.runtime.interpreted.UpdateCountingQueryContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.convert.{CommunityExpressionConverter, ExpressionConverters}
import org.neo4j.cypher.internal.runtime.interpreted.pipes.PipeExecutionBuilderContext
import org.neo4j.cypher.internal.runtime.{ExecutionMode, ExplainMode, ProfileMode, QueryContext}
import org.neo4j.cypher.internal.runtime.planDescription.Argument
import org.neo4j.cypher.internal.runtime.slotted.SlottedPipeBuilder
import org.neo4j.cypher.internal.runtime.slotted.expressions.SlottedExpressionConverters
import org.neo4j.cypher.internal.v3_5.frontend.PlannerName
import org.neo4j.cypher.internal.v3_5.logical.plans.IndexUsage
import org.neo4j.cypher.internal.v3_5.util.{InternalNotification, PeriodicCommitInOpenTransactionException}
import org.neo4j.cypher.result.RuntimeResult
import org.neo4j.values.virtual.MapValue

object CompiledRuntime extends CypherRuntime[EnterpriseRuntimeContext] {

  override def compileToExecutable(logicalPlanState: LogicalPlanState, context: EnterpriseRuntimeContext): ExecutionPlan = {
    val codeGen = new CodeGenerator(context.codeStructure, context.clock, CodeGenConfiguration(context.debugOptions))
    val readOnlies = new ReadOnlies
    logicalPlanState.planningAttributes.solveds.mapTo(readOnlies, _.readOnly)
    val cardinalities = logicalPlanState.planningAttributes.cardinalities
    val orders = logicalPlanState.planningAttributes.providedOrders

    val pipeBuildContext = PipeExecutionBuilderContext(logicalPlanState.semanticTable(), context.readOnly)
    val converters = new ExpressionConverters(CommunityExpressionConverter(context.tokenContext))
    val pipeBuilderFactory = InterpretedPipeBuilderFactory


    val executionPlanBuilder = new PipeExecutionPlanBuilder(expressionConverters = converters, pipeBuilderFactory = pipeBuilderFactory)

    val pipe = executionPlanBuilder
      .build(logicalPlanState.logicalPlan)(pipeBuildContext, context.tokenContext)

    val columns = logicalPlanState.statement().returnColumns
    val pipeInfo = PipeInfo(pipe, logicalPlanState.periodicCommit.map(x => PeriodicCommitInfo(x.batchSize)))
    val compiled: CompiledPlan = codeGen.generate(logicalPlanState.logicalPlan, logicalPlanState.semanticTable(), logicalPlanState.plannerName, readOnlies, cardinalities, orders, pipeInfo, columns)
    val resultBuilderFactory = compiled.executionResultBuilder
    val executionPlan: ExecutionPlan =
      new CompiledExecutionPlan(logicalPlanState.periodicCommit.map(x => PeriodicCommitInfo(x.batchSize)), resultBuilderFactory, CompiledRuntimeName, context.readOnly)
    executionPlan
  }

  class CompiledExecutionPlan(periodicCommit: Option[PeriodicCommitInfo],
                              resultBuilderFactory: ExecutionResultBuilderFactory,
                              override val runtimeName: RuntimeName,
                              readOnly: Boolean
                             ) extends ExecutionPlan {


    def runtimeUsed: RuntimeName = CompiledRuntimeName

    override def run(queryContext: QueryContext, planType: ExecutionMode, params: MapValue): RuntimeResult = {
      val doProfile = planType == ProfileMode
      val builderContext = if (!readOnly || doProfile) new UpdateCountingQueryContext(queryContext) else queryContext
      val builder = resultBuilderFactory.create(builderContext)

      val profileInformation = new InterpretedProfileInformation

      if (periodicCommit.isDefined && planType != ExplainMode) {
        if (!builderContext.transactionalContext.isTopLevelTx)
          throw new PeriodicCommitInOpenTransactionException()
        builder.setLoadCsvPeriodicCommitObserver(periodicCommit.get.batchRowCount)
      }

      if (doProfile)
        builder.setPipeDecorator(new Profiler(queryContext.transactionalContext.databaseInfo, profileInformation))

      builder.build(params,
        readOnly,
        profileInformation)
    }

    override def metadata: Seq[Argument] = Nil

    override def notifications: Set[InternalNotification] = Set.empty
  }
}
