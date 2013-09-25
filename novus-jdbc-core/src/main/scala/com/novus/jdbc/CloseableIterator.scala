/*
 * Copyright (c) 2013 Novus Partners, Inc. (http://www.novus.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.novus.jdbc

import collection.{Iterator, GenTraversableOnce}
import collection.mutable.{Queue => MQueue}
import annotation.tailrec
import java.io.Closeable

/**
 * A trait for an iterator that holds onto an underlying resource which must be released upon consumption. It contains
 * implementations common to `Iterator` defined nearly identical to standard but with resource management. It is
 * recommended that users of `CloseableIterator` take precautions to guarantee that the resource is explicitly released.
 *
 * @since 0.9
 * @tparam A the element type of the collection
 *
 * @define consumesIterator
 * After calling this method, one should discard the iterator it was called on.
 * @define consumesAndProducesIterator
 * After calling this method, one should discard the iterator it was called on, and use only the iterator that was
 * returned. Using the old iterator may result in changes to the new iterator as well.
 * @define consumesTwoAndProducesOneIterator
 * After calling this method, one should discard the iterator it was called on, as well as the one passed as a
 * parameter, and use only the iterator that was returned. Using the old iterators may result in changes to the new
 * iterator as well.
 * @define consumesOneAndProducesTwoIterators
 * After calling this method, one should discard the iterator it was called on, and use only the iterators that were
 * returned. Using the old iterator may result in changes to the new iterators as well.
 * @define consumesTwoIterators
 * After calling this method, one should discard the iterator it was called on, as well as the one passed as parameter.
 * Using any of the old iterators may result in changes to the new iterator as well.
 * @define releasesUnderlying
 * The underlying resource may be released, leading to unexpected exception and errors if an attempt is made to use the
 * iterator again.
 */
trait CloseableIterator[+A] extends Iterator[A] with Closeable {
  self =>

  /**
   * Creates a buffered iterator from this iterator.
   *
   * @see [[scala.collection.BufferedIterator]]
   * @return a buffered iterator producing the same values as this iterator.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def buffered = new CloseableIterator[A] with BufferedIterator[A] {
    private var hd: A = _
    private var hdDefined: Boolean = false

    def head: A = {
      if (!hdDefined) {
        hd = self next ()
        hdDefined = true
      }
      hd
    }

    def hasNext = hdDefined || self.hasNext

    def next() = if (hdDefined) {
      hdDefined = false
      hd
    }
    else self next ()

    def close(){
      self close ()
    }
  }

  /**
   * Returns an iterator which groups this iterator into fixed size
   * blocks.  Example usages:
   * {{{
   *   // Returns List(List(1, 2, 3), List(4, 5, 6), List(7)))
   *   (1 to 7).iterator grouped 3 toList
   *   // Returns List(List(1, 2, 3), List(4, 5, 6))
   *   (1 to 7).iterator grouped 3 withPartial false toList
   *   // Returns List(List(1, 2, 3), List(4, 5, 6), List(7, 20, 25)
   *   // Illustrating that withPadding's argument is by-name.
   *   val it2 = Iterator.iterate(20)(_ + 5)
   *   (1 to 7).iterator grouped 3 withPadding it2.next toList
   * }}}
   *
   * @note Reuse: $consumesAndProducesIterator
   */
  override def grouped[B >: A](size: Int): GroupedIterator[B] = new GroupedIterator[B](size, size)

  /**
   * Returns an iterator which presents a "sliding window" view of
   * another iterator.  The first argument is the window size, and
   * the second is how far to advance the window on each iteration;
   * defaults to `1`.  Example usages:
   * {{{
   *   // Returns List(List(1, 2, 3), List(2, 3, 4), List(3, 4, 5))
   *   (1 to 5).iterator.sliding(3).toList
   *   // Returns List(List(1, 2, 3, 4), List(4, 5))
   *   (1 to 5).iterator.sliding(4, 3).toList
   *   // Returns List(List(1, 2, 3, 4))
   *   (1 to 5).iterator.sliding(4, 3).withPartial(false).toList
   *   // Returns List(List(1, 2, 3, 4), List(4, 5, 20, 25))
   *   // Illustrating that withPadding's argument is by-name.
   *   val it2 = Iterator.iterate(20)(_ + 5)
   *   (1 to 5).iterator.sliding(4, 3).withPadding(it2.next).toList
   * }}}
   *
   * @note Reuse: $consumesAndProducesIterator
   */
  override def sliding[B >: A](size: Int, step: Int = 1): GroupedIterator[B] = new GroupedIterator[B](size, step)

