/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.utils

import java.util.concurrent._
import java.util.concurrent.atomic._
import java.lang.IllegalStateException

/**
 * A scheduler for running jobs in the background
 */
class KafkaScheduler(val numThreads: Int, val baseThreadName: String, isDaemon: Boolean) extends Logging {
  private val threadId = new AtomicLong(0)
  private var executor:ScheduledThreadPoolExecutor = null
  startUp

  def startUp = {
    executor = new ScheduledThreadPoolExecutor(numThreads, new ThreadFactory() {
      def newThread(runnable: Runnable): Thread = Utils.daemonThread(baseThreadName + threadId.getAndIncrement, runnable)
    })
    executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
  }

  def hasShutdown: Boolean = executor.isShutdown

  private def ensureExecutorHasStarted = {
    if(executor == null)
      throw new IllegalStateException("Kafka scheduler has not been started")
  }

  def scheduleWithRate(fun: () => Unit, delayMs: Long, periodMs: Long) = {
    ensureExecutorHasStarted
    executor.scheduleAtFixedRate(Utils.loggedRunnable(fun), delayMs, periodMs, TimeUnit.MILLISECONDS)
  }

  def shutdownNow() {
    ensureExecutorHasStarted
    executor.shutdownNow()
    info("Forcing shutdown of scheduler " + baseThreadName)
  }

  def shutdown() {
    ensureExecutorHasStarted
    executor.shutdown()
    info("Shutdown scheduler " + baseThreadName)
  }
}