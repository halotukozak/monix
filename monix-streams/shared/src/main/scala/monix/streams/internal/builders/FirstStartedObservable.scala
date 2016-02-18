/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.streams.internal.builders

import monix.execution.Ack.Cancel
import monix.execution.{Ack, Scheduler}
import monix.streams.observers.Subscriber
import monix.streams.{Observer, Observable}
import org.sincron.atomic.{Atomic, AtomicInt}

import scala.concurrent.Future

private[streams] final class FirstStartedObservable[T](source: Observable[T]*)
  extends Observable[T] {

  override def unsafeSubscribeFn(subscriber: Subscriber[T]): Unit = {
    import subscriber.scheduler
    val finishLine = Atomic(0)
    var idx = 0

    for (observable <- source) {
      createSubscription(observable, subscriber, finishLine, idx + 1)
      idx += 1
    }

    // if the list of observables was empty, just
    // emit `onComplete`
    if (idx == 0) subscriber.onComplete()
  }

  // Helper function used for creating a subscription that uses `finishLine` as guard
  def createSubscription(observable: Observable[T], observer: Observer[T], finishLine: AtomicInt, idx: Int)(implicit s: Scheduler): Unit =
    observable.unsafeSubscribeFn(new Observer[T] {
      // for fast path
      private[this] var finishLineCache = 0

      private def shouldStream(): Boolean = {
        if (finishLineCache != idx) finishLineCache = finishLine.get
        if (finishLineCache == idx)
          true
        else if (!finishLine.compareAndSet(0, idx))
          false
        else {
          finishLineCache = idx
          true
        }
      }

      def onNext(elem: T): Future[Ack] = {
        if (shouldStream())
          observer.onNext(elem)
        else
          Cancel
      }

      def onError(ex: Throwable): Unit = {
        if (shouldStream()) observer.onError(ex)
      }

      def onComplete(): Unit = {
        if (shouldStream()) observer.onComplete()
      }
    })
}
