package com.xcloud.godmode.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class SortOption(val key: String, val label: String)

val DEFAULT_SORT_OPTIONS = listOf(
    SortOption("relevance", "Relevancia y Catálogo"),
    SortOption("az", "Nombre (A - Z)"),
    SortOption("za", "Nombre (Z - A)"),
    SortOption("newest", "Recientemente Añadidos")
)

@Composable
fun SortOptionModal(
    visible: Boolean,
    currentSort: String,
    onSelectSort: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F1622),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("sort_option_modal")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                    Icon(Icons.Default.Sort, null, tint = Color(0xFF00E676), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Ordenar Catálogo",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                DEFAULT_SORT_OPTIONS.forEach { opt ->
                    val isSelected = opt.key == currentSort
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) Color(0xFF00E676).copy(alpha = 0.14f) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectSort(opt.key)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = opt.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) Color(0xFF00E676) else Color.White
                            )
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = Color(0xFF00E676), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

data class FilterCategoryOption(val key: String, val label: String)

val DEFAULT_FILTER_OPTIONS = listOf(
    FilterCategoryOption("all", "Todos los Juegos"),
    FilterCategoryOption("favorites", "Mis Favoritos ★"),
    FilterCategoryOption("recent", "Jugados Recientemente"),
    FilterCategoryOption("gamepass", "Xbox Game Pass Ultimate"),
    FilterCategoryOption("own", "Juegos en Propiedad (BYOG)"),
    FilterCategoryOption("series", "Optimizados para Xbox Series X|S")
)

@Composable
fun FilterOptionModal(
    visible: Boolean,
    currentFilter: String,
    onSelectFilter: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0F1622),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
            shadowElevation = 16.dp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("filter_option_modal")
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
                    Icon(Icons.Default.FilterList, null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Filtrar por Categoría",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }

                Divider(color = Color.White.copy(alpha = 0.08f), thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                DEFAULT_FILTER_OPTIONS.forEach { opt ->
                    val isSelected = opt.key == currentFilter
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.14f) else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectFilter(opt.key)
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = opt.label,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) Color(0xFF38BDF8) else Color.White
                            )
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