  //Using the super.Foo trick to override the basic definition of Iterator's GroupedIterator
  class GroupedIterator[B >: A](size: Int, step: Int) extends super.GroupedIterator[B](this, size, step)
    with CloseableIterator[Seq[B]] {

    def close(){
      self close ()
    }
  }

  /**
   * Creates a new iterator that maps all produced values of this iterator to new values using a transformation
   * function. The order of the elements is preserved.
   *
   * @param f the transformation function
   * @return a new iterator which transforms every value produced by this iterator by applying the function `f` to it.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def map[B](f: A => B) = new CloseableIterator[B] {
    def hasNext = self.hasNext

    def next() = f(self next ())

    def close(){
      self close ()
    }
  }

  /**
   * Creates a new iterator by applying a function to all values produced by this iterator and concatenating the
   * results.
   *
   * @param f the function to apply on each element.
   * @return the iterator resulting from applying the given iterator-valued function `f` to each value produced by this
   *         iterator and concatenating the results.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def flatMap[B](f: A => GenTraversableOnce[B]): Iterator[B] = new CloseableIterator[B] {
    private var cur: Iterator[B] = Iterator.empty

    @tailrec final def hasNext = cur.hasNext || self.hasNext && {
      cur = f(self next ()).toIterator
      hasNext
    }

    def next(): B = if(hasNext) cur.next() else Iterator.empty.next()

    def close(){
      self close ()
    }
  }

  /**
   * Returns an iterator over all the elements of this iterator that satisfy the predicate `pred`. The order of the
   * elements is preserved.
   *
   * @param pred the predicate used to test values.
   * @return an iterator which produces those values of this iterator which satisfy the predicate `pred`.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def filter(pred: A => Boolean) = new CloseableIterator[A] {
    private var hd: A = _
    private var hdDefined = false

    def hasNext = {
      while(!hdDefined && self.hasNext) {
        hd = self next ()
        hdDefined = pred(hd)
      }

      hdDefined
    }

    def next() = if (hasNext){
      hdDefined = false
      hd
    }
    else Iterator.empty.next()

    def close(){
      self close ()
    }
  }

  /**
   * Creates an iterator by transforming values produced by this iterator with a partial function, dropping those values
   * for which the partial function is not defined.
   *
   * @param pf the partial function which filters and maps the iterator.
   * @return a new `CloseableIterator` which yields application of the partial function `pf` for each contained value of
   *         which it is defined.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def collect[B](pf: PartialFunction[A, B]) = filter(pf isDefinedAt) map pf

  /**
   * Finds the first element for which the given partial function is defined, and applies the partial function `pf` to
   * it.
   *
   * @param pf the partial function
   * @return an option value containing `pf` applied to the first value for which it is defined, or `None` if none exists.
   * @note Reuse: $consumesIterator
   *              $releasesUnderlying
   */
  override def collectFirst[B](pf: PartialFunction[A, B]) = find(pf isDefinedAt) map pf

  /**
   * Creates an iterator returning an interval of the values produced by this iterator.
   *
   * @param start the index of the first element in this iterator which forms part of the slice.
   * @param stop the index of the first element following the slice.
   * @return an iterator which advances this iterator past the first `from` elements using `drop`, and then takes
   *         `to - from` elements, using `take`.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def slice(start: Int, stop: Int) ={
    require(start <= stop, "The sliced range must have a lower bound less than or equal to the upper bound.")

    var cnt = start
    while(cnt > 0 && self.hasNext){
      self next ()
      cnt -= 1
    }
    if(!self.hasNext) self close()

    new CloseableIterator[A]{
      private var limit = stop

      override def hasNext = self.hasNext && 0 <= limit

      override def next() = if(hasNext){
        limit -= 1
        val output = self next ()
        if(!hasNext) close()
        output
      }
      else Iterator.empty next ()

      def close(){
        self close ()
      }
    }
  }

  /**
   * Takes longest prefix of values produced by this iterator that satisfy a predicate.
   *
   * @param  pred The predicate used to test elements.
   * @return An iterator returning the values produced by this iterator, until this iterator produces a value that does
   *         not satisfy the predicate `pred`.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def takeWhile(pred: A => Boolean) = new CloseableIterator[A] {
    private var hd: A = _
    private var hdDefined: Boolean = false
    private var tail: Iterator[A] = self

    def hasNext = hdDefined || tail.hasNext && {
      hd = tail next ()
      hdDefined = pred(hd)
      if(!hdDefined){
        tail = Iterator.empty
        close()
      }
      hdDefined
    }

    def next() = if (hasNext) {
      hdDefined = false
      hd
    }
    else Iterator.empty next ()

    def close(){
      self close ()
    }
  }

  /**
   * Tests whether a predicate holds for some of the values produced by this iterator.
   *
   * @param pred the predicate used to test elements.
   * @return `true` if the given predicate `p` holds for some of the values produced by this iterator, otherwise
   *         `false`.
   * @note Reuse: $consumesIterator
   *              $releasesUnderlying
   */
  override def exists(pred: A => Boolean) ={
    val output = super.exists(pred)
    if(hasNext) close()
    output
  }

