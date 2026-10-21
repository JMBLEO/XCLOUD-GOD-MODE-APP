package com.aistudio.xcloudgodmode.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

data class XboxFriend(
    val gamertag: String,
    val gamerpicUrl: String,
    val isOnline: Boolean,
    val currentActivity: String
)

@Composable
fun PeopleHubSidebar(
    modifier: Modifier = Modifier
) {
    val friends = listOf(
        XboxFriend(
            gamertag = "MasterChief99",
            gamerpicUrl = "https://store-images.s-microsoft.com/image/apps.21536.13727851868390641.5bfa6060-6395-4eb8-8c1d-15ec8b3c9ec4.0e6aa457-3773-4560-8f69-7c46f14e74dc",
            isOnline = true,
            currentActivity = "Jugando a Halo Infinite"
        ),
        XboxFriend(
            gamertag = "MarcusFenix_COG",
            gamerpicUrl = "https://store-images.s-microsoft.com/image/apps.49077.14327242129181180.e4d36ef5-33ec-42f8-a149-a2926719e7a8.723cb829-d5e1-45a4-9e79-56e6d1eb2be1",
            isOnline = true,
            currentActivity = "Jugando a Gears 5"
        ),
        XboxFriend(
            gamertag = "HorizonDrifter",
            gamerpicUrl = "https://store-images.s-microsoft.com/image/apps.43956.13727851868390641.c9cc8f66-aff8-44ae-85fb-5b6c3ea31e17.c467a304-4b47-49f3-8f0a-37c02b1f8fb8",
            isOnline = true,
            currentActivity = "Jugando a Forza Horizon 5"
        ),
        XboxFriend(
            gamertag = "CortanaAI",
            gamerpicUrl = "https://store-images.s-microsoft.com/image/apps.23249.13727851868390641.5bfa6060-6395-4eb8-8c1d-15ec8b3c9ec4.8e041639-65cb-4fa1-831e-450fdf58882b",
            isOnline = false,
            currentActivity = "Desconectado (hace 2h)"
        )
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(Color(0xFF14171A))
            .border(width = 1.dp, color = Color(0xFF22262B))
            .padding(16.dp)
            .testTag("people_hub_sidebar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Icon(Icons.Default.Group, contentDescription = null, tint = Color(0xFF107C10))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Amigos en Xbox Live",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(friends) { friend ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C2024), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Box {
                        AsyncImage(
                            model = friend.gamerpicUrl,
                            contentDescription = friend.gamertag,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                        )
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (friend.isOnline) Color(0xFF22C55E) else Color.Gray)
                                .align(Alignment.BottomEnd)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = friend.gamertag,
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Text(
                            text = friend.currentActivity,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (friend.isOnline) Color(0xFF4ADE80) else Color.Gray
                        )
                    }
                }
            }
        }
    }
}
