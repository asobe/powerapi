/**
 * Copyright (C) 2012 Inria, University Lille 1.
 *
 * This file is part of PowerAPI.
 *
 * PowerAPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * PowerAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PowerAPI. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contact: powerapi-user-list@googlegroups.com.
 */
package fr.inria.powerapi.library

import scala.concurrent.Await
import scala.concurrent.duration.{FiniteDuration, Duration, DurationInt}
import scala.concurrent.{ Future, blocking }
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.{ Props, ActorSystem, ActorPath, ActorRef }
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.Status.Success
import fr.inria.powerapi.core.Clock
import fr.inria.powerapi.core.EnergyModule
import fr.inria.powerapi.core.{ Message, MessagesToListen, Listener, Component, TickIt, UnTickIt, TickSubscription, Process, ProcessedMessage }
import fr.inria.powerapi.core.Processor
import fr.inria.powerapi.core.Reporter
import fr.inria.powerapi.core.CallbackReporter

/**
 * PowerAPI's messages definition
 *
 * @author abourdon
 */
case class StartComponent(componentType: Class[_ <: Component]) extends Message
case class StartConfiguredComponent(propsObj: Props, componentType: Class[_ <: Component]) extends Message
case class StartSubscription(actorRef: ActorRef, componentType: Class[_ <: Component]) extends Message
case class StopComponent(componentType: Class[_ <: Component]) extends Message
case class StopComponents() extends Message
case class CancelSubscription(actorRef: ActorRef, componentType: Class[_ <: Component]) extends Message

case class StartMonitoring(
  process: Process = Process(-1),
  duration: FiniteDuration = Duration.Zero,
  processor: Class[_ <: Processor] = null,
  listener: Class[_ <: Listener] = null) extends Message
case class StartConfiguredMonitoring(
  process: Process = Process(-1),
  duration: FiniteDuration = Duration.Zero,
  processor: Class[_ <: Processor] = null,
  listener: (Props, Class[_ <: Listener]) = (null,null)) extends Message
case class StopMonitoring(
  process: Process = Process(-1),
  duration: FiniteDuration = Duration.Zero,
  processor: Class[_ <: Processor] = null,
  listener: Class[_ <: Listener] = null) extends Message

case object MonitoringStopped

/**
 * PowerAPI engine which start/stop every PowerAPI components such as Listener or Energy Module.
 *
 * Current implementation start/stop component in giving the component type.
 * This restriction helps to instantiate only one implementation of a given component.
 *
 * @author abourdon
 */
class PowerAPI extends Component {
  val components = collection.mutable.HashMap[Class[_ <: Component], ActorPath]()
  implicit val timeout = Timeout(5.seconds)

  def messagesToListen = Array(classOf[StartComponent], classOf[StartConfiguredComponent], classOf[StartSubscription],
    classOf[StopComponent], classOf[StopComponents], classOf[CancelSubscription],
    classOf[StartMonitoring], classOf[StartConfiguredMonitoring], classOf[StopMonitoring])

  def acquire = {
    case startComponent: StartComponent => process(startComponent)
    case startConfiguredComponent: StartConfiguredComponent => process(startConfiguredComponent)
    case startSubscription: StartSubscription => process(startSubscription)
    case stopComponent: StopComponent => process(stopComponent)
    case stopComponents: StopComponents => process(stopComponents)
    case cancelSubscription: CancelSubscription => process(cancelSubscription)
    case startMonitoring: StartMonitoring => process(startMonitoring)
    case startConfigredMonitoring: StartConfiguredMonitoring => process(startConfigredMonitoring)
    case stopMonitoring: StopMonitoring => process(stopMonitoring)
  }

  def process(startComponent: StartComponent) {
    /**
     * Starts a component to work into the PowerAPI system.
     *
     * @param componentType: component type that have to be started.
     */
    def start(componentType: Class[_ <: Component]) {
      if (components.contains(componentType)) {
        if (log.isWarningEnabled) log.warning("component " + componentType.getCanonicalName + " already started")
      } else {
        val component = context.actorOf(Props(componentType.newInstance), name = componentType.getCanonicalName)
        process(StartSubscription(component, componentType))
      }
    }

    start(startComponent.componentType)
  }