  /** Finds the first value produced by the iterator satisfying a predicate.
   *
   * @param pred the predicate used to test values.
   * @return an option value containing the first value produced by the iterator that satisfies predicate `pred`, or
   *         `None` if none exists.
   * @note Reuse: $consumesIterator
   *              $releasesUnderlying
   */
  override def find(pred: A => Boolean) ={
    val output = super.find(pred)
    if(hasNext) close()
    output
  }

  /** Returns the index of the first occurrence of the specified object in this iterable object.
   *
   * @param elem element to search for.
   * @return the index of the first occurrence of `elem` in the values produced by this iterator, or -1 if such an
   *         element does not exist until the end of the iterator is reached.
   * @note Reuse: $consumesIterator
   *              $releasesUnderlying
   */
  override def indexOf[B >: A](elem: B) ={
    val output = super.indexOf(elem)
    if(hasNext) close()
    output
  }

  /** Returns the index of the first produced value satisfying a predicate, or -1.
   *
   * @param pred the predicate to test values
   * @return the index of the first produced value satisfying `pred`, or -1 if such an element does not exist until the
   *         end of the iterator is reached.
   *  @note Reuse: $consumesIterator
    *              $releasesUnderlying
   */
  override def indexWhere(pred: A => Boolean) ={
    val output = super.indexWhere(pred)
    if(hasNext) close()
    output
  }

  /**
   * Returns this iterator with patched values.
   *
   * @param from The start index from which to patch
   * @param patchElems The iterator of patch values
   * @param replaced The number of values in the original iterator that are replaced by the patch.
   * @note Reuse: $consumesTwoAndProducesOneIterator
   */
  override def patch[B >: A](from: Int, patchElems: Iterator[B], replaced: Int) = new CloseableIterator[B] {
    private var indx = 0
    private val to = from + replaced

    def hasNext ={
      val out = if(indx < from || to <= indx) self.hasNext else (self.hasNext || patchElems.hasNext)
      if(!out) close()
      out
    }

    def next() ={
      val result = if(from <= indx && indx < to && patchElems.hasNext){
        if(self.hasNext) self next ()
        patchElems next ()
      }
      else if (self.hasNext) self next ()
      else Iterator.empty.next()

      indx += 1
      result
    }

    def close() {
      close(patchElems)
      self close ()
    }
  }

  /**
   * Partitions this iterator into two iterators according to a predicate.
   *
   * @param pred the predicate on which to partition
   * @return  a pair of iterators: the iterator that satisfies the predicate `pred` and the iterator that does not. The
   *          relative order of the elements in the resulting iterators is the same as in the original iterator.
   * @note Reuse: $consumesOneAndProducesTwoIterators
   */
  override def partition(pred: A => Boolean): (Iterator[A], Iterator[A]) ={
    var canClose = false

    class PartitionIterator(p: A => Boolean, trueBuffer: MQueue[A], falseBuffer: MQueue[A])
        extends CloseableIterator[A] {

      private var hd: A = _
      private var hdDefined = false

      def hasNext = hdDefined || {
        if(trueBuffer.nonEmpty){
          hd = trueBuffer dequeue ()
          hdDefined = true
        }
        else{
          while(self.hasNext && !hdDefined){
            val that = self next ()
            hdDefined = p(that)
            if(hdDefined) hd = that
            else falseBuffer += that
          }
        }
        if(!hdDefined) close()
        hdDefined
      }

      def next() = if(hasNext){
        hdDefined = false
        hd
      }
      else Iterator.empty next ()

      def close(){
        if(canClose) self close ()
        else canClose = true
      }
    }

    val oneBuffer = new MQueue[A]
    val twoBuffer = new MQueue[A]
    val l = new PartitionIterator(pred, oneBuffer, twoBuffer)
    val r = new PartitionIterator(!pred(_), twoBuffer, oneBuffer)
    (l, r)
  }

