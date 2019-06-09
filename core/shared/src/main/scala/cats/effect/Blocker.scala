/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats
package effect

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory}
import cats.data.NonEmptyList

/**
 * An execution context that is safe to use for blocking operations.
 *
 * Used in conjunction with [[ContextShift]], this type allows us to write functions
 * that require a special `ExecutionContext` for evaluation, while discouraging the
 * use of a shared, general purpose pool (e.g. the global context).
 *
 * Instances of this class should *not* be passed implicitly.
 */
final class Blocker private (val blockingContext: ExecutionContext) {

  /**
   * Like `Sync#delay` but the supplied thunk is evaluated on the blocking
   * execution context.
   */
  def delay[F[_], A](thunk: => A)(implicit F: Sync[F], cs: ContextShift[F]): F[A] =
    evalOn(F.delay(thunk))

  /**
   * Evaluates the supplied task on the blocking execution context via `evalOn`.
   */
  def evalOn[F[_], A](fa: F[A])(implicit cs: ContextShift[F]): F[A] =
    cs.evalOn(blockingContext)(fa)
}

object Blocker {

  /**
   * Creates a blocker that is backed by a cached thread pool.
   */
  def apply[F[_]](implicit F: Sync[F]): Resource[F, Blocker] =
    fromExecutorService(F.delay(Executors.newCachedThreadPool(new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = new Thread(r, "cats-effect-blocker")
        t.setDaemon(true)
        t
      }
    })))

  /**
   * Creates a blocker backed by the `ExecutorService` returned by the
   * supplied task. The executor service is shut down upon finalization
   * of the returned resource.
   * 
   * If there are pending tasks in the thread pool at time the returned
   * `Blocker` is finalized, the finalizer fails with a `Blocker.OutstandingTasksAtShutdown` 
   * exception.
   */
  def fromExecutorService[F[_]](makeExecutorService: F[ExecutorService])(implicit F: Sync[F]): Resource[F, Blocker] =
    Resource.make(makeExecutorService) { ec =>
        val tasks = F.delay {
          val tasks = ec.shutdownNow()
          val b = List.newBuilder[Runnable]
          val itr = tasks.iterator
          while (itr.hasNext)
            b += itr.next
          NonEmptyList.fromList(b.result)
        }
        F.flatMap(tasks) {
          case Some(t) => F.raiseError(new OutstandingTasksAtShutdown(t))
          case None => F.unit
        }
    }.map(es => liftExecutorService(es))

  /**
   * Creates a blocker that delegates to the supplied executor service.
   */
  def liftExecutorService(es: ExecutorService): Blocker =
    liftExecutionContext(ExecutionContext.fromExecutorService(es))

  /**
   * Creates a blocker that delegates to the supplied execution context.
   * 
   * This must not be used with general purpose contexts like
   * `scala.concurrent.ExecutionContext.Implicits.global'.
   */
  def liftExecutionContext(ec: ExecutionContext): Blocker = new Blocker(ec)

  /** Thrown if there are tasks queued in the thread pool at the time a `Blocker` is finalized. */
  final class OutstandingTasksAtShutdown(val tasks: NonEmptyList[Runnable]) extends IllegalStateException("There were outstanding tasks at time of shutdown of the thread pool")
}