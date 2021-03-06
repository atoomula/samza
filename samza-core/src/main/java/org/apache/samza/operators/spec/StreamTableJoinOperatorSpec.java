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
package org.apache.samza.operators.spec;

import org.apache.samza.annotation.InterfaceStability;
import org.apache.samza.operators.functions.ScheduledFunction;
import org.apache.samza.operators.functions.StreamTableJoinFunction;
import org.apache.samza.operators.functions.WatermarkFunction;

/**
 * The spec for stream-table join operator that retrieves a record from the table using key
 * derived from the incoming message and joins with the incoming message.
 *
 * @param <M>  the type of input messages
 * @param <R>  the type of table record
 * @param <JM>  the type of join result
 */
@InterfaceStability.Unstable
public class StreamTableJoinOperatorSpec<K, M, R, JM> extends OperatorSpec<M, JM> {

  private final String tableId;
  private final StreamTableJoinFunction<K, M, R, JM> joinFn;
  private final Object[] args;

  /**
   * Constructor for {@link StreamTableJoinOperatorSpec}.
   *
   * @param tableId  the Id of the table on the right side of the join
   * @param joinFn  the user-defined join function to get join keys and results
   * @param opId  the unique ID for this operator
   * @param args additional arguments passed to the table
   */
  StreamTableJoinOperatorSpec(String tableId, StreamTableJoinFunction<K, M, R, JM> joinFn, String opId, Object ... args) {
    super(OpCode.JOIN, opId);
    this.tableId = tableId;
    this.joinFn = joinFn;
    this.args = args;
  }

  public String getTableId() {
    return tableId;
  }

  public StreamTableJoinFunction<K, M, R, JM> getJoinFn() {
    return this.joinFn;
  }

  public Object[] getArgs() {
    return args;
  }

  @Override
  public WatermarkFunction getWatermarkFn() {
    return joinFn instanceof WatermarkFunction ? (WatermarkFunction) joinFn : null;
  }

  @Override
  public ScheduledFunction getScheduledFn() {
    return joinFn instanceof ScheduledFunction ? (ScheduledFunction) joinFn : null;
  }

}
