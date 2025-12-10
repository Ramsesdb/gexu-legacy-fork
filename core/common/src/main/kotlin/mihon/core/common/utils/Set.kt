package mihon.core.common.utils

fun <T> Set<T>.mutate(action: (MutableSet<T>) -> Unit): Set<T> = toMutableSet().apply(action)

