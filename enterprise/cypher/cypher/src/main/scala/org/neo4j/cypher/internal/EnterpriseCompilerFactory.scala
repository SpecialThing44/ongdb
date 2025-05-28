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
package org.neo4j.cypher.internal

import org.neo4j.cypher.internal.compatibility.CypherCurrentCompiler
import org.neo4j.cypher.{CypherPlannerOption, CypherRuntimeOption, CypherUpdateStrategy, CypherVersion}
import org.neo4j.cypher.internal.compatibility.CypherRuntimeConfiguration
import org.neo4j.cypher.internal.compatibility.v3_5.Cypher35Planner
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.compiled.EnterpriseRuntimeContextCreator
import org.neo4j.cypher.internal.compiler.v3_5._
import org.neo4j.cypher.internal.runtime.interpreted.LastCommittedTxIdProvider
import org.neo4j.cypher.internal.runtime.vectorized.dispatcher.{ParallelDispatcher, SingleThreadedExecutor}
import org.neo4j.cypher.internal.spi.v3_5.codegen.GeneratedQueryStructure
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.kernel.GraphDatabaseQueryService
import org.neo4j.kernel.configuration.Config
import org.neo4j.kernel.monitoring.{Monitors => KernelMonitors}
import org.neo4j.logging.LogProvider
import org.neo4j.scheduler.JobScheduler

class EnterpriseCompilerFactory(communityCompilerFactory: CommunityCompilerFactory,
                                graph: GraphDatabaseQueryService,
                                kernelMonitors: KernelMonitors,
                                logProvider: LogProvider,
                                plannerConfig: CypherPlannerConfiguration,
                                runtimeConfig: CypherRuntimeConfiguration
                               ) extends CompilerFactory {

  override def createCompiler(cypherVersion: CypherVersion,
                              cypherPlanner: CypherPlannerOption,
                              cypherRuntime: CypherRuntimeOption,
                              cypherUpdateStrategy: CypherUpdateStrategy): Compiler = {
    if (cypherPlanner != CypherPlannerOption.rule) {
      val log = logProvider.getLog(getClass)
      val txIdProvider = LastCommittedTxIdProvider(graph)
      val planner = Cypher35Planner(plannerConfig, MasterCompiler.CLOCK, kernelMonitors, log, cypherPlanner, cypherUpdateStrategy, txIdProvider)
      val runtime = EnterpriseRuntimeFactory.getRuntime(cypherRuntime, plannerConfig.useErrorsOverWarnings)
      val settings = graph.getDependencyResolver.resolveDependency(classOf[Config])
      val morselSize: Int = settings.get(GraphDatabaseSettings.cypher_morsel_size)
      val workers: Int = settings.get(GraphDatabaseSettings.cypher_worker_count)
      val dispatcher =
        if (workers == 1) new SingleThreadedExecutor(morselSize)
        else {
          val numberOfThreads = if (workers == 0) Runtime.getRuntime.availableProcessors() else workers
          val jobScheduler = graph.getDependencyResolver.resolveDependency(classOf[JobScheduler])
          val executorService = jobScheduler.workStealingExecutor(JobScheduler.Groups.cypherWorker, numberOfThreads)

          new ParallelDispatcher(morselSize, numberOfThreads, executorService)
        }
      val contextCreator = EnterpriseRuntimeContextCreator(GeneratedQueryStructure, log, plannerConfig, dispatcher)
      CypherCurrentCompiler(planner, runtime, contextCreator, kernelMonitors)
    } else {
      communityCompilerFactory.createCompiler(cypherVersion, cypherPlanner, cypherRuntime, cypherUpdateStrategy)
    }
  }
}
