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

import monix.streams.observers.Subscriber
import monix.streams.{Observable, CanObserve}
import language.higherKinds
import scala.util.control.NonFatal

private[streams] final class DeferObservable[+A, F[_] : CanObserve](fa: => F[A])
  extends Observable[A] {

  def unsafeSubscribeFn(subscriber: Subscriber[A]): Unit = {
    // Protect against user code, but if the subscription fails
    // then the behavior should be left undefined, otherwise we
    // can get weird effects.
    var streamErrors = true
    try {
      val source = CanObserve[F].observable(fa)
      streamErrors = false
      source.unsafeSubscribeFn(subscriber)
    } catch {
      case NonFatal(ex) if streamErrors =>
        subscriber.onError(ex)
    }
  }
}
