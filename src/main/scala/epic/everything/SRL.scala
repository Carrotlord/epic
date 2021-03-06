package epic.everything

import breeze.util.Index
import epic.framework._
import breeze.linalg._
import breeze.collection.mutable.TriangularArray
import collection.immutable.BitSet
import epic.ontonotes.Argument
import epic.trees.Span
import epic.sequences.{SemiCRFInference, SemiCRF, Segmentation, SegmentationEval}
import collection.mutable.ArrayBuffer
import collection.mutable
import epic.sequences.SemiCRF.TransitionVisitor
import scala.Some
import epic.ontonotes.Argument
import epic.sequences.Segmentation
import epic.everything.ChainNER.Label1Feature
import epic.trees.Span

/**
 *
 * @author dlwh
 */
object SRL {
  class Model(factory: SentenceBeliefs.Factory,
              val labelIndex: Index[Option[String]],
              outsideLabel: String,
              val featurizer: IndexedFeaturizer,
              initialWeights: Feature=>Option[Double] = {(_: Feature) => None}) extends EvaluableModel[FeaturizedSentence] with StandardExpectedCounts.Model with Serializable {
    assert(labelIndex(Some(outsideLabel)) != -1)

    def featureIndex = featurizer.featureIndex

    type Marginal = SRL.Marginal
    type Inference = SRL.Inference

    def initialValueForFeature(f: Feature): Double = initialWeights(f).getOrElse(0.0)

    def inferenceFromWeights(weights: DenseVector[Double]): Inference = new SRL.Inference(factory, labelIndex, outsideLabel, weights, featurizer)

    type EvaluationResult = SegmentationEval.Stats

    def evaluate(guess: FeaturizedSentence, gold: FeaturizedSentence, logResults: Boolean): EvaluationResult = {
      val pieces = guess.frames.zip(gold.frames) map { case (guessf, goldf) =>
        val guessSeg: Segmentation[String, String] = asSegments(guess.words, guessf.args)
        val goldSeg: Segmentation[String, String] = asSegments(gold.words, goldf.args)
        val stats = SegmentationEval.evaluateExample(Set(outsideLabel,"V"), guessSeg, goldSeg)
        if (logResults) {
          val sentence =(0 until guess.words.length).map{i => if (i == guessf.pos) "[" + guess.words(i) + "]" else guess.words(i)}.mkString(" ")
          println(s"""
          |=============================
          |Predicate: $sentence
          |Guess: ${guessSeg.render(badLabel=outsideLabel)}
          |
          |Gold: ${goldSeg.render(badLabel=outsideLabel)}
          |
          |Stats: $stats
          |==============================""".stripMargin('|'))
        }
        stats
      }

      pieces.foldLeft(new SegmentationEval.Stats())(_ + _)

    }

    private def asSegments(words: IndexedSeq[String], frame: IndexedSeq[Argument]): Segmentation[String, String] = {
      val sorted = frame.sortBy(_.span.start)
      var out = new ArrayBuffer[(String, Span)]()
      var last = 0
      for( arg <- sorted ) {
        assert(last <= arg.span.start)
        while(arg.span.start != last) {
          out += (outsideLabel -> Span(last,last+1))
          last += 1
        }
        out += (arg.arg -> Span(arg.span.start, arg.span.end))
        last = arg.span.end
      }
      while(words.length != last) {
        out += (outsideLabel -> Span(last,last+1))
        last += 1
      }

      Segmentation(out, words)
    }
  }

  case class Marginal(frames: IndexedSeq[SemiCRF.Marginal[Option[String], String]]) extends epic.framework.Marginal {
    def logPartition: Double = frames.map(_.logPartition).sum
    assert(!logPartition.isNaN)
    assert(!logPartition.isInfinite)
  }

