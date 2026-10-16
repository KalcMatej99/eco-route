package org.kalc.ecoroute

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun AddressSuggestions(
    address: AddressState,
    history: List<Place>,
    tagPrefix: String,
    showWhenSelected: Boolean = false,
    onSelect: (Place) -> Unit,
    onRemove: (Place) -> Unit,
    onClear: () -> Unit,
) {
    val suggestions = RecentPlaces.suggestions(history,address,showWhenSelected)
    if (suggestions.recent.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent places",style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f).testTag("recent-heading-$tagPrefix"))
            TextButton(onClick = onClear, modifier = Modifier.testTag("clear-recent-$tagPrefix")
                .semantics { contentDescription = "Clear recent places" }) {
                Text("Clear")
            }
        }
    }
    @Composable fun suggestion(place: Place, recent: Boolean) {
        Surface(onClick = { onSelect(place) },shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().testTag("${if (recent) "recent-" else ""}$tagPrefix-${place.id}")) {
            Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = if (recent) 4.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (recent) Icon(Icons.Default.History,null,Modifier.size(20.dp),tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(place.title,fontWeight = FontWeight.Medium,maxLines = 2,overflow = TextOverflow.Ellipsis)
                    if (place.detail.isNotBlank()) Text(place.detail,style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,overflow = TextOverflow.Ellipsis)
                }
                if (recent) IconButton(onClick = { onRemove(place) },modifier = Modifier.testTag("remove-recent-$tagPrefix-${place.id}")) {
                    Icon(Icons.Default.Close,"Remove ${place.title} from recent places")
                }
            }
        }
    }
    suggestions.recent.forEach { suggestion(it,true) }
    if (suggestions.online.isNotEmpty()) {
        Text("Search results",style = MaterialTheme.typography.labelLarge,modifier = Modifier.testTag("online-heading-$tagPrefix"))
    }
    suggestions.online.forEach { suggestion(it,false) }
}
