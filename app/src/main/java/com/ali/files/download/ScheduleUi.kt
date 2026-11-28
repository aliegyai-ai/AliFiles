package com.ali.files.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.files.util.AppSettings
import java.util.Calendar

/** the next time it's [h]:[m] (today if it's still ahead, otherwise tomorrow) */
fun nextAt(h: Int, m: Int): Long {
    val c = Calendar.getInstance()
    c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, m); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
    if (c.timeInMillis <= System.currentTimeMillis() + 60_000) c.add(Calendar.DAY_OF_YEAR, 1)
    return c.timeInMillis
}

fun clock(at: Long): String = java.text.SimpleDateFormat("hh:mm a", java.util.Locale("ar")).format(java.util.Date(at))

/** «ابدأ إمتى؟»: now / in an hour / tonight / at dawn / a time you pick. [startAt] 0 = now. */
@Composable
fun ScheduleChoice(startAt: MutableState<Long>) {
    val ctx = LocalContext.current
    val v = startAt.value
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ابدأ إمتى؟", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = v == 0L, onClick = { startAt.value = 0 }, label = { Text("دلوقتي") })
            FilterChip(selected = false, onClick = { startAt.value = System.currentTimeMillis() + 3_600_000L }, label = { Text("بعد ساعة") })
            FilterChip(selected = false, onClick = { startAt.value = nextAt(2, 0) }, label = { Text("الليلة 2 صباحاً") })
            FilterChip(selected = false, onClick = { startAt.value = nextAt(4, 30) }, label = { Text("الفجر 4:30") })
            FilterChip(selected = false, onClick = {
                val now = Calendar.getInstance()
                android.app.TimePickerDialog(ctx, { _, h, m -> startAt.value = nextAt(h, m) }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), false).show()
            }, label = { Text("وقت تاني…") })
        }
        if (v > 0) Text(
            "هيبدأ الساعة ${clock(v)}" + (if (AppSettings.scheduleWifiOnly.value) " على الواي فاي بس" else "") + " — حتى لو التطبيق مقفول",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
        )
    }
}