  class Inference(beliefsFactory: SentenceBeliefs.Factory,
                  labelIndex: Index[Option[String]],
                  outsideLabel: String,
                  weights: DenseVector[Double],
                  featurizer: IndexedFeaturizer) extends ProjectableInference[FeaturizedSentence, SentenceBeliefs] with AnnotatingInference[FeaturizedSentence] {
    type Marginal = SRL.Marginal
    type ExpectedCounts = StandardExpectedCounts[Feature]

    val notSRL = labelIndex(None)


    def emptyCounts = StandardExpectedCounts.zero(featurizer.featureIndex)

    def numLabels = labelIndex.size

    def baseAugment(doc: FeaturizedSentence): SentenceBeliefs = {
      beliefsFactory(doc)
    }


    def annotate(s: FeaturizedSentence, m: Marginal): FeaturizedSentence = {
      val pieces =  for ((margf, fi) <- m.frames.zipWithIndex) yield {
        val segments: Segmentation[Option[String], String] = SemiCRF.posteriorDecode(margf)
        s.frames(fi).copy(args=segments.label.collect { case (Some(l),span) => Argument(l,span)})
      }

      s.copy(frames=pieces)
    }

    def marginal(s: FeaturizedSentence, aug: SentenceBeliefs): Marginal = {
      val pieces =  for ((f, fi) <- s.frames.zipWithIndex) yield {
        SemiCRF.Marginal(new Anchoring(featurizer.anchor(s, f.lemma, f.pos), s.words, labelIndex, outsideLabel, weights, aug, fi))
      }
      new Marginal(pieces)
    }



    def goldMarginal(s: FeaturizedSentence, augment: SentenceBeliefs): Marginal = {
      val pieces = for ((f, fi) <- s.frames.zipWithIndex) yield {
        val seg = f.stripEmbedded.asSegments(s.words)
        val anchoring = new Anchoring(featurizer.anchor(s, f.lemma, f.pos), s.words, labelIndex, outsideLabel, weights, augment, fi)
        SemiCRF.Marginal.goldMarginal(anchoring, seg)
      }

      new Marginal(pieces)
    }

    def countsFromMarginal(s: FeaturizedSentence, marg: Marginal, counts: ExpectedCounts, scale: Double): ExpectedCounts = {
      counts.loss += marg.logPartition * scale
      for ( f <- marg.frames) {
        val labelMarginals = TriangularArray.fill(s.length+1)(null:Array[Double])
        val localization = f.anchoring.asInstanceOf[Anchoring].featurizer
        f visit new TransitionVisitor[Option[String], String] {
          def apply(prev: Int, cur: Int, beg: Int, end: Int, count: Double) {
            var arr = labelMarginals(beg,end)
            if (arr eq null) {
              arr = new Array[Double](labelIndex.size)
              labelMarginals(beg,end) = arr
            }
            arr(cur) += count
          }
        }
        for (b <- 0 until s.length; e <- (b+1) to s.length) {
          val arr = labelMarginals(b,e)
          if (arr ne null) {
            var label = 0
            while(label < arr.length) {
              val count = arr(label)
              if (count != 0.0) {
                val feats = localization.featuresFor(b, e, label)
                var f = 0
                while(f < feats.length) {
                  counts.counts(feats(f)) += count * scale
                  f += 1
                }
              }
              label += 1
            }
          }

        }
//        f visit new TransitionVisitor[Option[String], String] {
//          def apply(prev: Int, cur: Int, beg: Int, end: Int, count: Double) {
//            if (count != 0.0) {
//              var f = 0
//              val feats = localization.featuresFor(beg, end, cur)
//              while(f < feats.length) {
//                counts.counts(feats(f)) += count * scale
//                f += 1
//              }
//            }
//          }
//        }
      }
      counts
    }

    def project(sent: FeaturizedSentence, marg: Marginal, sentenceBeliefs: SentenceBeliefs): SentenceBeliefs = {
      assert(!marg.logPartition.isInfinite, "Infinite partition! " + sent)
      val newSpans = TriangularArray.tabulate(sent.length+1){ (b,e) =>
        if (b < e) {
          val spanBeliefs = sentenceBeliefs.spanBeliefs(b, e)
          if (spanBeliefs eq null) {
            null
          } else {
            val newFrames: IndexedSeq[Beliefs[Option[String]]] = for(i <- 0 until marg.frames.size) yield {
              val beliefs = DenseVector.tabulate(labelIndex.size) {
                marg.frames(i).spanMarginal(_, b, e)
              }
              assert(beliefs(notSRL) == 0.0, beliefs)
              if (spanBeliefs.frames(i)(notSRL) == 0.0 || beliefs(notSRL) < 0.0) {
                beliefs(notSRL) = 0.0
              } else {
                beliefs(notSRL) = 1 - breeze.linalg.sum(beliefs)
              }
              val normalizer: Double = breeze.linalg.sum(beliefs)
              beliefs /= normalizer
              Beliefs(spanBeliefs.frames(i).property, beliefs)
            }
            spanBeliefs.copy(frames = newFrames)
          }
        } else {
          null
        }
      }
      sentenceBeliefs.copy(spans=newSpans)
    }

  }

