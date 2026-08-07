package com.blackclaw.android.ui.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.blackclaw.android.base.BaseActivity
import com.blackclaw.android.memory.MemoryInventory
import com.blackclaw.android.ui.design.ClawCard
import com.blackclaw.android.ui.design.ClawIconAction
import com.blackclaw.android.ui.design.ClawPalette
import com.blackclaw.android.ui.design.ClawReveal
import com.blackclaw.android.ui.design.ClawSecondaryButton
import com.blackclaw.android.ui.design.ClawShimmerList
import com.blackclaw.android.ui.design.ClawTextColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows the user everything BlackClaw has learned about them, and lets them delete it.
 *
 * ## Why this screen exists
 *
 * The memory subsystem infers a profile (name, city, sleep schedule, frequent contacts
 * and apps) from ordinary phone use, stores it unencrypted, and injects it into the
 * system prompt on every request — which for a cloud model means it leaves the device
 * every time. Before this screen there was no way to see any of it, and the existing
 * `forgetAll()` had no callers at all. Inference the user cannot inspect is the part
 * that makes an assistant feel invasive, so the screen leads with what leaves the
 * device rather than burying it.
 *
 * All reads and deletes go through [MemoryInventory] so the numbers on screen cannot
 * drift from what is actually stored.
 */
class MemoryPrivacyActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var snapshot by remember { mutableStateOf<MemoryInventory.Snapshot?>(null) }
            var confirmWipe by remember { mutableStateOf(false) }
            var toast by remember { mutableStateOf("") }

            fun reload() {
                lifecycleScope.launch {
                    // MMKV reads plus a MemoryHub assemble; off the main thread.
                    snapshot = withContext(Dispatchers.IO) { MemoryInventory.snapshot() }
                }
            }

            LaunchedEffect(Unit) { reload() }

            MemoryPrivacyScreen(
                snapshot = snapshot,
                notice = toast,
                onBack = { finish() },
                onForget = { id ->
                    lifecycleScope.launch {
                        val removed = withContext(Dispatchers.IO) { MemoryInventory.forget(id) }
                        toast = if (removed > 0) "Se borraron $removed elementos"
                        else "No había nada que borrar"
                        // Re-read rather than mutating the list locally: if a delete
                        // silently failed, the screen must show that it is still there.
                        snapshot = withContext(Dispatchers.IO) { MemoryInventory.snapshot() }
                    }
                },
                onWipeAll = { confirmWipe = true },
            )

            if (confirmWipe) {
                AlertDialog(
                    onDismissRequest = { confirmWipe = false },
                    containerColor = ClawPalette.Elevation.Level3,
                    title = { Text("¿Borrar toda la memoria?", color = ClawTextColors.Primary) },
                    text = {
                        Text(
                            "Se borra el perfil aprendido, los hechos que pediste " +
                                "recordar, los resúmenes de conversaciones y el historial " +
                                "de tareas. No se puede deshacer.",
                            color = ClawTextColors.Secondary,
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmWipe = false
                            lifecycleScope.launch {
                                val n = withContext(Dispatchers.IO) {
                                    MemoryInventory.forgetEverything()
                                }
                                toast = "Memoria borrada ($n elementos)"
                                snapshot = withContext(Dispatchers.IO) {
                                    MemoryInventory.snapshot()
                                }
                            }
                        }) { Text("Borrar todo", color = ClawPalette.Danger.base) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmWipe = false }) {
                            Text("Cancelar", color = ClawTextColors.Secondary)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryPrivacyScreen(
    snapshot: MemoryInventory.Snapshot?,
    notice: String,
    onBack: () -> Unit,
    onForget: (String) -> Unit,
    onWipeAll: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ClawPalette.Elevation.Level0),
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = 8.dp, bottom = 32.dp,
            ),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClawIconAction(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        onClick = onBack,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Privacidad de la memoria",
                        color = ClawTextColors.Primary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            item {
                LeavesDeviceCard(snapshot)
                Spacer(Modifier.height(14.dp))
            }

            if (notice.isNotBlank()) {
                item {
                    Text(
                        notice,
                        color = ClawPalette.Finance.base,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }

            if (snapshot == null) {
                item { ClawShimmerList(rows = 4, rowHeight = 120.dp) }
            } else {
                itemsIndexed(snapshot.categories) { index, category ->
                    ClawReveal(index = index) {
                        CategoryCard(category = category, onForget = { onForget(category.id) })
                    }
                    Spacer(Modifier.height(12.dp))
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    ClawSecondaryButton(
                        text = "Borrar toda la memoria",
                        onClick = onWipeAll,
                        accent = ClawPalette.Danger,
                        icon = Icons.Outlined.DeleteOutline,
                        enabled = !snapshot.isEmpty,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The header, stating the cost before the contents.
 *
 * Leads with the fact that this data is sent to the model, because that is the part
 * the user cannot discover on their own. The token figure is labelled approximate on
 * purpose: tokenisation is model-specific, and a precise-looking number the user has
 * no way to check would be worse than an honest estimate.
 */
@Composable
private fun LeavesDeviceCard(snapshot: MemoryInventory.Snapshot?) {
    ClawCard(
        modifier = Modifier.fillMaxWidth(),
        accent = ClawPalette.Guard,
        elevated = true,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    null,
                    tint = ClawPalette.Guard.base,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Esto sale del teléfono",
                    color = ClawTextColors.Primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Cuando usas un modelo en la nube, todo lo de abajo se envía junto con " +
                    "cada mensaje para que el asistente te conozca. Con un modelo local " +
                    "no sale de aquí.",
                color = ClawTextColors.Secondary,
                fontSize = 13.sp,
            )
            if (snapshot != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Shield,
                        null,
                        tint = ClawTextColors.Tertiary,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${snapshot.totalItems} datos guardados · " +
                            "~${MemoryInventory.approxTokens(snapshot.promptCostChars)} " +
                            "tokens por mensaje",
                        color = ClawTextColors.Tertiary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** One memory category: what it is, how much there is, a sample, and a delete. */
@Composable
private fun CategoryCard(
    category: MemoryInventory.Category,
    onForget: () -> Unit,
) {
    val accent = when (category.id) {
        "profile" -> ClawPalette.Reminder
        "facts" -> ClawPalette.Note
        "conversations" -> ClawPalette.Event
        else -> ClawPalette.Shopping
    }
    ClawCard(
        modifier = Modifier.fillMaxWidth(),
        accent = accent,
        accentEdge = true,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    category.title,
                    color = ClawTextColors.Primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.base.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "${category.count}",
                        color = accent.base,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                category.explanation,
                color = ClawTextColors.Secondary,
                fontSize = 12.sp,
            )

            if (category.preview.isEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Vacío", color = ClawTextColors.Tertiary, fontSize = 12.sp)
            } else {
                Spacer(Modifier.height(12.dp))
                category.preview.forEach { item ->
                    Row(Modifier.padding(bottom = 6.dp)) {
                        Text(
                            item.label,
                            color = accent.light,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (item.detail.isNotBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                item.detail,
                                color = ClawTextColors.Secondary,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (category.count > category.preview.size) {
                    Text(
                        "y ${category.count - category.preview.size} más",
                        color = ClawTextColors.Tertiary,
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            ClawSecondaryButton(
                text = "Olvidar esto",
                onClick = onForget,
                accent = ClawPalette.Danger,
                icon = Icons.Outlined.DeleteOutline,
                enabled = category.count > 0,
            )
        }
    }
}
