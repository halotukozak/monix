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

import monix.execution.Ack.{Cancel, Continue}
import monix.execution.{Scheduler, Ack}
import monix.streams.Observable
import monix.streams.observers.Subscriber

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

private[streams] final class RepeatEvalObservable[+A](eval: => A)
  extends Observable[A] {

  def unsafeSubscribeFn(subscriber: Subscriber[A]): Unit = {
    val s = subscriber.scheduler
    fastLoop(subscriber, s.batchedExecutionModulus, 0)(s)
  }

  def reschedule(ack: Future[Ack], o: Subscriber[A], modulus: Int)(implicit s: Scheduler): Unit =
    ack.onComplete {
      case Success(success) =>
        if (success == Continue) fastLoop(o, modulus, 0)
      case Failure(ex) =>
        s.reportFailure(ex)
      case _ =>
        () // this was a Cancel, do nothing
    }

  @tailrec
  def fastLoop(o: Subscriber[A], modulus: Int, syncIndex: Int)(implicit s: Scheduler): Unit = {
    val ack = try o.onNext(eval) catch {
      case NonFatal(ex) =>
        Future.failed(ex)
    }

    val nextIndex =
      if (ack == Continue) (syncIndex + 1) & modulus
      else if (ack == Cancel) -1
      else 0

    if (nextIndex > 0)
      fastLoop(o, modulus, nextIndex)
    else if (nextIndex == 0)
      reschedule(ack, o, modulus)
  }
}