  trait IndexedFeaturizer {
    def featureIndex: Index[Feature]
    def anchor(fs: FeaturizedSentence, lemma: String, pos: Int):FeatureAnchoring
  }

  trait FeatureAnchoring {
    def featuresFor(begin: Int, end: Int, label: Int):Array[Int]
  }

  class Anchoring(val featurizer: FeatureAnchoring,
                  val words: IndexedSeq[String],
                  val labelIndex: Index[Option[String]],
                  val outsideLabel: String,
                  weights: DenseVector[Double],  val sentenceBeliefs: SentenceBeliefs, val frameIndex: Int) extends SemiCRF.Anchoring[Option[String], String] {

    def startSymbol: Option[String] = Some(outsideLabel)

    val iNone = labelIndex(None)
    val iOutside = labelIndex(Some(outsideLabel))

    def maxSegmentLength(label: Int): Int = if(label == iNone) 0 else if(label == iOutside) 1 else 50


    def scoreTransition(prev: Int, cur: Int, beg: Int, end: Int): Double = {
      score(beg, end, cur)
    }

    // (same trick as in parser:)
    // we employ the trick in Klein's thesis and in the Smith & Eisner BP paper
    // which is as follows: we want to multiply \prod_(all spans) p(span type of span or not a span)
    // but the dynamic program does not visit all spans for all parses, only those
    // in the actual parse. So instead, we premultiply by \prod_{all spans} p(not span)
    // and then we divide out p(not span) for spans in the tree.

    val normalizingPiece = sentenceBeliefs.spans.data.filter(_ ne null).map { b =>
      val notNerScore = b.frames(frameIndex).beliefs(iNone)

      if (notNerScore <= 0.0) 0.0 else math.log(notNerScore)
    }.sum

    private def beliefPiece(beg:Int, end:Int, cur: Int): Double = {
      val score = if (cur == iNone) Double.NegativeInfinity
      else if (sentenceBeliefs.spanBeliefs(beg, end).eq(null) || sentenceBeliefs.spanBeliefs(beg, end).frames(frameIndex)(cur) <= 0.0) Double.NegativeInfinity
      else if (sentenceBeliefs.spanBeliefs(beg, end).frames(frameIndex)(iNone) <=  0.0) {
        math.log(sentenceBeliefs.spanBeliefs(beg,end).frames(frameIndex)(cur))
      } else {
        math.log(sentenceBeliefs.spanBeliefs(beg,end).frames(frameIndex)(cur) / sentenceBeliefs.spanBeliefs(beg,end).frames(frameIndex)(iNone))
      }

//      if (beg == 0) score + normalizingPiece else score
      assert(score == 0.0 || score == Double.NegativeInfinity)
      score
    }


    def score(begin: Int, end: Int, label: Int):Double = {
      if(scoreCache(begin,end)(label).isNaN) {
        val init = 0.0//beliefPiece(begin, end, label)
        val score = {
          if (init == Double.NegativeInfinity) Double.NegativeInfinity
          else {
            val feats: Array[Int] = featurizer.featuresFor(begin, end, label)
            if (feats eq null) {
              Double.NegativeInfinity
            } else {
              init + dot(feats, weights)
            }
          }
        }
        scoreCache(begin,end)(label) = score
      }
      scoreCache(begin,end)(label)
    }

    private val scoreCache = TriangularArray.fill(length+1)(Array.fill(labelIndex.size)(Double.NaN))

    private def dot(features: Array[Int], weights: DenseVector[Double]) = {
      var i =0
      var score = 0.0
      if (features eq null) {
        Double.NegativeInfinity
      } else {
        while(i < features.length) {
          score += weights(features(i))
          i += 1
        }
        score
      }
    }
  }