  def process(startConfiguredComponent: StartConfiguredComponent) {
    /**
     * Start a configured component with Props object to work into the PowerAPI system.
     *
     * @param propsObj: Props to represent the component (usage of factory to create the actor is recommended)
     * @param componentType: component type to associate
     */
    def start(propsObj: Props, componentType: Class[_ <: Component]) {
      if (components.contains(componentType)) {
        if (log.isWarningEnabled) log.warning("component " + componentType.getCanonicalName + " already started")
      } else {
        val component = context.actorOf(propsObj, name = componentType.getCanonicalName)
        process(StartSubscription(component, componentType))
      }
    }

    start(startConfiguredComponent.propsObj, startConfiguredComponent.componentType)
  }

  def process(startSubscription: StartSubscription) {
    /**
     * Subscription of the actor on the event bus
     *
     * @param actorRef: Reference of the given actor
     */
    def subscribe(actorRef: ActorRef, componentType: Class[_ <: Component]) {
      val messages = Await.result(actorRef ? MessagesToListen, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]
      messages.foreach(message => context.system.eventStream.subscribe(actorRef, message))
      components += (componentType -> actorRef.path)
    }

    subscribe(startSubscription.actorRef, startSubscription.componentType)

    // Be aware to start the Clock if a component has been started
    if (components.size > 0 && !components.contains(classOf[Clock])) {
      process(StartComponent(classOf[Clock]))
      if (log.isDebugEnabled) log.debug("Clock started")
    }
  }

  def process(stopComponent: StopComponent) {
    /**
     * Stops a component from the PowerAPI system.
     *
     * @param componentType: component type that have to be stopped.
     */
    def stop(componentType: Class[_ <: Component]) {
      if (components.contains(componentType)) {
        val futureComponent = context.actorSelection(components(componentType)).resolveOne()
        val component = Await.result(futureComponent, timeout.duration)

        process(CancelSubscription(component, componentType))
      } else {
        if (log.isWarningEnabled) log.warning("Component " + componentType.getCanonicalName + " is not started")
      }
    }

    stop(stopComponent.componentType)
  }

  def process(stopComponents: StopComponents) {
    /**
     * Stop all the components stored in the hashmap
    */
    components.filter((tuple) => tuple._1 != classOf[Clock]).foreach((tuple) => process(StopComponent(tuple._1)))
    sender ! MonitoringStopped
  }

  def process(cancelSubscription: CancelSubscription) {
    /**
     * Cancel the subscription of the actor on the event bus and stop it.
     *
     * @param actorRef: Reference of the given actor.
     */
    def unsubscribe(actorRef: ActorRef, componentType: Class[_ <: Component]) {
      val messages = Await.result(actorRef ? MessagesToListen, timeout.duration).asInstanceOf[Array[Class[_ <: Message]]]
      messages.foreach(message => context.system.eventStream.unsubscribe(actorRef, message))
      context.stop(actorRef)
      components -= componentType
      
      if(componentType == classOf[Clock]) {
        if (log.isDebugEnabled) log.debug("Clock stopped.")
      }
    }

    unsubscribe(cancelSubscription.actorRef, cancelSubscription.componentType)

    // Be aware to stop the Clock if all other components has been stopped.
    if (components.size == 1 && components.contains(classOf[Clock])) {
      process(StopComponent(classOf[Clock]))
      if (log.isDebugEnabled) log.debug("Clock is going to shutdown.")
    }
  }

  def process(startMonitoring: StartMonitoring) {
    /**
     * Starts the monitoring of a process during a certain duration and listened by a given listener.
     *
     * @param process: process to monitor.
     * @param duration: duration period monitoring.
     * @param processor: processor type which will be aware by monitoring results.
     * @param listener: listener type which will be aware by processor messages and display final results.
     */
    def start(proc: Process, duration: FiniteDuration, processor: Class[_ <: Processor], listener: Class[_ <: Listener]) {
      if (processor != null) {
        process(StartComponent(processor))
      }
      if (listener != null) {
        process(StartComponent(listener))
      }
      if (proc != Process(-1) && duration != Duration.Zero) {
        context.system.eventStream.publish(TickIt(TickSubscription(proc, duration)))
      }
    }

    start(startMonitoring.process, startMonitoring.duration, startMonitoring.processor, startMonitoring.listener)
  }

