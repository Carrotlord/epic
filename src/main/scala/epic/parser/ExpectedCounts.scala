package epic.parser
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
import breeze.linalg._
import breeze.util.{Encoder, Index}

/**
 * Used to count the occurences of features in a set
 * of marginals. Works closely with a [[epic.parser.RefinedFeaturizer]]
 * to actually compute expected counts. This just tallies them.
 * @param index index over features corresponding to counts' size
 * @param counts feature counts, encoded to a vector using index
 * @param loss usually log-loss, which is basically negative log likelihood
 * @tparam Feat
 */
final case class ExpectedCounts[Feat](index: Index[Feat],
                                      counts: DenseVector[Double],
                                      var loss: Double) extends epic.framework.ExpectedCounts[ExpectedCounts[Feat]] {

  def this(index: Index[Feat]) = this(index, DenseVector.zeros(index.size), 0.0)

  def +=(c: ExpectedCounts[Feat]) = {
    val ExpectedCounts(_, wCounts, tProb) = c

    this.counts += wCounts

    loss += tProb
    this
  }

  def -=(c: ExpectedCounts[Feat]) = {
    val ExpectedCounts(_, cc, tProb) = c

    this.counts -= cc

    loss -= tProb
    this
  }

}