  class ModelFactory(factory: SentenceBeliefs.Factory,
                     processor: FeaturizedDocument.Factory,
                     weights: Feature=>Option[Double] = { (f:Feature) => None}) {

    def makeModel(sents: IndexedSeq[FeaturizedSentence]) = {
      val frames = sents.flatMap(_.frames)
      val lemmaIndex = Index(frames.iterator.map(_.lemma))


      val featurizer = new StandardFeaturizer(factory.srlProp.index, lemmaIndex, processor.wordFeatureIndex, processor.spanFeatureIndex)
      val model = new Model(factory, factory.srlProp.index, factory.srlOutsideLabel, featurizer, weights(_))

      model
    }
  }

  object Features {
    case class DistanceToPredFeature(dir: Symbol, label: Any, voice: Symbol, dist: Int) extends Feature
    case object LemmaContainedFeature extends Feature
  }
  import Features._

  case class StandardFeaturizer(labelIndex: Index[Option[String]],
                                lemmaIndex: Index[String],
                                baseWordFeatureIndex: Index[Feature],
                                baseSpanFeatureIndex: Index[Feature]) extends IndexedFeaturizer { outer =>

    val kinds = Array('Begin, 'Interior)
    val propKinds = Array('PassiveProp, 'ActiveProp)
    val leftRight = Array('Left, 'Right)

    println(lemmaIndex.size + " " + labelIndex.size)

    private def binDistance(dist2:Int) = {
      val dist = dist2.abs - 1
      if (dist >= 20) 4
      else if (dist >= 10) 3
      else if (dist >= 5) 2
      else if (dist >= 2) 1
      else 0
    }

    private def numDistBins = 5

    private val lNone = labelIndex(None)


    val (featureIndex: Index[Feature], wordFeatures, spanFeatures, distanceToLemmaFeatures, lemmaContainedFeature) = {
      val featureIndex = Index[Feature]()
      val labelFeatures = Array.tabulate(labelIndex.size, kinds.length, baseWordFeatureIndex.size) { (l, k, f) =>
        if (l != lNone)
          featureIndex.index(Label1Feature(labelIndex.get(l), baseWordFeatureIndex.get(f), kinds(k)))
        else -1
      }

      val spanFeatures = Array.tabulate(labelIndex.size, baseSpanFeatureIndex.size) { (l, f) =>
        if (l != lNone)
          featureIndex.index(Label1Feature(labelIndex.get(l), baseSpanFeatureIndex.get(f), 'Span))
        else -1
      }

      val distanceToLemmaFeatures = Array.tabulate(labelIndex.size, lemmaIndex.size + 1, propKinds.length, 2, numDistBins) { (l, lem, kind, dir, dist) =>
        if (l == lNone) -1
        else if(lem == lemmaIndex.size)
          featureIndex.index(DistanceToPredFeature(leftRight(dir), labelIndex.get(l), propKinds(kind), dist))
        else
          featureIndex.index(DistanceToPredFeature(leftRight(dir), (labelIndex.get(l) + " " + lemmaIndex.get(lem)).intern, propKinds(kind), dist))
      }

      val lemmaContainedFeature = featureIndex.index(LemmaContainedFeature)

      (featureIndex, labelFeatures, spanFeatures, distanceToLemmaFeatures, lemmaContainedFeature)
    }

    println("SRL features: " + featureIndex.size)
    val featuresByType = Counter[String, Int]()
    for(f <- featureIndex) {
      f match {
        case Label1Feature(_, f, _) => featuresByType(f.getClass.getName) += 1
        case _ => featuresByType(f.getClass.getName) += 1
      }
    }

    featuresByType.iterator foreach println

    def anchor(fs: FeaturizedSentence, lemma: String, pos: Int): FeatureAnchoring = {
      new Anchoring(fs, lemma, pos)

    }

    class Anchoring(fs: FeaturizedSentence, lemma: String, pos: Int) extends FeatureAnchoring {
      val voiceIndex = if(pos > 0 &&
        Set("was", "were", "being", "been").contains(fs.words(pos-1))
        || (pos > 1 && Set("was", "were", "being", "been").contains(fs.words(pos-2)))) 0 else 1

      val lemmaInd = lemmaIndex(lemma)

      def featuresFor(begin: Int, end: Int, label: Int): Array[Int] = {
        this.spanFeatures(label)(begin, end)
      }

      val beginCache = Array.tabulate(labelIndex.size, fs.words.length){ (label,w) =>
              val feats = fs.wordFeatures(w)
        val builder = Array.newBuilder[Int]
        builder.sizeHint(if(lemmaInd == -1) feats.length else 2 * feats.length)
        appendFeatures(builder, feats, wordFeatures(label)(0))
        builder.result()
      }


      private def appendFeatures(builder: mutable.ArrayBuilder[Int], rawFeatures: Array[Int], labeledFeatures: Array[Int]) {
        var i = 0
        while (i < rawFeatures.length) {
          builder += labeledFeatures(rawFeatures(i))
          i += 1
        }
      }

      val interiorCache = Array.tabulate(labelIndex.size, fs.words.length){ (label,w) =>
        val feats = fs.wordFeatures(w)
        val builder = Array.newBuilder[Int]
        builder.sizeHint(if(lemmaInd == -1) feats.length else 2 * feats.length)
        appendFeatures(builder, feats, wordFeatures(label)(1))
        builder.result()
      }


      def featuresForBegin(cur: Int, pos: Int): Array[Int] = {
        beginCache(cur)(pos)
      }


      def featuresForInterior(cur: Int, pos: Int): Array[Int] = {
        interiorCache(cur)(pos)
      }


      private val spanFeatures: Array[TriangularArray[Array[Int]]] = Array.tabulate(labelIndex.size){ label =>
        TriangularArray.tabulate(fs.words.length+1) { (beg, end) =>
          if(!fs.isPossibleMaximalSpan(beg, end) || beg == end || label == lNone ) {
            null
          } else {
            val acc = new ArrayBuffer[Array[Int]]()
            val _begin = featuresForBegin(label, beg)
            acc += _begin

            var p = beg+1
            while(p < end) {
              val w = featuresForInterior(label, p)
              acc += w
              p += 1
            }

            val forSpan = fs.featuresForSpan(beg, end)
            val builder = new Array[Int](acc.map(_.size).sum  + forSpan.length + {if(pos >= beg && pos < end) 1 else if (lemmaInd < 0) 1 else 2})
            var off = 0
            var i = 0
            while(i < acc.size) {
              System.arraycopy(acc(i), 0, builder, off, acc(i).length)
              off += acc(i).length
              i += 1
            }

            i = 0
            while (i < forSpan.length) {
              builder(off) = outer.spanFeatures(label)(forSpan(i))
              off += 1
              i += 1
            }

            val dir = if(pos < beg) 0 else 1

            if (pos >= beg && pos < end) {
              builder(off) = lemmaContainedFeature
              off += 1
            } else {
              builder(off) = distanceToLemmaFeatures(label)(lemmaIndex.size)(voiceIndex)(dir)(binDistance(beg - pos))
              off += 1
              if(lemmaInd >= 0) {
                builder(off) = distanceToLemmaFeatures(label)(lemmaInd)(voiceIndex)(dir)(binDistance(beg - pos))
                off += 1
              }
            }
            assert(builder.length == off)
            builder
          }
        }
      }





    }
  }

}
