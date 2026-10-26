package com.ghostpanter.files.ui

/**
 * MT 管理器式滑动多选（连选）：
 * - 滑动一个文件：选中/取消它并进入多选，被选中的文件成为锚点；
 * - 之后再滑动另一个文件：锚点与它之间（按当前可见顺序，含两端）全部选中，它成为新锚点；
 * - 点击只做单个切换，不改变锚点。
 *
 * 纯函数，便于 JVM 单测。返回新的选中集合与新锚点。
 */
data class SwipeResult(val selected: Set<String>, val anchor: String?)

fun swipeSelect(selected: Set<String>, anchor: String?, visible: List<String>, target: String): SwipeResult {
    if (anchor != null && anchor != target && anchor in selected) {
        val a = visible.indexOf(anchor)
        val b = visible.indexOf(target)
        if (a >= 0 && b >= 0) {
            val range = visible.subList(minOf(a, b), maxOf(a, b) + 1)
            return SwipeResult(selected + range, target)
        }
    }
    // 没有有效锚点（或再次滑动锚点本身）：单个切换。
    return if (target in selected) SwipeResult(selected - target, null)
    else SwipeResult(selected + target, target)
}
