package epic.parser

import org.scalatest.FunSuite
import breeze.util.Implicits._

/**
 * 
 * @author dlwh
 */
class LatentTreeMarginalTest extends FunSuite {
  test("LatentTreeMarginal is the same as TreeMarginal with no refinements") {
    val trees = ParserTestHarness.getTrainTrees()
    for(t <- trees) {
      val lmarg = LatentTreeMarginal(ParserTestHarness.simpleParser.augmentedGrammar, t.words, t.tree.map(_ -> Seq(0)))
      val marg = TreeMarginal(ParserTestHarness.simpleParser.augmentedGrammar, t.words, t.tree.map(_ -> 0))
      assert(lmarg.logPartition closeTo marg.logPartition, lmarg.logPartition + " " +marg.logPartition)
      val lcounts = lmarg.expectedProductionCounts
      val counts = lmarg.expectedProductionCounts
      assert((lcounts.counts - counts.counts).norm(2) < 1E-4 * lcounts.counts.length)
    }

  }

}