  def process(startConfigredMonitoring: StartConfiguredMonitoring) {
    /**
     * Starts the monitoring of a process during a certain duration and listened by a configured listener (with Props).
     *
     * @param process: process to monitor.
     * @param duration: duration period monitoring.
     * @param processor: processor type which will be aware by monitoring results.
     * @param propsObj: Props to represent the component (usage of factory to create the actor is recommended)
     */
    def start(proc: Process, duration: FiniteDuration, processor: Class[_ <: Processor], listener: (Props, Class[_ <: Listener])) {
      if (processor != null) {
        process(StartComponent(processor))
      }
      if (listener != null) {
        process(StartConfiguredComponent(listener._1, listener._2))
      }
      if (proc != Process(-1) && duration != Duration.Zero) {
        context.system.eventStream.publish(TickIt(TickSubscription(proc, duration)))
      }
    }

    start(startConfigredMonitoring.process, startConfigredMonitoring.duration, startConfigredMonitoring.processor, startConfigredMonitoring.listener)
  }

  def process(stopMonitoring: StopMonitoring) {
    /**
     * Stops the monitoring of a process during a certain duration and listened by a given listener.
     *
     * @param process: process to stop to monitor.
     * @param duration: duration period monitoring.
     * @param processor : processor type which will be unaware by monitoring results.
     * @param listener: listener type which will be unaware by processor messages.
     */
    def stop(proc: Process, duration: FiniteDuration, processor: Class[_ <: Processor], listener: Class[_ <: Listener]) {
      if (processor != null) {
        process(StopComponent(processor))
      }
      if (listener != null) {
        process(StopComponent(listener))
      }
      if (proc != Process(-1) && duration != Duration.Zero) {
        context.system.eventStream.publish(UnTickIt(TickSubscription(proc, duration)))
      }
    }

    stop(stopMonitoring.process, stopMonitoring.duration, stopMonitoring.processor, stopMonitoring.listener)
  }
}

/**
 * PowerAPI companion object that provide an API to use PowerAPI library.
 *
 * @author abourdon, mcolmant
 */
object PowerAPI {
  implicit lazy val system = ActorSystem("PowerAPI")
  lazy val engine = system.actorOf(Props[PowerAPI])

  /**
   * Start the energy module associated to the given type.
   *
   * @param energyModuleType: energy module type to start.
   */
  def startEnergyModule(energyModuleType: Class[_ <: Component]) {
    engine ! StartComponent(energyModuleType)
  }

  /**
   * Start the energy module with the Props object and the attached component type
   *
   * @param propsObj: Props to represent the component (usage of factory to create the actor is recommended) 
   * @param energyModuleType: associated type component
   */
  def startEnergyModule(propsObj: Props, energyModuleType: Class[_ <: Component]) {
    engine ! StartConfiguredComponent(propsObj, energyModuleType)
  }

  /**
   * Stops the energy module associated to the given type.
   *
   * @param energyModuleType: energy module type to stop.
   */
  def stopEnergyModule(energyModuleType: Class[_ <: EnergyModule]) {
    engine ! StopComponent(energyModuleType)
  }

  /**
   * Start the monitoring of the given process during the given duration period.
   * Results are then processed by the given processor and displayed by the given listener.
   *
   * @param process: process to monitor.
   * @param duration: duration period monitoring.
   * @param processor: processor type which will be aware by monitoring results.
   * @param listener: reporter type which will be aware by processor messages and display final results.
   */
  def startMonitoring(process: Process, duration: FiniteDuration, processor: Class[_ <: Processor], listener: Class[_ <: Listener]) {
    engine ! StartMonitoring(process = process, duration = duration, processor = processor, listener = listener)
  }

  // All the variants, no overloads allowed with default values
  def startMonitoring(process: Process, duration: FiniteDuration, processor: Class[_ <: Processor], listener: (Props, Class[_ <: Listener])) {
    engine ! StartConfiguredMonitoring(process = process, duration = duration, processor = processor, listener = listener)
  }

  def startMonitoring(process: Process, duration: FiniteDuration, listener: Class[_ <: Listener]) {
    engine ! StartMonitoring(process = process, duration = duration, listener = listener)
  }

