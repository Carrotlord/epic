package epic.trees
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
import breeze.util.{Interner, Lens}
import collection.mutable.ArrayBuffer

/**
 * Something we can throw in an AnnotatedLabel
 * @author dlwh
 */
@SerialVersionUID(1L)
trait Annotation extends Serializable

case class FunctionalTag(tag: String) extends Annotation

/**
 * The standard label used in the parser (used to be String).
 *
 * Useful for Klein-and-Manning style annotated labels and other explicit-annotation strategies
 * @author dlwh
 */
@SerialVersionUID(1L)
case class AnnotatedLabel(label: String,
                          parents: Seq[String] = Seq.empty,
                          siblings: Seq[Either[String, String]] = Seq.empty,
                          features: Set[Annotation] = Set.empty) {

  def annotate(sym: Annotation) = copy(features = features + sym)

  def isIntermediate = label.nonEmpty && label.charAt(0) == '@'

  def baseLabel = label.dropWhile(_ == '@')

  def baseAnnotatedLabel = AnnotatedLabel(label)
  def clearFeatures = copy(features=Set.empty)

  def treebankString = (label +: features.collect{ case t: FunctionalTag => t.tag}.toIndexedSeq).mkString("-")

  override def toString = {
    val components = new ArrayBuffer[String]()
    if(parents.nonEmpty) {
      components += parents.mkString("^")
    }
    if(siblings.nonEmpty) {
      val b = new StringBuilder()
      siblings foreach {
        case Left(sib) =>
          b ++= "\\"
          b ++= sib
        case Right(sib) =>
          b ++= "/"
          b ++= sib
      }
      components += b.toString
    }
    if(features.nonEmpty)
      components += features.toString

    if(components.nonEmpty) components.mkString(label+"[", ", ", "]")
    else label
  }
}

object AnnotatedLabel {

  def apply(lbl: String):AnnotatedLabel = interner.intern(new AnnotatedLabel(lbl))

  private val interner: Interner[AnnotatedLabel] = new Interner[AnnotatedLabel]()

  val TOP = AnnotatedLabel("TOP")

  implicit val stringLens:Lens[AnnotatedLabel, String] = new Lens[AnnotatedLabel, String] with Serializable {
    def get(t: AnnotatedLabel) = t.label
    def set(t: AnnotatedLabel, u: String) = t.copy(u)
  }
}