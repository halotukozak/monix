/*
 * Copyright (c) 2014-2020 by The Monix Project Developers.
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

package monix.reactive.internal.builders

import monix.execution.cancelables.CompositeCancelable
import monix.execution.{Ack, Cancelable}
import monix.execution.Ack.{Continue, Stop}
import scala.util.control.NonFatal
import monix.reactive.Observable
import monix.reactive.observers.Subscriber

import scala.concurrent.{Future, Promise}
import scala.util.Success

private[reactive] final class Zip6Observable[A1, A2, A3, A4, A5, A6, +R](
  obsA1: Observable[A1],
  obsA2: Observable[A2],
  obsA3: Observable[A3],
  obsA4: Observable[A4],
  obsA5: Observable[A5],
  obsA6: Observable[A6])(f: (A1, A2, A3, A4, A5, A6) => R)
  extends Observable[R] {

  def unsafeSubscribeFn(out: Subscriber[R]): Cancelable = {
    import out.scheduler

    val lock = new AnyRef
    // MUST BE synchronized by `lock`
    var isDone = false
    // MUST BE synchronized by `lock`
    var lastAck = Continue: Future[Ack]
    // MUST BE synchronized by `lock`
    var elemA1: A1 = null.asInstanceOf[A1]
    // MUST BE synchronized by `lock`
    var hasElemA1 = false
    // MUST BE synchronized by `lock`
    var elemA2: A2 = null.asInstanceOf[A2]
    // MUST BE synchronized by `lock`
    var hasElemA2 = false
    // MUST BE synchronized by `lock`
    var elemA3: A3 = null.asInstanceOf[A3]
    // MUST BE synchronized by `lock`
    var hasElemA3 = false
    // MUST BE synchronized by `lock`
    var elemA4: A4 = null.asInstanceOf[A4]
    // MUST BE synchronized by `lock`
    var hasElemA4 = false
    // MUST BE synchronized by `lock`
    var elemA5: A5 = null.asInstanceOf[A5]
    // MUST BE synchronized by `lock`
    var hasElemA5 = false
    // MUST BE synchronized by `lock`
    var elemA6: A6 = null.asInstanceOf[A6]
    // MUST BE synchronized by `lock`
    var hasElemA6 = false
    // MUST BE synchronized by `lock`
    var continueP = Promise[Ack]()
    // MUST BE synchronized by `lock`
    var sourcesCompleted: Int = 0

    def completeWithNext: Boolean = sourcesCompleted >= 1

    // MUST BE synchronized by `lock`
    def rawOnNext(a1: A1, a2: A2, a3: A3, a4: A4, a5: A5, a6: A6): Future[Ack] =
      if (isDone) Stop
      else {
        var streamError = true
        try {
          val c = f(a1, a2, a3, a4, a5, a6)
          streamError = false
          val ack = out.onNext(c)
          if (completeWithNext) {
            ack.onComplete(_ => signalOnComplete(false))
          }
          ack
        } catch {
          case NonFatal(ex) if streamError =>
            isDone = true
            out.onError(ex)
            Stop
        } finally {
          hasElemA1 = false
          hasElemA2 = false
          hasElemA3 = false
          hasElemA4 = false
          hasElemA5 = false
          hasElemA6 = false
        }
      }

    // MUST BE synchronized by `lock`
    def signalOnNext(a1: A1, a2: A2, a3: A3, a4: A4, a5: A5, a6: A6): Future[Ack] = {
      lastAck = lastAck match {
        case Continue => rawOnNext(a1, a2, a3, a4, a5, a6)
        case Stop => Stop
        case async =>
          async.flatMap {
            // async execution, we have to re-sync
            case Continue => lock.synchronized(rawOnNext(a1, a2, a3, a4, a5, a6))
            case Stop => Stop
          }
      }

      val oldP = continueP
      continueP = Promise[Ack]()
      oldP.completeWith(lastAck)
      lastAck
    }

    def signalOnError(ex: Throwable): Unit = lock.synchronized {
      if (!isDone) {
        isDone = true
        out.onError(ex)
        lastAck = Stop
      }
    }

    def rawOnComplete(): Unit =
      if (!isDone) {
        isDone = true
        out.onComplete()
      }

    def signalOnComplete(hasElem: Boolean): Unit = lock.synchronized {
      // If all other sources have completed then
      // we won't receive the next batch of elements
      if (!hasElem || sourcesCompleted == 5) {
        lastAck match {
          case Continue => rawOnComplete()
          case Stop => () // do nothing
          case async =>
            async.onComplete {
              case Success(Continue) =>
                lock.synchronized(rawOnComplete())
              case _ =>
                () // do nothing
            }
        }

        continueP.trySuccess(Stop)
        lastAck = Stop
      } else {
        sourcesCompleted += 1
      }
    }

    val composite = CompositeCancelable()

    composite += obsA1.unsafeSubscribeFn(new Subscriber[A1] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A1): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA1 = elem
          if (!hasElemA1) hasElemA1 = true

          if (hasElemA2 && hasElemA3 && hasElemA4 && hasElemA5 && hasElemA6)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5, elemA6)
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)

      def onComplete(): Unit =
        signalOnComplete(hasElemA1)
    })

    composite += obsA2.unsafeSubscribeFn(new Subscriber[A2] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A2): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA2 = elem
          if (!hasElemA2) hasElemA2 = true

          if (hasElemA1 && hasElemA3 && hasElemA4 && hasElemA5 && hasElemA6)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5, elemA6)
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)

      def onComplete(): Unit =
        signalOnComplete(hasElemA2)
    })

    composite += obsA3.unsafeSubscribeFn(new Subscriber[A3] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A3): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA3 = elem
          if (!hasElemA3) hasElemA3 = true

          if (hasElemA1 && hasElemA2 && hasElemA4 && hasElemA5 && hasElemA6)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5, elemA6)
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)

      def onComplete(): Unit =
        signalOnComplete(hasElemA3)
    })

    composite += obsA4.unsafeSubscribeFn(new Subscriber[A4] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A4): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA4 = elem
          if (!hasElemA4) hasElemA4 = true

          if (hasElemA1 && hasElemA2 && hasElemA3 && hasElemA5 && hasElemA6)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5, elemA6)
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)

      def onComplete(): Unit =
        signalOnComplete(hasElemA4)
    })

    composite += obsA5.unsafeSubscribeFn(new Subscriber[A5] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A5): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA5 = elem
          if (!hasElemA5) hasElemA5 = true

          if (hasElemA1 && hasElemA2 && hasElemA3 && hasElemA4 && hasElemA6)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5, elemA6)
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)

      def onComplete(): Unit =
        signalOnComplete(hasElemA5)
    })

    composite += obsA6.unsafeSubscribeFn(new Subscriber[A6] {
      implicit val scheduler = out.scheduler

      def onNext(elem: A6): Future[Ack] = lock.synchronized {
        if (isDone) Stop
        else {
          elemA6 = elem
          if (!hasElemA6) hasElemA6 = true

          if (hasElemA1 && hasElemA2 && hasElemA3 && hasElemA4 && hasElemA5)
            signalOnNext(elemA1, elemA2, elemA3, elemA4, elemA5, elemA6)
          else
            continueP.future
        }
      }

      def onError(ex: Throwable): Unit =
        signalOnError(ex)

      def onComplete(): Unit =
        signalOnComplete(hasElemA6)
    })

    composite
  }
}
