package rj.qmce.lite.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Thread-safe, allocation-efficient date and time formatting utilities.
 * Uses ThreadLocal to avoid both multithread lock contention and per-render SimpleDateFormat instantiation.
 */
object TimeUtils {
    private val dayKeyFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
    private val chatDateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("M月d日", Locale.getDefault())
    }
    private val timeHourMinuteFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }
    private val dateMonthDayFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM/dd", Locale.getDefault())
    }
    private val monthDayHourMinuteFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    fun toDayKey(seconds: Long): String {
        if (seconds <= 0) return ""
        return dayKeyFormat.get()?.format(Date(seconds * 1000L)).orEmpty()
    }

    fun formatChatDate(seconds: Long): String {
        if (seconds <= 0) return ""
        return chatDateFormat.get()?.format(Date(seconds * 1000L)).orEmpty()
    }

    fun formatHourMinute(seconds: Long): String {
        if (seconds <= 0) return ""
        return timeHourMinuteFormat.get()?.format(Date(seconds * 1000L)).orEmpty()
    }

    fun formatMonthDay(seconds: Long): String {
        if (seconds <= 0) return ""
        return dateMonthDayFormat.get()?.format(Date(seconds * 1000L)).orEmpty()
    }

    fun formatMonthDayHourMinute(seconds: Long): String {
        if (seconds <= 0) return ""
        return monthDayHourMinuteFormat.get()?.format(Date(seconds * 1000L)).orEmpty()
    }
}
