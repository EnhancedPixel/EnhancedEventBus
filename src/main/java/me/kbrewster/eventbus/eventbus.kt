package me.kbrewster.eventbus

import me.kbrewster.eventbus.exception.ExceptionHandler
import me.kbrewster.eventbus.invokers.InvokerType
import me.kbrewster.eventbus.invokers.ReflectionInvoker
import java.lang.reflect.Modifier
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.jvm.internal.Intrinsics

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
annotation class Subscribe(val priority: Int = 0)

class EventBus @JvmOverloads constructor(private val invokerType: InvokerType = ReflectionInvoker(),
               private val exceptionHandler: ExceptionHandler = object: ExceptionHandler {
                   override fun handle(exception: Exception) {
                       throw exception
                   }
               }) {

    private class Subscriber(val `object`: Any, val priority: Int, private val invoker: InvokerType.SubscriberMethod?) {

        @Throws(Exception::class)
        operator fun invoke(arg: Any?) {
            invoker!!.invoke(arg)
        }

        override fun equals(other: Any?): Boolean {
            return other.hashCode() == this.hashCode()
        }

        override fun hashCode(): Int {
            return `object`.hashCode()
        }

    }

    private class PriorityCopyAndWriteArrayList : CopyOnWriteArrayList<Subscriber>() {
        override fun add(element: Subscriber): Boolean {
            if (size == 0) {
                super.add(element)
            } else {
                var index = this.binarySearch(element, Comparator.comparingInt { obj: Subscriber -> obj.priority })
                if (index < 0) index = -(index + 1)
                super.add(index, element)
            }
            return true
        }
    }

    private val subscribers: ConcurrentHashMap<Class<*>, PriorityCopyAndWriteArrayList> = ConcurrentHashMap()

    fun register(obj: Any) {
        for (method in obj.javaClass.declaredMethods) {
            val sub: Subscribe = method.getAnnotation(Subscribe::class.java) ?: continue

            // Verification
            Intrinsics.areEqual(method.returnType, Void.TYPE)
            Intrinsics.areEqual(method.parameterCount, 1)
            Intrinsics.areEqual(method.modifiers and Modifier.STATIC, Modifier.STATIC)

            // Parameter verification
            val parameterClazz = method.parameterTypes[0]
            when {
                parameterClazz.isPrimitive -> throw IllegalArgumentException("Cannot subscribe method to a primitive.")
                parameterClazz.modifiers and (Modifier.ABSTRACT or Modifier.INTERFACE) != 0 -> throw IllegalArgumentException("Cannot subscribe method to a polymorphic class.")
            }

            val subscriberMethod = invokerType.setup(obj, obj.javaClass, parameterClazz, method)

            val subscriber = Subscriber(obj, sub.priority, subscriberMethod)
            subscribers.putIfAbsent(parameterClazz, PriorityCopyAndWriteArrayList())
            subscribers[parameterClazz]!!.add(subscriber)
        }
    }

    fun unregister(obj: Any) {
        for (method in obj.javaClass.declaredMethods) {
            if (method.getAnnotation(Subscribe::class.java) == null) {
                continue
            }
            subscribers[method.parameterTypes[0]]?.remove(Subscriber(obj, -1, null))
        }
    }

    fun post(event: Any) {
        val events = subscribers[event.javaClass] ?: return
        // executed in descending order
        for (i in (events.size-1) downTo 0) {
            try {
                events[i].invoke(event)
            } catch (e: Exception) {
                exceptionHandler.handle(e)
            }
        }

    }

    private inline fun iterateSubclasses(obj: Any, body: (Class<*>) -> Unit) {
        var postClazz: Class<*>? = obj.javaClass
        do {
            body(postClazz!!)
            postClazz = postClazz.superclass
        } while (postClazz != null)
    }
}