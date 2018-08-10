/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.apache.samza.sql.runner;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.rel.BiRel;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.core.TableModify;
import org.apache.commons.lang3.Validate;
import org.apache.samza.application.StreamApplication;
import org.apache.samza.config.Config;
import org.apache.samza.config.MapConfig;
import org.apache.samza.job.ApplicationStatus;
import org.apache.samza.runtime.AbstractApplicationRunner;
import org.apache.samza.runtime.ApplicationRunner;
import org.apache.samza.runtime.LocalApplicationRunner;
import org.apache.samza.runtime.RemoteApplicationRunner;
import org.apache.samza.sql.dsl.SamzaSqlDslConverterFactory;
import org.apache.samza.sql.interfaces.DslConverter;
import org.apache.samza.sql.interfaces.DslConverterFactory;
import org.apache.samza.sql.interfaces.SqlIOResolver;
import org.apache.samza.sql.interfaces.SqlIOConfig;
import org.apache.samza.sql.planner.QueryPlanner;
import org.apache.samza.sql.testutil.SamzaSqlQueryParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Application runner implementation for SamzaSqlApplication.
 * SamzaSqlApplication needs SamzaSqlConfigRewriter to infer some of the configs from SQL statements.
 * Since Samza's config rewriting capability is available only in the RemoteApplicationRunner.
 * This runner invokes the SamzaSqlConfig re-writer if it is invoked on a standalone mode (i.e. localRunner == true)
 * otherwise directly calls the RemoteApplicationRunner which automatically performs the config rewriting .
 */
public class SamzaSqlApplicationRunner extends AbstractApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(SamzaSqlApplicationRunner.class);

  private final ApplicationRunner appRunner;
  private final Boolean localRunner;

  public static final String RUNNER_CONFIG = "app.runner.class";
  public static final String CFG_FMT_SAMZA_STREAM_SYSTEM = "streams.%s.samza.system";

  public SamzaSqlApplicationRunner(Config config) {
    this(false, config);
  }

  public SamzaSqlApplicationRunner(Boolean localRunner, Config config) {
    super(config);
    this.localRunner = localRunner;
    appRunner = ApplicationRunner.fromConfig(computeSamzaConfigs(localRunner, config));
  }

  public static Config computeSamzaConfigs(Boolean localRunner, Config config) {
    Map<String, String> newConfig = new HashMap<>();

    List<String> dslStmts = SamzaSqlApplicationConfig.fetchSqlFromConfig(config);

    // TODO: Get the converter factory based on the file type. Create abstraction around this.
    DslConverterFactory dslConverterFactory = new SamzaSqlDslConverterFactory();
    DslConverter dslConverter = dslConverterFactory.create(config);
    Collection<RelRoot> relRoots = dslConverter.convertDsl(String.join("\n", dslStmts));

    Set<String> inputSystemStreams = new HashSet<>();
    Set<String> outputSystemStreams = new HashSet<>();

    populateSystemStreams(relRoots.iterator().next().project(), inputSystemStreams, outputSystemStreams);

    SqlIOResolver ioResolver = SamzaSqlApplicationConfig.createIOResolver(config);

    // This is needed because the SQL file may not be available in all the node managers.
    String sqlJson = SamzaSqlApplicationConfig.serializeSqlStmts(dslStmts);
    newConfig.put(SamzaSqlApplicationConfig.CFG_SQL_STMTS_JSON, sqlJson);

    // Populate stream to system mapping config for input and output system streams
    for (String source : inputSystemStreams) {
      SqlIOConfig inputSystemStreamConfig = ioResolver.fetchSourceInfo(source);
      newConfig.put(String.format(CFG_FMT_SAMZA_STREAM_SYSTEM, inputSystemStreamConfig.getStreamName()),
          inputSystemStreamConfig.getSystemName());
      newConfig.putAll(inputSystemStreamConfig.getConfig());
    }

    for (String sink : outputSystemStreams) {
      SqlIOConfig outputSystemStreamConfig = ioResolver.fetchSinkInfo(sink);
      newConfig.put(String.format(CFG_FMT_SAMZA_STREAM_SYSTEM, outputSystemStreamConfig.getStreamName()),
          outputSystemStreamConfig.getSystemName());
      newConfig.putAll(outputSystemStreamConfig.getConfig());
    }

    /*
    List<SamzaSqlQueryParser.QueryInfo> queryInfo = SamzaSqlApplicationConfig.fetchQueryInfo(sqlStmts);
    for (SamzaSqlQueryParser.QueryInfo query : queryInfo) {
      // Populate stream to system mapping config for input and output system streams
      for (String inputSource : query.getSources()) {
        SqlIOConfig inputSystemStreamConfig = ioResolver.fetchSourceInfo(inputSource);
        newConfig.put(String.format(CFG_FMT_SAMZA_STREAM_SYSTEM, inputSystemStreamConfig.getStreamName()),
            inputSystemStreamConfig.getSystemName());
        newConfig.putAll(inputSystemStreamConfig.getConfig());
      }

      SqlIOConfig outputSystemStreamConfig = ioResolver.fetchSinkInfo(query.getSink());
      newConfig.put(String.format(CFG_FMT_SAMZA_STREAM_SYSTEM, outputSystemStreamConfig.getStreamName()),
          outputSystemStreamConfig.getSystemName());
      newConfig.putAll(outputSystemStreamConfig.getConfig());
    }
    */

    newConfig.putAll(config);

    if (localRunner) {
      newConfig.put(RUNNER_CONFIG, LocalApplicationRunner.class.getName());
    } else {
      newConfig.put(RUNNER_CONFIG, RemoteApplicationRunner.class.getName());
    }

    LOG.info("New Samza configs: " + newConfig);
    return new MapConfig(newConfig);
  }

  public void runAndWaitForFinish() {
    Validate.isTrue(localRunner, "This method can be called only in standalone mode.");
    SamzaSqlApplication app = new SamzaSqlApplication();
    run(app);
    appRunner.waitForFinish();
  }

  @Override
  public void runTask() {
    appRunner.runTask();
  }

  @Override
  public void run(StreamApplication streamApp) {
    Validate.isInstanceOf(SamzaSqlApplication.class, streamApp);
    appRunner.run(streamApp);
  }

  @Override
  public void kill(StreamApplication streamApp) {
    appRunner.kill(streamApp);
  }

  @Override
  public ApplicationStatus status(StreamApplication streamApp) {
    return appRunner.status(streamApp);
  }

  static void populateSystemStreams(RelNode relNode, Set<String> inputSystemStreams, Set<String> outputSystemStreams) {
    if (relNode instanceof TableModify) {
      outputSystemStreams.add(getSystemStreamName(relNode));
    } else {
      if (relNode instanceof BiRel) {
        BiRel biRelNode = (BiRel) relNode;
        populateSystemStreams(biRelNode.getLeft(), inputSystemStreams, outputSystemStreams);
        populateSystemStreams(biRelNode.getRight(), inputSystemStreams, outputSystemStreams);
      } else {
        if (relNode.getTable() != null) {
          inputSystemStreams.add(getSystemStreamName(relNode));
        }
      }
    }

    List<RelNode> relNodes = relNode.getInputs();
    if (relNodes == null || relNodes.isEmpty()) {
      return;
    }
    relNodes.forEach(node -> populateSystemStreams(node, inputSystemStreams, outputSystemStreams));
  }

  private static String getSystemStreamName(RelNode relNode) {
    return relNode.getTable().getQualifiedName().stream().map(Object::toString).collect(Collectors.joining("."));
  }
}