  /**
   * Splits this Iterator into a prefix/suffix pair according to a predicate.
   *
   * @param pred the test predicate
   * @return a pair of Iterators consisting of the longest prefix of this whose elements all satisfy `pred`, and the
   *         rest of the Iterator.
   *  @note Reuse: $consumesOneAndProducesTwoIterators
   *               $releasesUnderlying
   */
  override def span(pred: A => Boolean): (Iterator[A], Iterator[A]) ={
    val head = new MQueue[A]
    val buffer = buffered
    var continue = buffer.hasNext && pred(buffer.head)
    var canClose = false

    class HeadIterator extends CloseableIterator[A] {
      def hasNext = head.nonEmpty || (continue && buffer.hasNext && pred(buffer.head))

      def next() = if(hasNext){
        if (head.isEmpty){
          val that = buffer next ()
          continue = buffer.hasNext && pred(buffer.head)
          that
        }
        else head dequeue ()
      }
      else Iterator.empty next ()

      def close(){
        if(canClose) buffer close ()
        else canClose = true
      }
    }
    class TailIterator extends CloseableIterator[A] {
      def hasNext = (!continue && buffer.hasNext) || {
        while(buffer.hasNext && pred(buffer.head)) head += buffer next ()
        continue = buffer.hasNext && !pred(buffer.head)
        continue
      }

      def next() = if(hasNext) buffer next () else Iterator.empty next ()

      def close(){
        if(canClose) buffer close ()
        else canClose = true
      }
    }

    (new HeadIterator, new TailIterator)
  }

  /**
   * Creates two new iterators that both iterate over the same elements as this iterator (in the same order).  The
   * duplicate iterators are considered equal if they are positioned at the same element.
   *
   * Given that most methods on iterators will make the original iterator unfit for further use, this methods provides
   * a reliable way of calling multiple such methods on an iterator.
   *
   * @return a pair of iterators
   * @note The implementation may allocate temporary storage for elements iterated by one iterator but not yet by the
   *       other.
   * @note Reuse: $consumesOneAndProducesTwoIterators
   */
  override def duplicate: (Iterator[A], Iterator[A]) ={
    var canClose = false

    class DuplicateIterator(behind: MQueue[A], forward: MQueue[A]) extends CloseableIterator[A]{
      def hasNext = behind.nonEmpty || self.hasNext

      def next() = if(hasNext){
        if(behind.isEmpty){
          val that = self next ()
          forward += that
          that
        }
        else behind dequeue ()
      }
      else Iterator.empty next ()

      def close(){
        if(canClose) self close ()
        else canClose = true
      }
    }

    val buff1 = new MQueue[A]
    val buff2 = new MQueue[A]
    (new DuplicateIterator(buff1, buff2), new DuplicateIterator(buff2, buff1))
  }

  /**
   * Produces a collection containing cumulative results of applying the operator going left to right.
   *
   * @tparam B the type of the elements in the resulting collection
   * @param z the initial value
   * @param op the binary operator applied to the intermediate result and the element
   * @return iterator with intermediate results
   * @note Reuse: $consumesAndProducesIterator
   */
  override def scanLeft[B](z: B)(op: (B,A) => B) = new CloseableIterator[B] {
    var hasNext = true //public because public in Scala source code
    var elem = z //ditto

    def next() = if(hasNext){
      val output = elem
      if(self.hasNext) elem = op(elem, self next ())
      else{
        hasNext = false
        close()
      }
      output
    }
    else Iterator.empty next ()

    def close() {
      self close ()
    }
  }

  /**
   * Copies selected values produced by this iterator to an array. Fills the given array `xs` starting at index `start`
   * with at most `len` values produced by this iterator. Copying will stop once either the end of the current iterator
   * is reached, or the end of the array is reached, or `len` elements have been copied.
   *
   * @param xs the array to fill.
   * @param start the starting index.
   * @param len the maximal number of elements to copy.
   * @tparam B the type of the elements of the array.
   *
   * @note Reuse: $consumesIterator
   *              $releasesUnderlying
   */
  override def copyToArray[B >: A](xs: Array[B], start: Int, len: Int) {
    super.copyToArray(xs, start, len)
    if(hasNext) close()
  }

