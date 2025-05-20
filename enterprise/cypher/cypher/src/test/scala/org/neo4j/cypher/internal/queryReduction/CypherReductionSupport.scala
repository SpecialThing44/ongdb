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
/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.queryReduction

import org.junit.Rule
import org.neo4j.cypher.CypherPlannerOption.cost
import org.neo4j.cypher.CypherRuntimeOption.interpreted
import org.neo4j.cypher.CypherVersion.v3_5
import org.neo4j.cypher.internal.compatibility.CommunityRuntimeContextCreator
import org.neo4j.cypher.{CypherExpressionEngineOption, GraphIcing}
import org.neo4j.cypher.internal.compatibility.v3_5.WrappedMonitors
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.phases.CompilationState
import org.neo4j.cypher.internal.compiler.v3_5._
import org.neo4j.cypher.internal.compiler.v3_5.phases.{CompilationContains, LogicalPlanState}
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.idp.{IDPQueryGraphSolver, IDPQueryGraphSolverMonitor, SingleComponentPlanner, cartesianProductsOrValueJoins}
import org.neo4j.cypher.internal.compiler.v3_5.planner.logical.{CachedMetricsFactory, SimpleMetricsFactory}
import org.neo4j.cypher.internal.v3_5.ast._
import org.neo4j.cypher.internal.javacompat.GraphDatabaseCypherService
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.{Cardinalities, Solveds}
import org.neo4j.cypher.internal.planner.v3_5.spi.{IDPPlannerName, PlanContext, PlannerNameFor}
import org.neo4j.cypher.internal.queryReduction.DDmin.Oracle
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.runtime.interpreted.{CSVResources, TransactionBoundPlanContext, TransactionBoundQueryContext, TransactionalContextWrapper, ValueConversion}
import org.neo4j.cypher.internal.runtime.vectorized.dispatcher.SingleThreadedExecutor
import org.neo4j.cypher.internal.runtime.{InternalExecutionResult, NormalMode}
import org.neo4j.cypher.internal.spi.v3_5.codegen.GeneratedQueryStructure
import org.neo4j.cypher.internal.v3_5.ast.prettifier.{ExpressionStringifier, Prettifier}
import org.neo4j.cypher.internal.v3_5.ast.semantics.SemanticState
import org.neo4j.cypher.internal.v3_5.frontend.phases.{ASTRewriter, BaseContains, BaseState, CompilationPhases, If, PreparatoryRewriting, SemanticAnalysis, devNullLogger}
import org.neo4j.cypher.internal.v3_5.rewriting.RewriterStepSequencer
import org.neo4j.cypher.internal.v3_5.util.attribution.SequentialIdGen
import org.neo4j.cypher.internal.v3_5.util.test_helpers.{CypherFunSuite, CypherTestSupport}
import org.neo4j.cypher.internal.{CommunityCompilerFactory, CommunityRuntimeFactory, CypherConfiguration, ExecutionPlan, RewindableExecutionResult}
import org.neo4j.graphdb.DependencyResolver
import org.neo4j.internal.kernel.api.Transaction
import org.neo4j.internal.kernel.api.security.LoginContext
import org.neo4j.kernel.GraphDatabaseQueryService
import org.neo4j.kernel.configuration.Config
import org.neo4j.kernel.impl.coreapi.{InternalTransaction, PropertyContainerLocker}
import org.neo4j.kernel.impl.query.clientconnection.ClientConnectionInfo.EMBEDDED_CONNECTION
import org.neo4j.kernel.impl.query.{Neo4jTransactionalContextFactory, TransactionalContextFactory}
import org.neo4j.kernel.monitoring.Monitors
import org.neo4j.test.TestGraphDatabaseFactory
import org.neo4j.test.rule.{DatabaseRule, ImpermanentDatabaseRule}
import org.neo4j.time.FakeClock
import org.neo4j.values.virtual.VirtualValues.{EMPTY_MAP, EMPTY_MAP_WRAP}

import scala.util.Try

object CypherReductionSupport {
  private val stepSequencer = RewriterStepSequencer.newPlain _
  private val metricsFactory = CachedMetricsFactory(SimpleMetricsFactory)
  
  @Rule 
  var database = new ImpermanentDatabaseRule

