package org.neo4j.cypher.internal.compatibility.v3_5.runtime.compiled

import org.neo4j.cypher.internal.compatibility.v3_5.runtime.{ClosingQueryResultRecordIterator, ResultIterator}
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.PhysicalPlanningAttributes.SlotConfigurations
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.executionplan.{BaseExecutionResultBuilderFactory, ExecutionResultBuilder, PipeInfo}
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.interpreted.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.internal.runtime.slotted.SlottedQueryState
import org.neo4j.cypher.internal.v3_5.logical.plans.LogicalPlan
import org.neo4j.values.virtual.MapValue

import scala.collection.mutable

class CompiledExecutionResultBuilderFactory(pipeInfo: PipeInfo,
                                            columns: List[String],
                                            logicalPlan: LogicalPlan)
  extends BaseExecutionResultBuilderFactory(pipeInfo.pipe, false, columns, logicalPlan) {

  class CompiledExecutionWorkflowBuilder extends BaseExecutionWorkflowBuilder {
    override protected def createQueryState(params: MapValue): QueryState = {
      new QueryState(queryContext,
        externalResource,
        params,
        pipeDecorator,
        triadicState = mutable.Map.empty,
        repeatableReads = mutable.Map.empty,
        lenientCreateRelationship = false)
    }

    override def buildResultIterator(results: Iterator[ExecutionContext], isUpdating: Boolean): ResultIterator = {
      val closingIterator = new ClosingQueryResultRecordIterator(results, taskCloser, exceptionDecorator)
      val resultIterator = if (isUpdating) closingIterator.toEager else closingIterator
      resultIterator
    }

    override def queryContext: QueryContext = null
  }


  override def create(queryContext: QueryContext): ExecutionResultBuilder = new CompiledExecutionWorkflowBuilder()
}
