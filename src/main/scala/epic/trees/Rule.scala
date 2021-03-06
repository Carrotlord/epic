package epic.trees

import breeze.serialization.DataSerialization
import java.io.DataOutput
import epic.framework.Feature

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

sealed trait Production[@specialized(Int) +L, +W] extends Feature {
  def parent: L
  def map[A](f: L => A): Production[A, W]
}

sealed trait Rule[@specialized(Int) +L] extends Production[L, Nothing] {
  def parent: L

  def children: Seq[L]

  def symbols = parent +: children

  def map[A](f: L => A): Rule[A]

  def mapChildren[A >: L](f: L => A): Rule[A]
}

final case class BinaryRule[@specialized(Int) +L](parent: L, left: L, right: L) extends Rule[L] {
  def children = Seq(left, right)

  def map[A](f: L => A) = BinaryRule(f(parent), f(left), f(right))

  def mapChildren[A >: L](f: L => A) = BinaryRule(parent, f(left), f(right))
}

final case class UnaryRule[@specialized(Int) +L](parent: L, child: L, chain: Seq[String]) extends Rule[L] {
  def children = Seq(child)

  def map[A](f: L => A) = UnaryRule(f(parent), f(child), chain)

  def mapChildren[A >: L](f: L => A) = UnaryRule(parent, f(child), chain)
}

final case class LexicalProduction[@specialized(Int) +L, +W](parent: L, word: W) extends Production[L, W] {
  def map[A](f: L => A) = LexicalProduction(f(parent), word)
}


object Rule {
  implicit def ruleReadWritable[L: DataSerialization.ReadWritable] = new DataSerialization.ReadWritable[Rule[L]] {
    def write(sink: DataOutput, r: Rule[L]) = r match {
      case r@UnaryRule(_, _, _) =>
        sink.writeBoolean(false)
        DataSerialization.write(sink, r.parent)
        DataSerialization.write(sink, r.child)
        DataSerialization.write(sink, r.chain)
      case r@BinaryRule(_, _, _) =>
        sink.writeBoolean(true)
        DataSerialization.write(sink, r.parent)
        DataSerialization.write(sink, r.left)
        DataSerialization.write(sink, r.right)
    }

    def read(source: DataSerialization.Input) = {
      if (source.readBoolean()) {
        val p = DataSerialization.read[L](source)
        val c = DataSerialization.read[L](source)
        val chain = DataSerialization.read[Seq[String]](source)(DataSerialization.seqReadWritable[String])
        UnaryRule(p, c, chain)
      } else {
        val p = DataSerialization.read[L](source)
        val l = DataSerialization.read[L](source)
        val r = DataSerialization.read[L](source)
        BinaryRule(p, l, r)
      }
    }
  }
}

object UnaryRule {
  implicit def ruleReadWritable[L: DataSerialization.ReadWritable] = new DataSerialization.ReadWritable[UnaryRule[L]] {
    def write(sink: DataOutput, r: UnaryRule[L]) = {
      sink.writeBoolean(false)
      DataSerialization.write(sink, r.parent)
      DataSerialization.write(sink, r.child)
    }

    def read(source: DataSerialization.Input) = {
      val isUnary = !source.readBoolean()
      assert(isUnary)
      val p = DataSerialization.read[L](source)
      val c = DataSerialization.read[L](source)
      val chain = DataSerialization.read[Seq[String]](source)(DataSerialization.seqReadWritable[String])
      UnaryRule(p, c, chain)
    }
  }
}

object BinaryRule {
  implicit def ruleReadWritable[L: DataSerialization.ReadWritable] = new DataSerialization.ReadWritable[BinaryRule[L]] {
    def write(sink: DataOutput, r: BinaryRule[L]) = {
      sink.writeBoolean(true)
      DataSerialization.write(sink, r.parent)
      DataSerialization.write(sink, r.left)
      DataSerialization.write(sink, r.right)
    }

    def read(source: DataSerialization.Input) = {
      val isBinary = source.readBoolean()
      assert(isBinary)
      val p = DataSerialization.read[L](source)
      val c = DataSerialization.read[L](source)
      val r = DataSerialization.read[L](source)
      BinaryRule(p, c, r)
    }
  }
}