  /**
   * Creates an iterator formed from this iterator and another iterator by combining corresponding values in pairs. If
   * one of the two iterators is longer than the other, its remaining elements are ignored.
   *
   * @param that The iterator providing the second half of each result pair
   * @return a new iterator containing pairs consisting of corresponding elements of this iterator and `that`. The
   *         number of elements returned by the new iterator is the minimum of the number of elements returned by this
   *         iterator and `that`.
   * @note Reuse: $consumesTwoAndProducesOneIterator
   */
  override def zip[B](that: Iterator[B]): Iterator[(A, B)] = new CloseableIterator[(A, B)] {
    def hasNext = self.hasNext && that.hasNext

    def next = if(hasNext){
      val output = (self next (), that next ())
      if(!hasNext) close()
      output
    }
    else Iterator.empty.next()

    def close(){
      close(that)
      self close ()
    }
  }

  /**
   * Creates an iterator formed from this iterator and another iterator by combining corresponding elements in pairs. If
   * one of the two iterators is shorter than the other, placeholder elements are used to extend the shorter iterator to
   * the length of the longer.
   *
   * @param that iterator `that` may have a different length as the self iterator.
   * @param thisElem element `thisElem` is used to fill up the resulting iterator if the self iterator is shorter than
   *                 `that`
   * @param thatElem element `thatElem` is used to fill up the resulting iterator if `that` is shorter than the self
   *                 iterator.
   * @return a new iterator containing pairs consisting of corresponding values of this iterator and `that`. The length
   *         of the returned iterator is the maximum of the lengths of this iterator and `that`. If this iterator is
   *         shorter than `that`, `thisElem` values are used to pad the result. If `that` is shorter than this iterator,
   *         `thatElem` values are used to pad the result.
   * @note Reuse: $consumesTwoAndProducesOneIterator
   */
  override def zipAll[B, A1 >: A, B1 >: B](that: Iterator[B], thisElem: A1, thatElem: B1): Iterator[(A1, B1)] =
    new CloseableIterator[(A1, B1)] {
      def hasNext = self.hasNext || that.hasNext

      def next() ={
        val output = if (self.hasNext) {
          if (that.hasNext) (self next (), that next ())
          else (self next (), thatElem)
        }
        else if(that.hasNext){
          (thisElem, that next ())
        }
        else Iterator.empty.next()

        if(!hasNext) close()
        output
      }

      def close(){
        close(that)
        self close ()
      }
    }

  /**
   * Creates an iterator that pairs each element produced by this iterator with its index, counting from 0.
   *
   * @return a new iterator containing pairs consisting of corresponding elements of this iterator and their indices.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def zipWithIndex = new CloseableIterator[(A, Int)] {
    var idx = 0 //This is public because it's public in Scala source code!

    def hasNext = self.hasNext

    def next = {
      val ret = (self next (), idx)
      idx += 1
      ret
    }

    def close(){
      self close ()
    }
  }

  /** Appends an element value to this iterator until a given target length is reached.
   *
   * @param len the target length
   * @param elem the padding value
   * @return a new iterator consisting of producing all values of this iterator, followed by the minimal number of
   *         occurrences of `elem` so that the number of produced values is at least `len`.
   * @note Reuse: $consumesAndProducesIterator
   */
  override def padTo[B >: A](len: Int, elem: B) = new CloseableIterator[B] {
    private var count = 0

    def hasNext = self.hasNext || count < len

    def next() = {
      count += 1
      if (self.hasNext) self next ()
      else if (count <= len) elem
      else Iterator.empty.next()
    }

    def close(){
      self close ()
    }
  }

  /**
   * Tests if another iterator produces the same values as this one.
   *
   * @param that the other iterator
   * @return `true`, if both iterators produce the same elements in the same order, `false` otherwise.
   *
   * @note Reuse: $consumesTwoIterators
   *              $releasesUnderlying
   */
  override def sameElements(that: Iterator[_]) ={
    val output = super.sameElements(that)
    if(hasNext) close()
    close(that)
    output
  }

  protected[jdbc] def close(that: Iterator[_]){
    that match{
      case x:CloseableIterator[_] if x.hasNext => x close ()
      case _ =>
    }
  }

  override def corresponds[B](that: GenTraversableOnce[B])(pred: (A, B) => Boolean) ={
    val output = super.corresponds(that)(pred)
    if(hasNext) close()
    output
  }

  /**
   * Releases the resource from control by the iterator.
   *
   * @note Reuse: $releasesUnderlying
   */
  def close()

  /** This is defined as an unreliable fail-safe mechanism for releasing the resource. */
  override def finalize(){
    if(hasNext) close()
  }
}