  val graph = new GraphDatabaseCypherService(this.database.getGraphDatabaseAPI)
  val resolver: DependencyResolver = graph.getDependencyResolver

  val config: Config = resolver.resolveDependency(classOf[Config])

  private val cypherConfig = CypherConfiguration(
    queryCacheSize = 0,
    statsDivergenceCalculator = StatsDivergenceCalculator.divergenceNoDecayCalculator(0, 0),
    useErrorsOverWarnings = false,
    idpMaxTableSize = 128,
    idpIterationDuration = 1000,
    errorIfShortestPathFallbackUsedAtRuntime = false,
    errorIfShortestPathHasCommonNodesAtRuntime = false,
    legacyCsvQuoteEscaping = false,
    csvBufferSize = CSVResources.DEFAULT_BUFFER_SIZE,
    planWithMinimumCardinalityEstimates = true,
    lenientCreateRelationship = true,
    version = v3_5,
    planner = cost,
    runtime = interpreted,
    expressionEngineOption = CypherExpressionEngineOption.interpreted,
    workers = 1,
    morselSize = 5, doSchedulerTracing = false, waitTimeout = 100, recompilationLimit = 1).toCypherPlannerConfiguration()
  private val kernelMonitors = new Monitors
  private val compiler = CypherPlanner(WrappedMonitors(kernelMonitors), stepSequencer, metricsFactory, cypherConfig, defaultUpdateStrategy,
    FakeClock, CommunityRuntimeContextCreator)

  private val monitor = kernelMonitors.newMonitor(classOf[IDPQueryGraphSolverMonitor])
  private val searchMonitor = kernelMonitors.newMonitor(classOf[IndexSearchMonitor])
  private val singleComponentPlanner = SingleComponentPlanner(monitor)
  private val queryGraphSolver = IDPQueryGraphSolver(singleComponentPlanner, cartesianProductsOrValueJoins, monitor)

  val prettifier = Prettifier(ExpressionStringifier())
}

/**
  * Do not mixin GraphDatabaseTestSupport when using this object.
  */
trait CypherReductionSupport extends CypherTestSupport with GraphIcing {
  self: CypherFunSuite  =>

  private var graph: GraphDatabaseCypherService = _
  private var contextFactory: TransactionalContextFactory = _

  override protected def initTest() {
    super.initTest()
    graph = new GraphDatabaseCypherService(new TestGraphDatabaseFactory().newImpermanentDatabase())
    contextFactory = Neo4jTransactionalContextFactory.create(graph, new PropertyContainerLocker())
  }

  override protected def stopTest() {
    try {
      super.stopTest()
    }
    finally {
      if (graph != null) graph.shutdown()
    }
  }

  private val rewriting = PreparatoryRewriting andThen
    SemanticAnalysis(warn = true).adds(BaseContains[SemanticState])
  private val createExecPlan = ProcedureCallOrSchemaCommandPlanBuilder andThen
    If((s: CompilationState) => s.maybeExecutionPlan.isFailure)(
      CommunityCompilerFactory().create(None, CypherReductionSupport.config.useErrorsOverWarnings).adds(CompilationContains[ExecutionPlan]))

  def evaluate(query: String, executeBefore: Option[String] = None, enterprise: Boolean = false): RewindableExecutionResult = {
    val parsingBaseState = queryToParsingBaseState(query, enterprise)
    val statement = parsingBaseState.statement()
    produceResult(query, statement, parsingBaseState, executeBefore, enterprise)
  }

  def reduceQuery(query: String, executeBefore: Option[String] = None, enterprise: Boolean = false)(test: Oracle[Try[RewindableExecutionResult]]): String = {
    val oracle: Oracle[Try[(String, RewindableExecutionResult)]] = (tryTuple) => {
      val tryResult = tryTuple.map(_._2)
      test(tryResult)
    }
    reduceQueryWithCurrentQueryText(query, executeBefore, enterprise)(oracle)
  }