  def startMonitoring(process: Process, duration: FiniteDuration) {
    engine ! StartMonitoring(process = process, duration = duration)
  }

  def startMonitoring(processor: Class[_ <: Processor], listener: Class[_ <: Listener]) {
    engine ! StartMonitoring(processor = processor, listener = listener)
  }

  def startMonitoring(processor: Class[_ <: Processor], listener: (Props, Class[_ <: Listener])) {
    engine ! StartConfiguredMonitoring(processor = processor, listener = listener)
  }

  /**
   * Stops the monitoring of the given process during the given duration period.
   * Processors and listeners can also be stopped.
   *
   * @param process: process to stop to monitor.
   * @param duration: duration period monitoring.
   * @param processor : processor type which will be unaware by monitoring results.
   * @param reporter: reporter type which will be unaware by processor messages.
   */
  def stopMonitoring(process: Process = Process(-1), duration: FiniteDuration = Duration.Zero, processor: Class[_ <: Processor] = null, listener: Class[_ <: Listener] = null) {
    engine ! StopMonitoring(process, duration, processor, listener)
  }
}

class API(name: String) {
  implicit lazy val system = ActorSystem(name)
  lazy val engine = system.actorOf(Props[PowerAPI])
  implicit val timeout = Timeout(5.seconds)

  /**
   * Start the component associated to the given type
   * @param componentType: component type to start.
   */
  def startComponent(componentType: Class[_ <: Component]) {
    engine ! StartComponent(componentType)
  }

  /**
   * Stop all the components
   */
  def stop() {
    Await.result(engine ? StopComponents(), timeout.duration)
  }

  /**
   * Start the monitoring of the given processes during with a specified frequency for the Clock component
   * Results are displayed by the given reporter
   *
   * @param processes: processes to monitor.
   * @param duration: clock frequency.
   * @param reporters: reporter used to display the results.
   */
  def startMonitoring(processes: Array[Process], duration: FiniteDuration, reporter: Class[_ <: Reporter], timeout: FiniteDuration) {
    // Start the reporter
    engine ! StartMonitoring(listener = reporter)
    // Start the monitoring with the given processes
    processes.foreach(process => engine ! StartMonitoring(process = process, duration = duration))

    if(timeout != null) {
        Thread.sleep(timeout.toMillis)
    }
  }

  /**
   * Start the monitoring of the given processes during with a specified frequency for the Clock component
   * Results are displayed by the anonymous function
   *
   * @param processes: processes to monitor.
   * @param duration: clock frequency.
   * @param process: function used to display the results.
   */
  def startMonitoring(processes: Array[Process], duration: FiniteDuration, reporter: (ProcessedMessage) => Unit, timeout: FiniteDuration) {
    // Start the callback reporter with the specified behavior (function) for the reporting
    engine ! StartConfiguredMonitoring(listener = (CallbackReporter.props(reporter), classOf[CallbackReporter]))
    // Start the monitoring with the given processes
    processes.foreach(process => engine ! StartMonitoring(process = process, duration = duration))
    
    if(timeout != null) {
      Thread.sleep(timeout.toMillis)
    }
  }

  /**
   * Start the monitoring of the given processes during with a specified frequency for the Clock component
   * Results are displayed by the given actor
   *
   * @param processes: processes to monitor.
   * @param duration: clock frequency.
   * @param actorRef: actor reference to process the messages.
   */
  def startMonitoring(processes: Array[Process], duration: FiniteDuration, reporter: (ActorRef, Class[_ <: Reporter]), timeout: FiniteDuration) {
    engine ! StartSubscription(reporter._1, reporter._2)
    // Start the monitoring with the given processes
    processes.foreach(process => engine ! StartMonitoring(process = process, duration = duration))

    if(timeout != null) {
      Thread.sleep(timeout.toMillis)
    }
  }

  /**
   * Stop the monitoring of the given processes with the specified frequency
   * @param processes: processes to monitor.
   * @param duration: clock frequency.
   * @param reporters: reporters to stop.
   */
  def stopMonitoring(processes: Array[Process] = Array(), duration: FiniteDuration = Duration.Zero) {
    // Stop the monitoring of the given processes
    processes.foreach(process => engine ! StopMonitoring(process = process, duration = duration))
  }
}