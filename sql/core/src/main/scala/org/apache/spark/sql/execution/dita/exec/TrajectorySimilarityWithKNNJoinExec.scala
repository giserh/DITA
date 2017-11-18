/*
 *  Copyright 2017 by DITA Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.spark.sql.execution.dita.exec

import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.dita.common.shape.{Point, Rectangle, Shape}
import org.apache.spark.sql.catalyst.expressions.dita.common.trajectory.{Trajectory, TrajectorySimilarity}
import org.apache.spark.sql.catalyst.expressions.dita.{TrajectorySimilarityExpression, TrajectorySimilarityFunction}
import org.apache.spark.sql.catalyst.expressions.{Attribute, BindReferences, Expression, UnsafeArrayData}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.dita.rdd.TrieRDD
import org.apache.spark.sql.execution.dita.sql.{DITAIternalRow, TrieIndexedRelation}
import org.apache.spark.sql.execution.{BinaryExecNode, SparkPlan}


case class TrajectorySimilarityWithKNNJoinExec(leftKey: Expression, rightKey: Expression,
                                               function: TrajectorySimilarityFunction,
                                               count: Int,
                                               leftLogicalPlan: LogicalPlan, rightLogicalPlan: LogicalPlan,
                                               left: SparkPlan, right: SparkPlan) extends BinaryExecNode with Logging {
  override def output: Seq[Attribute] = left.output ++ right.output

  sparkContext.conf.registerKryoClasses(Array(classOf[Shape], classOf[Point],
    classOf[Rectangle], classOf[Trajectory]))

  protected override def doExecute(): RDD[InternalRow] = {
    logWarning(s"Distance function: $function")
    logWarning(s"Count: $count")

    val leftResults = left.execute()
    val rightResults = right.execute()
    val leftCount = leftResults.count()
    val rightCount = rightResults.count()
    logWarning(s"Data count: $leftCount, $rightCount")

    logWarning("Applying efficient trajectory similarity join algorithm!")

    val distanceFunction = TrajectorySimilarity.getDistanceFunction(function)

    val leftTrieRDD = TrajectorySimilarityWithThresholdJoinExec
      .getIndexedRelation(sqlContext, leftLogicalPlan)
      .map(_.asInstanceOf[TrieIndexedRelation].trieRDD)
      .getOrElse({
        logWarning("Building left trie RDD")
        val leftRDD = leftResults.map(row =>
          new DITAIternalRow(row, TrajectorySimilarityExpression.getPoints(
            BindReferences.bindReference(leftKey, left.output)
              .eval(row).asInstanceOf[UnsafeArrayData]))).asInstanceOf[RDD[Trajectory]]
        new TrieRDD(leftRDD)
      })

    val rightTrieRDD = TrajectorySimilarityWithThresholdJoinExec
      .getIndexedRelation(sqlContext, rightLogicalPlan)
      .map(_.asInstanceOf[TrieIndexedRelation].trieRDD)
      .getOrElse({
        logWarning("Building right trie RDD")
        val rightRDD = rightResults.map(row =>
          new DITAIternalRow(row, TrajectorySimilarityExpression.getPoints(
            BindReferences.bindReference(rightKey, right.output)
              .eval(row).asInstanceOf[UnsafeArrayData]))).asInstanceOf[RDD[Trajectory]]
        new TrieRDD(rightRDD)
      })

    // get answer
    val join = TrajectorySimilarityWithKNNJoinAlgorithms.DistributedJoin
    val answerRDD = join.join(sparkContext, leftTrieRDD, rightTrieRDD,
      distanceFunction, count)
    val outputRDD = answerRDD.mapPartitions { iter =>
      iter.map(x => InternalRow(x._1.asInstanceOf[DITAIternalRow].row,
          x._2.asInstanceOf[DITAIternalRow].row, x._3))
    }
    outputRDD.asInstanceOf[RDD[InternalRow]]
  }
}
