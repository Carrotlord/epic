package epic.sequences

import breeze.stats.ContingencyStats
import epic.framework.EvaluationResult


object SegmentationEval {
  def eval[L ,W](crf: SemiCRF[L, W], examples: IndexedSeq[Segmentation[L, W]], outsideLabel: L):Stats = {
    examples.par.aggregate(new Stats(0,0,0)) ({ (stats, gold )=>
      val guess = crf.bestSequence(gold.words, gold.id +"-guess")
      val myStats = evaluateExample(Set(outsideLabel), guess, gold)
      println("Guess:\n" + guess.render(badLabel=outsideLabel) + "\n Gold:\n" + gold.render(badLabel=outsideLabel)+ "\n" + myStats)
      stats + myStats
    }, {_ + _})

  }

  def evaluateExample[W, L](outsideLabel: Set[L], guess: Segmentation[L, W], gold: Segmentation[L, W]): SegmentationEval.Stats = {
    val guessSet = guess.segments.filter(a => !outsideLabel(a._1)).toSet
    val goldSet = gold.segments.filter(a => !outsideLabel(a._1)).toSet
    val nRight = (guessSet & goldSet).size
    val myStats: Stats = new Stats(nRight, guessSet.size, goldSet.size)
    myStats
  }

  class Stats(val nRight: Int = 0, val nGuess: Int = 0, val nGold: Int = 0) extends EvaluationResult[Stats] {
    def precision = nRight * 1.0 / nGuess
    def recall = nRight * 1.0 / nGold
    def f1 = 2 * precision * recall / (precision + recall)

    def +(stats: Stats) = {
      new Stats(nRight + stats.nRight, nGuess + stats.nGuess, nGold + stats.nGold)
    }

    override def toString = "Evaluation Result: P=%.4f R=%.4f F=%.4f".format(precision,recall,f1)

  }

}
