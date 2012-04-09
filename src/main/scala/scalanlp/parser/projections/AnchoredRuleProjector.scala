package scalanlp.parser
package projections

import scalanlp.collection.mutable.TriangularArray
import scalanlp.tensor.sparse.OldSparseVector
import scalanlp.util.TypeTags
import TypeTags.ID
import scalanlp.trees.Rule

/**
 * Used for computed the expected number of anchored rules that occur at each span/split.
 * @author dlwh
 */
@SerialVersionUID(2L)
class AnchoredRuleProjector(threshold: Double) extends Serializable {

  /**
   * Projects an inside and outside chart to anchored rule posteriors.
   *
   * @param inside inside chart
   * @param outside outside chart
   * @param sentProb log probability of the root. probably a log partition
   * @param scorer: scorer used to produce this tree.
   * @param pruneLabel should return a threshold to determine if we need to prune. (prune if posterior <= threshold) See companion object for good choices.
   */
  def projectRulePosteriors[L,W](charts: ChartMarginal[ParseChart, L, W],
                                 goldTagPolicy: GoldTagPolicy[L] = GoldTagPolicy.noGoldTags[L]):AnchoredRuleProjector.AnchoredData = {

    val length = charts.length
    // preliminaries: we're not going to fill in everything: some things will be null.
    // all of this is how to deal with it.
    val numProjectedLabels = charts.grammar.labelIndex.size
    val numProjectedRules = charts.grammar.index.size
    def projVector() = {
      new OldSparseVector(numProjectedLabels, 0.0);
    }

    def projRuleVector() = {
      new OldSparseVector(numProjectedRules, 0.0);
    }

    def getOrElseUpdate[T<:AnyRef](arr: Array[T], i: Int, t : =>T) = {
      if(arr(i) == null) {
        arr(i) = t;
      }
      arr(i);
    }

    // The data, and initialization. most things init'd to null
    val lexicalScores = TriangularArray.raw(length+1, null:OldSparseVector)
    val unaryScores = TriangularArray.raw(length+1, null:OldSparseVector);

    val totals = TriangularArray.raw(length+1, null:OldSparseVector);
    val totalsUnaries = TriangularArray.raw(length+1, null:OldSparseVector);

    val binaryScores = TriangularArray.raw[Array[OldSparseVector]](length+1, null);
    for(begin <- 0 until length; end <- (begin + 1) to length) {
      val numSplits = end - begin;
      if(!charts.inside.bot.enteredLabelIndexes(begin, end).isEmpty) // is there anything to put here?
        binaryScores(TriangularArray.index(begin, end)) = Array.fill(numSplits)(null:OldSparseVector)
    }
    
    val visitor = new AnchoredSpanVisitor[L] {
      def visitSpan(begin: Int, end: Int, tag: ID[L], ref: ID[Ref[L]], score: Double) {
        // fill in spans with 0 if they're active
        getOrElseUpdate(lexicalScores, TriangularArray.index(begin, end), projVector())(tag) = 0
      }

      def visitBinaryRule(begin: Int, split: Int, end: Int, rule: ID[Rule[L]], ref: ID[RuleRef[L]], count: Double) {
        val index = TriangularArray.index(begin, end)
        if(count > 0.0) {
          totals(index)(charts.grammar.grammar.parent(rule)) += count

          val parentArray = if(binaryScores(index)(split-begin) eq null) {
            binaryScores(index)(split-begin) = projRuleVector()
            binaryScores(index)(split-begin)
          } else {
            binaryScores(index)(split-begin)
          }
          parentArray(rule) += count
        }
      }

      def visitUnaryRule(begin: Int, end: Int, rule: ID[Rule[L]], ref: ID[RuleRef[L]], count: Double) {
        val index = TriangularArray.index(begin, end)
        val parentArray = if(unaryScores(index) eq null) {
          unaryScores(index) = projRuleVector()
          unaryScores(index)
        } else {
          unaryScores(index)
        }
        parentArray(rule) += count
        totalsUnaries(index)(charts.grammar.grammar.parent(rule)) += count
      }

    }

    charts.visit(visitor)

    new AnchoredRuleProjector.AnchoredData(lexicalScores, unaryScores, totalsUnaries, binaryScores, totals);
  }
}


object AnchoredRuleProjector {

  /**
   * POJO for anchored rule counts. entries may be null.
   */
  case class AnchoredData(/** spanScore(trianuglarIndex)(label) = score of tag at position pos */
                          spanScores: Array[OldSparseVector],
                          /** unaryScores(triangularIndex)(rule) => score of unary from parent to child */
                          unaryScores: Array[OldSparseVector],
                          /** (triangularIndex)(parent) => same, but for unaries*/
                          unaryTotals: Array[OldSparseVector],
                          /** binaryScores(triangularIndex)(split)(rule) => score of unary from parent to child */
                          binaryScores: Array[Array[OldSparseVector]],
                          /** (triangularIndex)(parent) => sum of all binary rules at parent. */
                          binaryTotals: Array[OldSparseVector]);

}


