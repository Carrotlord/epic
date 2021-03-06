package epic.parser.models

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
import epic.trees.{BinaryRule, UnaryRule}
import epic.parser.projections.GrammarRefinements
import epic.parser.{TagScorer, Lexicon, RefinedGrammar, BaseGrammar}

object FeaturizedGrammar {
  def apply[L, L2, W](xbar: BaseGrammar[L],
                      lexicon: Lexicon[L, W],
                      refinements: GrammarRefinements[L, L2],
                      weights: DenseVector[Double],
                      features: IndexedFeaturizer[L, L2, W],
                      tagScorer: TagScorer[L2, W]) = {
    val ruleCache = Array.tabulate[Double](refinements.rules.fineIndex.size){r =>
      features.computeWeight(r,weights)
    }
    val spanCache = new Array[Double](refinements.labels.fineIndex.size)

    RefinedGrammar.unanchored(xbar, lexicon, refinements, ruleCache, spanCache, tagScorer)
  }
}
