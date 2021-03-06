package epic.framework

/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import breeze.util.Index
import breeze.linalg._
import java.io.File


/**
 * A Model represents a class for turning weight vectors into [[epic.framework.Inference]]s.
 * It's main job is to hook up with a [[epic.framework.ModelObjective]] and mediate
 * computation of ExpectedCounts and conversion to the objective that's
 * needed for optimzation.
 *
 * @tparam Datum the kind of
 */
trait Model[Datum] { self =>
  type ExpectedCounts >: Null <: epic.framework.ExpectedCounts[ExpectedCounts]
  type Marginal <: epic.framework.Marginal

  type Inference <: epic.framework.Inference[Datum] {
    type ExpectedCounts = self.ExpectedCounts
    type Marginal = self.Marginal
  }

  /**
   * Models have features, and this defines the mapping from indices in the weight vector to features.
   * @return
   */
  def featureIndex: Index[Feature]

  def numFeatures = featureIndex.size

  /**
   * just saves feature weights to disk as a serialized counter. The file is prefix.ser.gz
   */
  def cacheFeatureWeights(weights: DenseVector[Double], prefix: String = "weights") {
    val out = new File(prefix + ".ser.gz")
    breeze.util.writeObject(out, (featureIndex, weights))
  }

  def initialValueForFeature(f: Feature): Double // = 0

  def inferenceFromWeights(weights: DenseVector[Double]): Inference


  def expectedCountsToObjective(ecounts: ExpectedCounts): (Double, DenseVector[Double])
}