  def reduceQueryWithCurrentQueryText(query: String, executeBefore: Option[String] = None, enterprise: Boolean = false)(test: Oracle[Try[(String, RewindableExecutionResult)]]): String = {
    val parsingBaseState = queryToParsingBaseState(query, enterprise)
    val statement = parsingBaseState.statement()

    val oracle: Oracle[Statement] = (currentStatement) => {
      // Actual query
      val currentlyRunQuery = CypherReductionSupport.prettifier.asString(currentStatement)
      val tryResults = Try((currentlyRunQuery, produceResult(query, currentStatement, parsingBaseState, executeBefore, enterprise)))
      val testRes = test(tryResults)
      testRes
    }

    val smallerStatement = GTRStar(new StatementGTRInput(statement))(oracle)
    CypherReductionSupport.prettifier.asString(smallerStatement)
  }

  private def queryToParsingBaseState(query: String, enterprise: Boolean): BaseState = {
    val startState = LogicalPlanState(query, None, PlannerNameFor(IDPPlannerName.name), new Solveds, new Cardinalities)
    val parsingContext = createContext(query, CypherReductionSupport.metricsFactory, CypherReductionSupport.config, null, null, enterprise)
    CompilationPhases.parsing(CypherReductionSupport.stepSequencer).transform(startState, parsingContext)
  }

  private def produceResult(query: String,
                            statement: Statement,
                            parsingBaseState: BaseState,
                            executeBefore: Option[String],
                            enterprise: Boolean): RewindableExecutionResult = {
    val explicitTx = graph.beginTransaction(Transaction.Type.explicit, LoginContext.AUTH_DISABLED)
    val implicitTx = graph.beginTransaction(Transaction.Type.`implicit`, LoginContext.AUTH_DISABLED)
    try {
      executeBefore match {
        case None =>
        case Some(setupQuery) =>
          val setupBS = queryToParsingBaseState(setupQuery, enterprise)
          val setupStm = setupBS.statement()
          executeInTx(setupQuery, setupStm, setupBS, implicitTx, enterprise)
      }
      executeInTx(query, statement, parsingBaseState, implicitTx, enterprise)
    } finally {
      explicitTx.failure()
      explicitTx.close()
    }
  }

  private def executeInTx(query: String, statement: Statement, parsingBaseState: BaseState, implicitTx: InternalTransaction, enterprise: Boolean): InternalExecutionResult = {
    val neo4jtxContext = contextFactory.newContext(EMBEDDED_CONNECTION, implicitTx, query, EMPTY_MAP_WRAP)
    val txContextWrapper = TransactionalContextWrapper(neo4jtxContext)
    val planContext = TransactionBoundPlanContext(txContextWrapper, devNullLogger)

    var baseState = parsingBaseState.withStatement(statement)
    val planningContext = createContext(query, CypherReductionSupport.metricsFactory, CypherReductionSupport.config, planContext, CypherReductionSupport.queryGraphSolver, enterprise)


    baseState = rewriting.transform(baseState, planningContext)

    val logicalPlanState = CypherReductionSupport.compiler.planPreparedQuery(baseState, planningContext)


    val compilationState = createExecPlan.transform(logicalPlanState, planningContext)
    val executionPlan = compilationState.maybeExecutionPlan.get
    val queryContext = new TransactionBoundQueryContext(txContextWrapper)(CypherReductionSupport.searchMonitor)

    RewindableExecutionResult(executionPlan.run(queryContext, NormalMode, ValueConversion.asValues(baseState.extractedParams())))
  }

  private def createContext(query: String, metricsFactory: CachedMetricsFactory,
                            config: CypherCompilerConfiguration,
                            planContext: PlanContext,
                            queryGraphSolver: IDPQueryGraphSolver, enterprise: Boolean) = {
    val logicalPlanIdGen = new SequentialIdGen()
    if (enterprise) {
      val dispatcher = new SingleThreadedExecutor(1)
      EnterpriseRuntimeContextCreator(GeneratedQueryStructure, dispatcher).create(NO_TRACING, devNullLogger, planContext, query, Set(),
        None, WrappedMonitors(new Monitors), metricsFactory, queryGraphSolver, config, defaultUpdateStrategy, CompilerEngineDelegator.CLOCK, logicalPlanIdGen, null)
    } else {
    CommunityRuntimeContextCreator.create(NO_TRACING, devNullLogger, planContext, query, Set(),
      None, WrappedMonitors(new Monitors), metricsFactory, queryGraphSolver, config = config, updateStrategy = defaultUpdateStrategy, clock = CompilerEngineDelegator.CLOCK, logicalPlanIdGen, evaluator = null)
    }
  }
}
